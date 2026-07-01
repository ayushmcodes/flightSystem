-- Flight Booking System — seed data (runs once on first Postgres boot).
-- ~5 dated flights across BLR->DEL, BLR->BOM, DEL->BLR on near-future dates
-- (relative to 2026-07), each with 20 AVAILABLE seats, mixed cabin class.

INSERT INTO flight (scheduled_flight_id, flight_id, source, destination, departure_time, arrival_time, base_fare) VALUES
    ('SF1', '6E-203', 'BLR', 'DEL', '2026-07-15T08:00:00Z', '2026-07-15T10:50:00Z', 5000.00),
    ('SF2', '6E-411', 'BLR', 'DEL', '2026-07-16T18:00:00Z', '2026-07-16T20:45:00Z', 5500.00),
    ('SF3', 'AI-505', 'BLR', 'BOM', '2026-07-15T09:30:00Z', '2026-07-15T11:00:00Z', 4200.00),
    ('SF4', 'UK-810', 'DEL', 'BLR', '2026-07-20T07:00:00Z', '2026-07-20T09:50:00Z', 6000.00),
    ('SF5', '6E-777', 'BLR', 'DEL', '2026-07-20T14:00:00Z', '2026-07-20T16:50:00Z', 4800.00);

-- 20 seats per flight: rows 1-5 x columns A-D. Rows 1-2 BUSINESS, rows 3-5 ECONOMY.
INSERT INTO seat (seat_id, scheduled_flight_id, seat_no, cabin_class, status, booking_id, hold_expires_at)
SELECT f.scheduled_flight_id || '-' || r.row_no || c.col,
       f.scheduled_flight_id,
       r.row_no || c.col,
       CASE WHEN r.row_no <= 2 THEN 'BUSINESS' ELSE 'ECONOMY' END,
       'AVAILABLE',
       NULL,
       NULL
FROM flight f
CROSS JOIN generate_series(1, 5) AS r(row_no)
CROSS JOIN (VALUES ('A'), ('B'), ('C'), ('D')) AS c(col);
