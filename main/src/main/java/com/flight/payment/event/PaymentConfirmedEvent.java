package com.flight.payment.event;

/**
 * Published (in-process) by the {@code payment} package when a payment transitions to
 * SUCCESS. The {@code booking} package listens and drives commit + confirm. This is the
 * seam where a broker (e.g. Kafka) would sit if the packages were split into services.
 */
public record PaymentConfirmedEvent(String bookingId) {
}
