package com.flight.inventory.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A flight plus its available-seat snapshot, returned by {@link CatalogQueryService}.
 * A neutral record so the JPA projection never leaks across the package boundary.
 */
public record FlightAvailability(
        String scheduledFlightId,
        String flightNumber,
        String source,
        String destination,
        OffsetDateTime departureTime,
        OffsetDateTime arrivalTime,
        long availableSeats,
        BigDecimal baseFare) {
}
