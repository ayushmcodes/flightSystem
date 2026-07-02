# Flight Booking System

A flight search and booking service built as a modular monolith. One Spring Boot app, one PostgreSQL database, four internal packages (`search`, `inventory`, `booking`, `payment`) that communicate only through Java service interfaces — never by querying each other's tables.

See [design.md](design.md) for the full design: entities, state machines, booking flow, idempotency, and failure scenarios.

---

## Prerequisites

- Docker and Docker Compose
- Java 17+ and Maven (only needed to run tests outside Docker)
- **Mermaid plugin** (if using IntelliJ needed to view the flow and state diagrams in [design.md](design.md))
  - **IntelliJ:** `⌘,` → Plugins → search **Mermaid** (by Vladimir Schneider) → Install → Restart
  - **VSCode:** diagrams render natively with no additional setup

---

## Running the application

From the `main/` directory:

```bash
docker compose up --build
```

This starts two containers:
- **postgres** — Postgres 16. The schema (`db/init/V1__schema.sql`) and seed data (`db/init/V2__seed.sql`) are loaded automatically on first boot.
- **app** — Spring Boot app, waits for Postgres to be healthy then starts on port `8080`.

The API is available at **http://localhost:8080**.

> **Reset the database:** `docker compose down -v` then `docker compose up --build` again. The `-v` flag removes the Postgres data volume so the init scripts re-run.

---

## Seed data

5 flights are pre-loaded, each with 20 available seats (rows 1–2 BUSINESS, rows 3–5 ECONOMY, columns A–D):

| ID  | Flight  | Route   | Departure (UTC)     | Fare |
|-----|---------|---------|---------------------|------|
| SF1 | 6E-203  | BLR→DEL | 2026-07-15 08:00    | 5000 |
| SF2 | 6E-411  | BLR→DEL | 2026-07-16 18:00    | 5500 |
| SF3 | AI-505  | BLR→BOM | 2026-07-15 09:30    | 4200 |
| SF4 | UK-810  | DEL→BLR | 2026-07-20 07:00    | 6000 |
| SF5 | 6E-777  | BLR→DEL | 2026-07-20 14:00    | 4800 |

Valid seat numbers: `1A`–`5D` (e.g. `3A`, `2B`, `5D`).

---

## APIs

### 1. Search flights

```bash
curl "http://localhost:8080/flights/search?source=BLR&destination=DEL&date=2026-07-15"
```

Response:
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

`availableSeats` counts seats that are `AVAILABLE` plus any `HELD` seats whose hold TTL has expired.

---

### 2. Initiate a booking

Holds a seat and creates a payment intent. Returns `PENDING`. Requires an `Idempotency-Key` header — use a stable unique value per request and keep it the same across retries.

```bash
curl -i -X POST http://localhost:8080/bookings/initiate \
  -H 'Idempotency-Key: my-request-001' \
  -H 'Content-Type: application/json' \
  -d '{
        "userId": "U1",
        "scheduledFlightId": "SF1",
        "seatNo": "3A"
      }'
```

Response (`200`):
```json
{
  "bookingId": "BK-...",
  "status": "PENDING",
  "seatNo": "3A",
  "paymentId": "PAY-...",
  "amount": 5000.00
}
```

| Status | Meaning |
|--------|---------|
| `200`  | Seat held, payment intent created, booking is `PENDING` |
| `404`  | Flight or seat not found |
| `409`  | Seat already held or booked by someone else |
| `422`  | Same `Idempotency-Key` reused with a different request body |

---

### 3. Confirm payment (gateway webhook)

In production this endpoint would be called by the payment gateway automatically after the customer pays. Here the gateway is stubbed, so **you need to call this manually** to complete the booking. Use the `paymentId` from the initiate response.

**Success:**
```bash
curl -i -X POST http://localhost:8080/payments/{paymentId}/confirm \
  -H 'Event-Id: evt-001'
```

This transitions: `payment → SUCCESS`, `seat → BOOKED`, `booking → CONFIRMED` — atomically in one transaction.

**Idempotent:** sending the same `Event-Id` again returns `200` and does nothing (webhook dedup via `processed_webhook` table).

---

## Full end-to-end flow

```bash
# 1. Search
curl "http://localhost:8080/flights/search?source=BLR&destination=DEL&date=2026-07-15"

# 2. Initiate — note the scheduledFlightId and pick any seat (e.g. 3A)
curl -i -X POST http://localhost:8080/bookings/initiate \
  -H 'Idempotency-Key: my-request-001' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"U1","scheduledFlightId":"SF1","seatNo":"3A"}'

# 3. Confirm payment — replace PAY-... with the paymentId from step 2
curl -i -X POST http://localhost:8080/payments/PAY-.../confirm \
  -H 'Event-Id: evt-001'

# 4. Search again — seat 3A should no longer appear in availableSeats
curl "http://localhost:8080/flights/search?source=BLR&destination=DEL&date=2026-07-15"
```

---

## Running tests

### Unit tests (no Docker needed)

```bash
cd main
mvn test -Dtest='InventoryServiceImplTest,BookingServiceImplTest,FlightSearchServiceImplTest,PaymentServiceImplTest'
```

25 tests, no Spring context, no database. Runs in a few seconds.

### Integration test (requires Docker)

**Step 1 — start Docker:**

```bash
docker compose up --build
```

**Step 2 — run the integration test:**

```bash
cd main
mvn test -Dtest=BookingFlowIntegrationTest -pl .
```

TestContainers spins up a `postgres:16` container, loads the production schema and seed data, boots the full Spring context, and runs the complete booking lifecycle: search → initiate → confirm → assert final DB state.

### All tests

```bash
cd main
mvn test
```

Requires Docker running for the integration test. Unit tests always pass regardless.
