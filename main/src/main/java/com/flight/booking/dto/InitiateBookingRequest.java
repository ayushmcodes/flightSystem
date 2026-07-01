package com.flight.booking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record InitiateBookingRequest(
        @NotBlank String userId,
        @NotBlank String scheduledFlightId,
        @NotBlank String seatNo,
        @NotEmpty @Valid List<Passenger> passengers) {

    public record Passenger(@NotBlank String name) {
    }
}
