package com.flight.booking;

import com.flight.payment.event.PaymentConfirmedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link PaymentConfirmedEvent} published by the {@code payment} package and
 * drives the booking confirmation saga. Runs synchronously in the publisher's transaction
 * so payment UPDATE + seat commit + booking confirm are atomic.
 * <p>
 * This is the in-process seam where a Kafka consumer subscription would sit if the
 * packages were split into services.
 */
@Component
public class BookingEventListener {

    private final BookingService bookingService;

    public BookingEventListener(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @EventListener
    public void onPaymentConfirmed(PaymentConfirmedEvent event) {
        bookingService.confirm(event.bookingId());
    }
}
