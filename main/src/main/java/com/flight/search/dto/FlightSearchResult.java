package com.flight.search.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One flight in the search response, with an availability snapshot. */
public record FlightSearchResult(
        String scheduledFlightId,
        String flightNumber,
        String source,
        String destination,
        OffsetDateTime departureTime,
        OffsetDateTime arrivalTime,
        long availableSeats,
        BigDecimal baseFare) {
}
