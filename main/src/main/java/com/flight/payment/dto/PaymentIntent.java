package com.flight.payment.dto;

/** Minimal view of a payment intent returned to the orchestrator. */
public record PaymentIntent(String paymentId, String status) {
}
