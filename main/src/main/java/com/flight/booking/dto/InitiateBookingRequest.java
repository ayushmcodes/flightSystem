package com.flight.booking.dto;

import jakarta.validation.constraints.NotBlank;

public record InitiateBookingRequest(
        @NotBlank String userId,
        @NotBlank String scheduledFlightId,
        @NotBlank String seatNo) {
}
