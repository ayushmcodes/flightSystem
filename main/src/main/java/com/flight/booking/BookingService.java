package com.flight.booking;

import com.flight.booking.dto.BookingResponse;
import com.flight.booking.dto.InitiateBookingRequest;

public interface BookingService {

    /**
     * Initiate a booking: insert PENDING, hold the seat, create the payment intent, link
     * it, and return PENDING. Idempotent on {@code idempotencyKey} — a replay returns the
     * original booking's response without holding a new seat or creating a new payment.
     */
    BookingResponse initiate(String idempotencyKey, InitiateBookingRequest request);

    /**
     * Confirm a booking after payment success. Commits the held seat (HELD → BOOKED) then
     * marks the booking CONFIRMED (PENDING → CONFIRMED). Ordering is preserved: seat commit
     * always precedes booking confirm. Idempotent — if the booking is already beyond PENDING
     * the call is a no-op.
     * <p>
     * On seat-commit failure (hold expired / seat taken), marks booking FAILED and stubs a
     * refund path.
     */
    void confirm(String bookingId);
}
