# Flight Booking System

A flight search + booking service built as a **modular monolith**: one Spring Boot app,
one Postgres database, four internal packages that talk only through Java service
interfaces (in-process, never HTTP, never by reaching into another package's tables).

- `com.flight.inventory` — owns `flight` + `seat` (one aggregate cluster: a flight and its
  seats). Source of truth for the sellable catalog and the **no-oversell** guarantee.
- `com.flight.search` — owns no tables; a read/API module that serves flight search by
  calling inventory's `CatalogQueryService`.
- `com.flight.booking` — owns `booking`; the orchestrator (the saga).
- `com.flight.payment` — owns `payment`; the payment intent (gateway stubbed).

`search` and `booking` both call `inventory` through its interfaces (`CatalogQueryService`
for flight reads/fare, `InventoryService` for seat holds); `booking` also drives `payment`
via `PaymentService`. Because `flight` and `seat` share one owner, the availability JOIN is
internal to `inventory` — **no package ever queries another package's table** (no
exceptions). The seams are explicit so any package could be extracted into its own service
later.

See [design.md](design.md) for the full design (entities, state machines, indexes,
failure scenarios).

## Run it

Requires Docker. From this directory:

```bash
docker compose up --build
```

This starts exactly two containers:

- `postgres` (Postgres 16) — the schema (`db/init/V1__schema.sql`) and seed
  (`db/init/V2__seed.sql`) are mounted into `docker-entrypoint-initdb.d` and run
  automatically on first boot.
- `app` — waits for Postgres to become healthy (`depends_on: condition: service_healthy`,
  backed by a `pg_isready` healthcheck), then connects on
  `jdbc:postgresql://postgres:5432/flightdb`.

The API is exposed on the host at **http://localhost:8080**.

> Re-seeding: the init scripts only run when the Postgres data volume is first created.
> To reset the DB, `docker compose down -v` then `up` again.

## Seed data

5 dated flights, 20 `AVAILABLE` seats each (rows 1–2 BUSINESS, rows 3–5 ECONOMY):

| scheduled_flight_id | flight | route | departure (UTC) | base fare |
|---|---|---|---|---|
| SF1 | 6E-203 | BLR→DEL | 2026-07-15 08:00 | 5000 |
| SF2 | 6E-411 | BLR→DEL | 2026-07-16 18:00 | 5500 |
| SF3 | AI-505 | BLR→BOM | 2026-07-15 09:30 | 4200 |
| SF4 | UK-810 | DEL→BLR | 2026-07-20 07:00 | 6000 |
| SF5 | 6E-777 | BLR→DEL | 2026-07-20 14:00 | 4800 |

## API 1 — Search flights

```bash
curl "http://localhost:8080/flights/search?source=BLR&destination=DEL&date=2026-07-15"
```

```json
{
  "flights": [
    {
      "scheduledFlightId": "SF1",
      "flightNumber": "6E-203",
      "source": "BLR",
      "destination": "DEL",
      "departureTime": "2026-07-15T08:00:00Z",
      "arrivalTime": "2026-07-15T10:50:00Z",
      "availableSeats": 20,
      "baseFare": 5000.00
    }
  ]
}
```

`availableSeats` counts `AVAILABLE` seats **plus** `HELD` seats whose hold has expired
(lazy expiry). It is a snapshot, not a guarantee.

## API 2 — Initiate a booking

Holds a seat and creates a payment intent, returning `PENDING`. Requires an
`Idempotency-Key` header.

```bash
curl -i -X POST http://localhost:8080/bookings/initiate \
  -H 'Idempotency-Key: key-001' \
  -H 'Content-Type: application/json' \
  -d '{
        "userId": "U1",
        "scheduledFlightId": "SF1",
        "seatNo": "12A"
      }'
```

```json
{
  "bookingId": "BK-...",
  "status": "PENDING",
  "seatNo": "12A",
  "paymentId": "PAY-...",
  "amount": 5000.00
}
```

> Note: the seed uses seat numbers `1A`–`5D` (rows 1–5, columns A–D). Use e.g. `3A`.

### Behaviours

- **Idempotency:** replaying the same `Idempotency-Key` with the **same body** returns the
  original response (no new hold, no new payment). Same key + **different body** → `422`.
- **No overselling:** two concurrent initiates on the same seat → exactly one `200`, the
  other `409`. Enforced by a single atomic conditional `UPDATE` (rowcount), never by
  application-level locking.
- **404** — unknown `scheduledFlightId` or `seatNo`.
- **409** — the seat was already held/booked by someone else.

## How the no-oversell guarantee works

`inventory.holdSeat` is one atomic, owner-and-state-guarded conditional UPDATE:

```sql
UPDATE seat SET status='HELD', booking_id=:bid, hold_expires_at=:exp
WHERE scheduled_flight_id=:f AND seat_no=:s
  AND ( status='AVAILABLE'
        OR (status='HELD' AND hold_expires_at < now())   -- lazy expiry reclaim
        OR booking_id=:bid );                             -- idempotent retry by owner
```

The DB serializes concurrent writers on the row: exactly one sees rowcount `1` (held), the
rest see `0` (lost → `409`). The `booking_id=:bid` branch makes a retried hold by the same
owner an idempotent no-op success.

## Scope

`POST /bookings/initiate` performs everything up to **seat held + payment intent created**
(returns `PENDING`). Payment-success → commit seat → `CONFIRMED` is out of scope; the
gateway is stubbed. `InventoryService` implements `commitSeat`/`releaseSeat` for
completeness. A background sweeper flips stale `HELD` seats back to `AVAILABLE` (reporting
only; lazy expiry already covers correctness).

## Known gap

Automated tests (concurrent-hold no-oversell, idempotent re-initiate, search→initiate
integration) are not yet included and should be added before this is considered complete.
