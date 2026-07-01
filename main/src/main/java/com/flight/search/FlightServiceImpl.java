package com.flight.search;

import com.flight.search.dto.FlightSearchResponse;
import com.flight.search.dto.FlightSearchResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Implements both public read interfaces of the search package. Both back onto
 * the
 * flight table (and, for the availability count, the single allowed flight⨝seat
 * JOIN).
 */
@Service
@Transactional(readOnly = true)
public class FlightServiceImpl implements FlightSearchService, FlightQueryService {

    private final FlightRepository flightRepository;

    public FlightServiceImpl(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    @Override
    public FlightSearchResponse search(String source, String destination, LocalDate date) {
        // departure_time within the UTC day [date 00:00Z, date+1 00:00Z).
        OffsetDateTime from = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(1);

        List<FlightSearchResult> flights = flightRepository
                .searchWithAvailability(source, destination, from, to)
                .stream()
                .map(v -> new FlightSearchResult(
                        v.getScheduledFlightId(),
                        v.getFlightId(),
                        v.getSource(),
                        v.getDestination(),
                        v.getDepartureTime().atOffset(ZoneOffset.UTC),
                        v.getArrivalTime().atOffset(ZoneOffset.UTC),
                        v.getAvailableSeats(),
                        v.getBaseFare()))
                .toList();

        return new FlightSearchResponse(flights);
    }

    @Override
    public Optional<BigDecimal> findFare(String scheduledFlightId) {
        return flightRepository.findById(scheduledFlightId).map(Flight::getBaseFare);
    }
}
