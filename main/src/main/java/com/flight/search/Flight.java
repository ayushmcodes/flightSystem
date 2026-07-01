package com.flight.search;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Flight — a single dated flight. Owned by the {@code search} package.
 * Reference data: rows are provisioned once (see V2 seed) and never mutated by the booking flow.
 */
@Entity
@Table(name = "flight")
@Getter
@Setter
public class Flight {

    @Id
    @Column(name = "scheduled_flight_id")
    private String scheduledFlightId;

    @Column(name = "flight_id")
    private String flightId;

    private String source;

    private String destination;

    @Column(name = "departure_time")
    private OffsetDateTime departureTime;

    @Column(name = "arrival_time")
    private OffsetDateTime arrivalTime;

    @Column(name = "base_fare")
    private BigDecimal baseFare;
}
