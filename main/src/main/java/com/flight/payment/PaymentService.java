package com.flight.payment;

import java.math.BigDecimal;

/**
 * Public in-process interface of the {@code payment} package. The gateway is stubbed:
 * this creates the payment intent only. Confirmation (CREATED -> SUCCESS) is out of scope.
 */
public interface PaymentService {

    /**
     * Create a payment intent for a booking. Idempotent on {@code bookingId}: the
     * UNIQUE(booking_id) constraint guarantees at most one intent per booking, so a retry
     * returns the existing intent rather than creating a second one (no double charge).
     */
    PaymentIntent createIntent(String bookingId, BigDecimal amount);

    /** Minimal view of a payment intent returned to the orchestrator. */
    record PaymentIntent(String paymentId, String status) {
    }
}
