package com.flight.search;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Internal, in-process interface exposed by the {@code search} package (owner of the
 * flight table) to other packages. The {@code booking} orchestrator uses it to resolve
 * a flight's fare and confirm the flight exists — instead of reading the flight table
 * directly, which would violate table ownership.
 */
public interface FlightQueryService {

    /** Base fare for a scheduled flight, or empty if the flight does not exist. */
    Optional<BigDecimal> findFare(String scheduledFlightId);
}
