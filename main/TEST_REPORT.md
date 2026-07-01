# Plan: TEST_REPORT.md

## Context
All unit and integration tests have been written. The report documents what each test
covers, the rationale for picking it, and the current pass/fail result. Intended as a
quick reference for reviewers.

---

## Results

24 unit tests run with pure Mockito (no Spring context, no DB); 1 integration test requires Docker and runs the full Spring context against a real `postgres:16` container.

| Test class | Tests | Result |
|---|---|---|
| InventoryServiceImplTest | 6 | ✅ PASS |
| BookingServiceImplTest | 10 | ✅ PASS |
| FlightSearchServiceImplTest | 2 | ✅ PASS |
| PaymentServiceImplTest | 6 | ✅ PASS |
| BookingFlowIntegrationTest | 1 | 🐳 Requires Docker |
| **Total** | **25** | **24 passed** |

---

## Tests

#### InventoryServiceImplTest (6 tests)

---

**`holdSeat_won_returnsHeld`**
- **What it covers:** `InventoryService.holdSeat` is the first write in the booking saga — it attempts to exclusively reserve a seat for a given booking. Under the hood this is a single atomic conditional SQL UPDATE: the seat row transitions from `AVAILABLE` to `HELD` only if no other writer has claimed it first. When that UPDATE affects exactly one row, meaning this caller won the concurrent race, the service must read the seat back (to surface its database primary key) and return `HoldResult.HELD` carrying both the `seatId` and `seatNo`. This test verifies that successful acquisition produces the correct outcome and that both fields are populated with the values from the seat row.
- **Why this case:** `seatId` is written onto the booking row immediately after and later passed to `commitSeat(seatId, bookingId)`. A null or wrong id here silently breaks the entire confirm path — the commit UPDATE would match zero rows and force the booking into FAILED even though the seat was genuinely acquired.

**`holdSeat_seatExistsButLost_returnsConflict`**
- **What it covers:** When two clients race for the same seat, only one can win the conditional UPDATE. The loser sees zero rows affected. To tell `BookingServiceImpl` whether to return HTTP 409 or 404, the service calls `findByScheduledFlightIdAndSeatNo` after the failed UPDATE to check if the seat row exists at all. This test covers the case where that lookup finds the seat row — confirming the seat exists but is currently held by a different booking. The expected outcome is `HoldResult.CONFLICT` with a null `seatId`, because this caller has no ownership over that seat.
- **Why this case:** Returning `HELD` or a non-null `seatId` on the conflict path would link a seat owned by another booking to this one — a direct oversell. The null `seatId` assertion is deliberate: a caller that did not win the race must not receive an id it cannot use.

**`holdSeat_seatNotFound_returnsNotFound`**
- **What it covers:** Same lost-UPDATE path as above, but the `findByScheduledFlightIdAndSeatNo` lookup returns nothing — no seat row with this `(flightId, seatNo)` pair exists at all. This happens when a client sends an invalid seat number or a seat that was never provisioned on this flight. The expected outcome is `HoldResult.NOT_FOUND`.
- **Why this case:** `BookingServiceImpl` switches on the `HoldResult` enum to choose the HTTP status code: `CONFLICT` → 409 (seat taken), `NOT_FOUND` → 404 (seat does not exist). Conflating the two responses would tell a client that submitted a nonsense seat number that the seat is merely unavailable, pointing them toward a retry that will never succeed.

**`commitSeat_rowcount1_returnsTrue`**
- **What it covers:** `commitSeat` is called from `BookingServiceImpl.confirm` after a payment webhook is received. It runs a state-guarded UPDATE — `SET status='BOOKED' WHERE seat_id=:sid AND booking_id=:bid AND status='HELD'` — that atomically transitions the seat to BOOKED only if it is still HELD by the right owner. When that UPDATE affects one row, the seat is successfully committed and `commitSeat` must return `true`.
- **Why this case:** `BookingServiceImpl.confirm` branches on this boolean: `true` → proceed to `confirmBooking`, `false` → call `markFailed`. Mapping a successful commit as `false` would unconditionally mark every payment-confirmed booking as FAILED the moment the webhook fires.

**`commitSeat_rowcount0_returnsFalse`**
- **What it covers:** When the commit UPDATE affects zero rows, the seat was not in the expected `HELD` state for this `bookingId` — the hold TTL may have expired while payment was being processed, or the seat was somehow reclaimed. `commitSeat` must return `false`.
- **Why this case:** Mapping a failed commit as `true` is the worst possible outcome: the service would call `confirmBooking` on a booking that has no `BOOKED` seat, violating the core system invariant that a `CONFIRMED` booking always has a corresponding `BOOKED` seat.

**`releaseSeat_rowcount1_returnsTrue`**
- **What it covers:** `releaseSeat` is the compensation action used when `initiate` fails partway through — specifically when the seat hold succeeded but a subsequent step (e.g. payment-intent creation) threw. It runs `SET status='AVAILABLE' WHERE booking_id=:bid AND status='HELD'`, returning the seat to the pool. When that UPDATE affects one row, `releaseSeat` must return `true`.
- **Why this case:** A `true` return confirms the seat is back in the `AVAILABLE` pool and can be acquired by other bookings. An incorrect `false` here would suggest to callers that compensation failed when it actually succeeded, potentially triggering unnecessary escalation.


#### BookingServiceImplTest (10 tests)

---

**`initiate_happyPath_createBookingInPendingState`**
- **What it covers:** `initiate` orchestrates four steps in sequence: resolve the flight's base fare via `CatalogQueryService`, insert a PENDING booking row, acquire a seat hold via `InventoryService`, create a payment intent via `PaymentService`, and link both the acquired `seatId` and the `paymentId` onto the booking row. This test verifies the full happy path: the returned `BookingResponse` has status=PENDING, `seatNo="3A"`, `paymentId="PAY-1"`, and `amount` matching the flight fare. It also verifies that `linkSeat` was called with the seat's database primary key (`SEAT-SF1-3A`, not just the seat number) and that `linkPayment` was called with the payment id.
- **Why this case:** The booking row is the saga's coordination record — it holds references to both the inventory side (`seatId`) and the payment side (`paymentId`) so that when `confirm` fires later it has everything it needs. If `linkSeat` is not called, `confirm` loads the booking and finds `seatId=null`; it passes that null to `commitSeat(null, bookingId)`, the guarded UPDATE matches zero rows, `commitSeat` returns false, and the booking is immediately marked FAILED — even though the seat was genuinely held and the customer has paid. If `linkPayment` is not called, the client gets a `paymentId` in the response that was never written back to the booking row; any attempt to look up which payment belongs to which booking (for refunds, reconciliation, or audit) would find nothing. Both link calls are the handshake that wires inventory and payment into a coherent saga record.

**`initiate_duplicateKey_sameBody_returnsExistingBooking`**
- **What it covers:** The idempotency mechanism relies on a UNIQUE constraint on `idempotency_key` in the booking table. When a client retries with the same key, the `saveAndFlush` call throws `DataIntegrityViolationException`. The service catches this, looks up the existing booking via `findByIdempotencyKey`, resolves the original seat number from the stored `seatId` via `InventoryService.getSeatNo`, then compares the three identifying fields from the request (userId, scheduledFlightId, seatNo) against what was persisted. When they match, it returns the original `BookingResponse`. This test verifies that the correct `bookingId` is returned and that `holdSeat` is never called on the retry.
- **Why this case:** Without this replay path, a network timeout and retry would attempt to insert a new PENDING booking and hold a second seat under a different `bookingId`. That leaked HELD seat would never be committed or released, quietly consuming inventory.

**`initiate_duplicateKey_differentBody_throws422`**
- **What it covers:** Same duplicate-key path — `saveAndFlush` throws, `findByIdempotencyKey` returns the existing booking, `getSeatNo` resolves the seat. But the existing booking has `userId="DIFFERENT_USER"` while the new request has `userId="U1"`. After comparing the three identifying fields and detecting the mismatch, the service throws `IdempotencyConflictException`.
- **Why this case:** An idempotency key is scoped to one specific request. Reusing the same key with a different body means either a client bug (mixing up keys) or an attempt to update a booking already in flight. Silently returning the first booking would hand one user's booking details to a caller acting as a different user.

**`initiate_seatConflict_marksFailedAndThrows409`**
- **What it covers:** `initiate` is structured as a saga — once the PENDING booking is inserted, every subsequent step that fails requires compensation before the method can throw. When `holdSeat` returns `HoldResult.CONFLICT` (the conditional UPDATE acquired no rows because another booking owns the seat), the service is in a state where a booking row exists in the DB but no seat was secured. As compensation, it calls `markFailed` on that booking row, transitioning it from PENDING to FAILED, before throwing `SeatUnavailableException`. This test asserts both: that `SeatUnavailableException` is thrown, and that `markFailed` was called. Both assertions are necessary — the exception alone does not prove the booking row was closed.
- **Why this case:** At the point where `holdSeat` returns CONFLICT, a PENDING booking already exists in the database. Without calling `markFailed`, that row stays permanently PENDING with no seat and no payment linked to it. There is no automatic path out of PENDING except through a payment webhook (which will never arrive, because no payment intent was ever created) or the background EXPIRED sweep, which only runs periodically. Until the sweep fires, the booking appears in any status scan as a valid in-flight reservation — indistinguishable from a booking that is legitimately waiting for payment — while actually holding no inventory at all.

**`initiate_seatNotFound_marksFailedAndThrows404`**
- **What it covers:** Same post-INSERT compensation path as the CONFLICT case. `holdSeat` returns `HoldResult.NOT_FOUND` this time, meaning no seat row with the requested `(flightId, seatNo)` pair exists on this flight at all, as opposed to CONFLICT where the seat exists but is taken. The service calls `markFailed` on the booking row to transition it to FAILED, then throws `NotFoundException`. The test asserts both: the exception type and the `markFailed` call.
- **Why this case:** The ghost-state risk is identical to the CONFLICT case — a PENDING booking with no seat must be immediately closed to prevent it lingering as unreachable state. What differs is the signal sent to the caller: 404 (NOT_FOUND) tells the client the seat number itself is invalid and retrying with the same seat on this flight will never succeed, whereas 409 (CONFLICT) tells the client the seat exists but is taken and they should try a different seat.

**`initiate_flightNotFound_throws404`**
- **What it covers:** `CatalogQueryService.findFare` returns `Optional.empty()`, meaning no flight with that `scheduledFlightId` exists. The service throws `NotFoundException` immediately, before touching the booking repository at all. The test asserts the exception and verifies that `bookingRepository` had zero interactions.
- **Why this case:** The fare lookup is deliberately the first step, before the booking INSERT. If the booking were inserted first and the fare lookup failed after, the result would be an orphaned PENDING booking with no valid flight reference and no linked seat — permanently unresolvable without manual cleanup.

**`confirm_bookingNotFound_doesNotAttemptSeatCommit`**
- **What it covers:** `confirm` is the second half of the booking saga, invoked by `BookingEventListener` when it receives a `PaymentConfirmedEvent` that `PaymentServiceImpl` publishes after a successful payment transition. Its job is to commit the held seat to BOOKED and then mark the booking CONFIRMED. Before doing any of that, it loads the booking via `findById`. This test covers the case where that lookup returns empty — no booking exists for the given id. The method exits immediately; neither `InventoryService` nor `BookingRepository` (beyond the initial `findById`) receive any further calls.
- **Why this case:** The event-driven confirm path can receive a `bookingId` that doesn't exist for several reasons: the webhook was redelivered after an unusually long delay and the booking was purged in a cleanup job, the event was produced by a test or staging environment but routed to production, or the id was already handled and the booking row was archived. In all of these cases the correct behavior is a silent no-op. Without this guard, execution would proceed to `commitSeat(booking.getSeatId(), bookingId)` on a null `booking` reference, throwing a `NullPointerException` and turning what should be a benign no-op into an unhandled exception.

**`confirm_bookingAlreadyConfirmed_doesNotAttemptSeatCommit`**
- **What it covers:** When `findById` returns a booking whose status is already CONFIRMED (not PENDING), the method exits before calling `commitSeat`. `inventoryService` receives no calls.
- **Why this case:** Without the PENDING check, `confirm` called on an already-CONFIRMED booking would proceed to `commitSeat(seatId, bookingId)`. The seat is already BOOKED at this point — the commit UPDATE requires `status='HELD'`, so it matches zero rows and returns false. That false return triggers `markFailed(bookingId)` — but `markFailed` is itself state-guarded (`WHERE status='PENDING'`), so it is also a no-op. The method eventually returns without corrupting state, but it has made two unnecessary DB roundtrips and silently re-entered the saga path. The PENDING check short-circuits all of this before any DB operation runs, making it explicit in the service layer that `confirm` is only meaningful for bookings still waiting for their payment to complete.

**`confirm_seatCommitFails_marksBookingFailed`**
- **What it covers:** With a PENDING booking loaded, `InventoryService.commitSeat(SEAT_ID, "BK-1")` returns `false` — the seat hold had expired (the payment took longer than the TTL) or the seat was reclaimed by the time the webhook arrived. The service calls `markFailed("BK-1")` and the test verifies that `confirmBooking` is never called.
- **Why this case:** The system's central correctness invariant is that a CONFIRMED booking must always have a BOOKED seat. Calling `confirmBooking` when `commitSeat` failed would produce a CONFIRMED booking with a seat still in HELD or AVAILABLE state — money has been taken but no seat is allocated, an unrecoverable state that looks like a completed booking to the customer.

**`confirm_happyPath_orderingEnforced`**
- **What it covers:** With a PENDING booking and `commitSeat` returning `true`, both `commitSeat` and `confirmBooking` are called. The test uses Mockito `InOrder` to assert that `commitSeat` is invoked strictly before `confirmBooking` — not just that both were called, but that their call order is correct.
- **Why this case:** The ordering is an explicit design invariant: the seat must be BOOKED before the booking is CONFIRMED. If the booking were confirmed first and a crash occurred before `commitSeat` ran, a reconciliation job would find a CONFIRMED booking with a still-HELD seat — recoverable but requiring special handling. `InOrder` makes this a structural assertion: any refactor that swaps the two calls will fail this test immediately, not silently in production.

#### FlightSearchServiceImplTest (2 tests)

---

**`search_returnsMatchingFlights`**
- **What it covers:** `FlightSearchServiceImpl.search` correctly delegates to `CatalogQueryService.searchFlights`, maps every field from each returned `FlightAvailability` to the corresponding field in `FlightSearchResult`, and returns all results in a `FlightSearchResponse` with no values dropped, swapped, or duplicated. All 8 fields — `scheduledFlightId`, `flightNumber`, `source`, `destination`, `departureTime`, `arrivalTime`, `availableSeats`, `baseFare` — are verified on the first result; the second result is spot-checked to confirm the list contains distinct records and not two copies of the first.
- **Why this case:** `FlightAvailability` and `FlightSearchResult` are record types in separate packages that happen to share field names. The `toResult` mapping passes values positionally to the `FlightSearchResult` constructor — if a field is renamed in `FlightAvailability` without updating `toResult`, the compiler raises no error (the accessor call would fail to resolve, but if the package is restructured or a `get`-style method is used instead, the mismatch is invisible). The per-field assertions also catch a subtler class of bug: `departureTime` and `arrivalTime` are both `OffsetDateTime`, making an accidental swap type-safe and compile-clean while returning wrong schedules for every flight in the API response.

**`search_noFlights_returnsEmptyList`**
- **What it covers:** `CatalogQueryService.searchFlights` returns an empty list — no flights operate on this route on this date. The test calls `search` and asserts that the response's `flights` list is empty and no exception is thrown.
- **Why this case:** Searching a route with no scheduled departures is a valid and frequent query — it should return a clean empty response, not an exception. The current implementation uses `.stream().map(...).toList()` which handles empty input safely, but any future change that assumes at least one result (e.g. using `.findFirst().orElseThrow()` or indexing into the list) would throw here. This test keeps that boundary explicit.

#### PaymentServiceImplTest (6 tests)

---

**`createIntent_firstCall_insertsAndReturnsIntent`**
- **What it covers:** On the first call for a given `bookingId`, `createIntent` correctly persists the payment and returns a `PaymentIntent` DTO with the saved entity's `paymentId` and status `CREATED`. The idempotency key stored on the payment row is derived deterministically from `bookingId` (as `"pay-" + bookingId`) so that any retry can converge to the same payment without a fresh random value.
- **Why this case:** Baseline for the happy path. It also pins the `toIntent` projection — if the method returned `null` for `paymentId` or `status`, the booking orchestrator would write a null `paymentId` onto the booking row and return it in the API response, making the booking unresumable and the client unable to identify the payment to confirm.

**`createIntent_duplicateBookingId_returnsExistingIntent`**
- **What it covers:** The `payment` table enforces `UNIQUE(booking_id)`, guaranteeing at most one payment per booking attempt. When a concurrent call or a retry attempts to insert a second payment for the same `bookingId`, `saveAndFlush` throws `DataIntegrityViolationException`. `createIntent` catches this and falls back to `findByBookingId` to retrieve the already-existing payment, returning that payment's `paymentId`. The test verifies the returned id is the existing one and that `saveAndFlush` was called exactly once — confirming there is no retry loop.
- **Why this case:** `BookingServiceImpl.initiate` is deliberately not `@Transactional` across all steps. If it times out after `createIntent` was called once and the caller retries the entire `initiate`, a second INSERT will hit the constraint. Without this fallback the exception would propagate up, leaving the booking unable to complete even though a valid payment intent already exists. Returning the original `paymentId` means both the caller and the customer converge on the same payment, preventing a second charge.

**`createIntent_duplicateBookingId_lookupAlsoMissing_rethrows`**
- **What it covers:** Same duplicate-key path — `saveAndFlush` throws `DataIntegrityViolationException` — but `findByBookingId` also returns empty. This is a contradictory state: the constraint fired, indicating a row with this `bookingId` exists, yet the immediate lookup finds nothing. `createIntent` rethrows the original exception instance unchanged. The test asserts the thrown exception `isSameAs` the original — not just the same type, but the exact object.
- **Why this case:** The fallback lookup is the only recovery path once the INSERT fails. If it returns empty, proceeding would mean either inserting yet another payment (risking a double charge if the first row becomes visible later) or returning null (silently passing null `paymentId` to the booking row). Neither is safe. Rethrowing fails loudly so the caller can surface a real error rather than continuing in an undefined state. `isSameAs` (rather than `isInstanceOf`) ensures the exception is not re-wrapped, preserving the original stack trace for debugging.

**`confirm_duplicateEventId_doesNotUpdatePaymentOrPublishEvent`**
- **What it covers:** The first action of `confirm` is to insert the `eventId` into `processed_webhook`. If that INSERT throws `DataIntegrityViolationException` (the PK already exists — this event has been seen before), `confirm` returns immediately. `confirmPayment` is never called and `eventPublisher` receives no interactions at all.
- **Why this case:** A gateway redelivering the same webhook is the expected case, not an error. The `processed_webhook` INSERT is the earliest possible dedup point — before the payment row is touched. Without this short-circuit, a redelivered event would call `confirmPayment` (a no-op since the payment is already SUCCESS) and then re-publish `PaymentConfirmedEvent`. That second event would drive `BookingServiceImpl.confirm` again — where both `commitSeat` and `markFailed` would be no-ops individually, but re-entering the saga on a fully completed booking is incorrect behavior. The test asserts zero interactions with both `confirmPayment` and `eventPublisher` to make the full boundary explicit.

**`confirm_paymentNotInCreatedState_doesNotPublishEvent`**
- **What it covers:** After a fresh `eventId` is recorded, `confirm` runs a guarded UPDATE: `UPDATE payment SET status='SUCCESS' WHERE payment_id=:pid AND status='CREATED'`. When that UPDATE affects zero rows — the payment is not in CREATED status — `confirm` returns without publishing `PaymentConfirmedEvent`. The test verifies `eventPublisher` receives no calls.
- **Why this case:** The state guard on `confirmPayment` is the second dedup line after `processed_webhook`. A rowcount of 0 means the payment already transitioned out of CREATED (confirmed by a concurrent request, or put into FAILED). Publishing the event regardless of the rowcount would trigger `BookingServiceImpl.confirm` for a payment that did not actually just succeed — potentially confirming a booking whose payment outcome is unknown.

**`confirm_happyPath_publishesPaymentConfirmedEvent`**
- **What it covers:** When `confirmPayment` returns 1 (payment successfully transitioned from CREATED to SUCCESS), `confirm` reads the `bookingId` from the now-updated payment row via `findById` and publishes `PaymentConfirmedEvent(bookingId)`. The test asserts `publishEvent` is called with the event carrying `bookingId="BK-1"`.
- **Why this case:** `PaymentConfirmedEvent` is the only mechanism that drives the second half of the saga — without it the booking stays PENDING and the seat stays HELD indefinitely. The `bookingId` carried in the event must be read from the persisted payment row rather than derived from a caller parameter, so that the event always refers to the booking the actual payment was created for. The test pins the exact `bookingId` value in the event to verify this lookup and mapping are correct.

#### BookingFlowIntegrationTest (1 test)

---

**`fullBookingLifecycle_searchInitiateConfirm`**
- **What it covers:** The test runs the full lifecycle in four steps against a live database, with a DB-state snapshot taken after each of the two write phases.

  **Step 1 — Search.** `FlightSearchService.search("BLR", "DEL", 2026-07-15)` executes the real availability COUNT query (which uses a `FILTER (WHERE …)` clause) against the `postgres:16` container seeded with `V2__seed.sql`. The assertion that results are non-empty confirms the seed data was loaded correctly and the query runs without error.

  **Step 2 — Initiate.** `bookingService.initiate` runs the saga against the real database: a PENDING booking row is inserted, the atomic conditional UPDATE acquires the hold on seat "3A" (transitioning it from AVAILABLE to HELD), a CREATED payment row is inserted, and both the `seatId` and `paymentId` are linked back onto the booking row — each step committing its own transaction.

  **Mid-flow snapshot.** All three rows are read back from the DB. The booking is PENDING with both `seatId` and `paymentId` populated. The seat is HELD, owned by this booking, with `holdExpiresAt` set to a future timestamp. The payment is CREATED with the correct `bookingId` and amount. These assertions verify each transaction committed the right state before the next step ran.

  **Step 3 — Confirm.** `paymentService.confirm(paymentId, "evt-e2e-1")` inserts into `processed_webhook`, runs the guarded `confirmPayment` UPDATE (CREATED → SUCCESS), then publishes `PaymentConfirmedEvent`. Because the `@EventListener` is synchronous, `BookingEventListener` fires in the same thread and calls `bookingService.confirm`, which runs `commitSeat` (HELD → BOOKED) and `confirmBooking` (PENDING → CONFIRMED) — all within the same `@Transactional` boundary opened by `PaymentServiceImpl.confirm`.

  **Final snapshot.** The booking is CONFIRMED. The seat is BOOKED with `holdExpiresAt` explicitly null — the `commitSeat` UPDATE clears it (`SET hold_expires_at = NULL`), so this assertion proves the commit UPDATE ran rather than any other status change. The payment is SUCCESS.
- **Why this case:** The unit tests prove that each service interprets its inputs and outputs correctly, but they can never prove that all the pieces work together. Three things are only testable here: (1) the native SQL in `SeatRepository` — the conditional UPDATE `WHERE status='AVAILABLE' OR (status='HELD' AND hold_expires_at < now())` and the `FILTER (WHERE …)` in the availability count query run against real PostgreSQL semantics, not mocks; (2) the `@EventListener` wiring — `PaymentServiceImpl.confirm` publishes `PaymentConfirmedEvent` synchronously inside its `@Transactional` boundary, `BookingEventListener` picks it up in the same thread, and `bookingService.confirm` participates in the same transaction, making payment SUCCESS + seat BOOKED + booking CONFIRMED atomic; (3) the schema itself — the test runs against `V1__schema.sql` including the partial index `WHERE status='HELD'` and the `UNIQUE(booking_id)` constraints, confirming they are structurally correct and the seed data is consistent.

