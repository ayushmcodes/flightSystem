package com.flight.payment;

import com.flight.payment.dto.PaymentIntent;

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
}
