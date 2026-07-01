package com.flight.booking.dto;

import java.math.BigDecimal;

/** Response for POST /bookings/initiate. */
public record BookingResponse(
        String bookingId,
        String status,
        String seatNo,
        String paymentId,
        BigDecimal amount) {
}
