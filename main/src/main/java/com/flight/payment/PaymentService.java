package com.flight.payment;

import com.flight.payment.dto.PaymentIntent;

import java.math.BigDecimal;

/**
 * Public in-process interface of the {@code payment} package.
 */
public interface PaymentService {

    /**
     * Create a payment intent for a booking. Idempotent on {@code bookingId}: the
     * UNIQUE(booking_id) constraint guarantees at most one intent per booking, so a retry
     * returns the existing intent rather than creating a second one (no double charge).
     */
    PaymentIntent createIntent(String bookingId, BigDecimal amount);

    /**
     * Confirm a payment via a gateway webhook signal. Idempotent on {@code eventId} via the
     * {@code processed_webhook} dedupe table. On success, publishes a
     * {@link com.flight.payment.event.PaymentConfirmedEvent} in-process so the booking
     * package can drive seat commit and booking confirmation atomically.
     */
    void confirm(String paymentId, String eventId);
}
