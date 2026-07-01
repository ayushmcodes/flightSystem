package com.flight.inventory;

import com.flight.inventory.entity.Flight;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

public interface FlightRepository extends JpaRepository<Flight, String> {

    /**
     * Availability search. Both {@code flight} and {@code seat} are owned by this package,
     * so the JOIN is internal — no cross-module table access.
     * <p>
     * available_seats counts seats that are AVAILABLE plus HELD seats whose hold has
     * lapsed (hold_expires_at &lt; now()) — the lazy-expiry snapshot. It is a snapshot,
     * not a guarantee.
     */
    @Query(value = """
            SELECT f.scheduled_flight_id AS scheduledFlightId,
                   f.flight_id           AS flightId,
                   f.source              AS source,
                   f.destination         AS destination,
                   f.departure_time      AS departureTime,
                   f.arrival_time        AS arrivalTime,
                   f.base_fare           AS baseFare,
                   COUNT(s.seat_id) FILTER (
                       WHERE s.status = 'AVAILABLE'
                          OR (s.status = 'HELD' AND s.hold_expires_at < now())
                   ) AS availableSeats
            FROM flight f
            LEFT JOIN seat s ON s.scheduled_flight_id = f.scheduled_flight_id
            WHERE f.source = :source
              AND f.destination = :destination
              AND f.departure_time >= :from
              AND f.departure_time < :to
            GROUP BY f.scheduled_flight_id, f.flight_id, f.source, f.destination,
                     f.departure_time, f.arrival_time, f.base_fare
            ORDER BY f.departure_time
            """, nativeQuery = true)
    List<FlightAvailabilityView> searchWithAvailability(@Param("source") String source,
                                                        @Param("destination") String destination,
                                                        @Param("from") OffsetDateTime from,
                                                        @Param("to") OffsetDateTime to);

    /**
     * Projection for {@link #searchWithAvailability}. Timestamps are typed as {@link Instant}
     * because the JDBC driver reads {@code timestamptz} as an Instant for native projections;
     * the service converts to a UTC {@link OffsetDateTime} for the response.
     */
    interface FlightAvailabilityView {
        String getScheduledFlightId();
        String getFlightId();
        String getSource();
        String getDestination();
        Instant getDepartureTime();
        Instant getArrivalTime();
        java.math.BigDecimal getBaseFare();
        long getAvailableSeats();
    }
}
