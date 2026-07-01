package com.flight.inventory;

import com.flight.inventory.dto.FlightAvailability;
import com.flight.inventory.entity.Flight;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class CatalogQueryServiceImpl implements CatalogQueryService {

    private final FlightRepository flightRepository;

    public CatalogQueryServiceImpl(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    @Override
    public List<FlightAvailability> searchFlights(String source, String destination, LocalDate date) {
        // departure_time within the UTC day [date 00:00Z, date+1 00:00Z).
        OffsetDateTime from = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = from.plusDays(1);

        return flightRepository.searchWithAvailability(source, destination, from, to)
                .stream()
                .map(v -> new FlightAvailability(
                        v.getScheduledFlightId(),
                        v.getFlightId(),
                        v.getSource(),
                        v.getDestination(),
                        v.getDepartureTime().atOffset(ZoneOffset.UTC),
                        v.getArrivalTime().atOffset(ZoneOffset.UTC),
                        v.getAvailableSeats(),
                        v.getBaseFare()))
                .toList();
    }

    @Override
    public Optional<BigDecimal> findFare(String scheduledFlightId) {
        return flightRepository.findById(scheduledFlightId).map(Flight::getBaseFare);
    }
}
