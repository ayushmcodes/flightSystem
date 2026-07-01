-- Flight Booking System — schema (DESIGN.md §2)
-- One database `flightdb`, four tables. Each table is owned by exactly one package:
--   flight -> search, seat -> inventory, booking -> booking, payment -> payment.

CREATE TABLE flight (
    scheduled_flight_id VARCHAR(64)  PRIMARY KEY,
    flight_id           VARCHAR(32)  NOT NULL,          -- airline flight code, e.g. 6E-203
    source              VARCHAR(8)   NOT NULL,
    destination         VARCHAR(8)   NOT NULL,
    departure_time      TIMESTAMPTZ  NOT NULL,
    arrival_time        TIMESTAMPTZ  NOT NULL,
    base_fare           NUMERIC(12,2) NOT NULL
);

-- Search query (API 1): equality on source/destination, range on departure_time.
CREATE INDEX idx_flight_route_departure ON flight (source, destination, departure_time);

CREATE TABLE seat (
    seat_id             VARCHAR(64)  PRIMARY KEY,
    scheduled_flight_id VARCHAR(64)  NOT NULL REFERENCES flight (scheduled_flight_id),
    seat_no             VARCHAR(8)   NOT NULL,
    cabin_class         VARCHAR(16)  NOT NULL,          -- ECONOMY / BUSINESS
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
    booking_id          VARCHAR(64),                    -- hold/booked owner; null when AVAILABLE
    hold_expires_at     TIMESTAMPTZ,                    -- TTL for the hold
    UNIQUE (scheduled_flight_id, seat_no)
);

-- Availability count (JOIN + filter) and "list seats for a flight".
CREATE INDEX idx_seat_flight_status ON seat (scheduled_flight_id, status);
-- Commit/release of every seat held by a booking, and reconciliation.
CREATE INDEX idx_seat_booking ON seat (booking_id);
-- Expiry sweeper: only HELD rows are ever scanned, so the partial index stays small.
CREATE INDEX idx_seat_hold_expiry ON seat (hold_expires_at) WHERE status = 'HELD';

CREATE TABLE booking (
    booking_id          VARCHAR(64)  PRIMARY KEY,
    user_id             VARCHAR(64)  NOT NULL,
    scheduled_flight_id VARCHAR(64)  NOT NULL,
    seat_id             VARCHAR(64),                    -- linked once the seat is held
    payment_id          VARCHAR(64),                    -- linked after the intent is created
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED', 'EXPIRED')),
    amount              NUMERIC(12,2) NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL UNIQUE,   -- dedup gate for duplicate initiate calls
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);

-- EXPIRED sweep (PENDING bookings older than the hold TTL) and reconciliation scans.
CREATE INDEX idx_booking_status_created ON booking (status, created_at);

CREATE TABLE payment (
    payment_id          VARCHAR(64)  PRIMARY KEY,
    booking_id          VARCHAR(64)  NOT NULL UNIQUE,   -- at-most-one intent per booking
    amount              NUMERIC(12,2) NOT NULL,
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('CREATED', 'SUCCESS', 'FAILED')),
    idempotency_key     VARCHAR(128) NOT NULL,          -- deterministic from booking_id
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);

-- Webhook-redelivery dedupe guard (payment package): a confirm webhook whose event_id is
-- already present is a no-op. The PK is the dedupe mechanism (attempt INSERT, catch the
-- violation) — see ProcessedWebhookRepository.
CREATE TABLE processed_webhook (
    event_id            VARCHAR(128) PRIMARY KEY
);
