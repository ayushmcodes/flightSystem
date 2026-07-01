package com.flight.inventory;

import com.flight.inventory.dto.FlightAvailability;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Public in-process read interface of the {@code inventory} package for the flight catalog.
 * Kept separate from {@link InventoryService} (seat writes) — reads vs. the no-oversell
 * command side. Consumed by {@code search} (flight search) and {@code booking} (fare lookup),
 * so neither of them touches the flight/seat tables directly.
 */
public interface CatalogQueryService {

    /** Flights on the route departing on {@code date}, each with an available-seats snapshot. */
    List<FlightAvailability> searchFlights(String source, String destination, LocalDate date);

    /** Base fare for a scheduled flight, or empty if the flight does not exist. */
    Optional<BigDecimal> findFare(String scheduledFlightId);
}
