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
}
