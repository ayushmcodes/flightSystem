package com.flight.search;

import com.flight.inventory.CatalogQueryService;
import com.flight.inventory.dto.FlightAvailability;
import com.flight.search.dto.FlightSearchResponse;
import com.flight.search.dto.FlightSearchResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Read/API side of the flight catalog. Owns no tables — it delegates to the inventory
 * package (which owns flight + seat) via {@link CatalogQueryService} and maps the result
 * to the public API DTO.
 */
@Service
public class FlightSearchServiceImpl implements FlightSearchService {

    private final CatalogQueryService catalogQueryService;

    public FlightSearchServiceImpl(CatalogQueryService catalogQueryService) {
        this.catalogQueryService = catalogQueryService;
    }

    @Override
    public FlightSearchResponse search(String source, String destination, LocalDate date) {
        List<FlightSearchResult> flights = catalogQueryService.searchFlights(source, destination, date)
                .stream()
                .map(FlightSearchServiceImpl::toResult)
                .toList();
        return new FlightSearchResponse(flights);
    }

    private static FlightSearchResult toResult(FlightAvailability f) {
        return new FlightSearchResult(
                f.scheduledFlightId(),
                f.flightNumber(),
                f.source(),
                f.destination(),
                f.departureTime(),
                f.arrivalTime(),
                f.availableSeats(),
                f.baseFare());
    }
}
