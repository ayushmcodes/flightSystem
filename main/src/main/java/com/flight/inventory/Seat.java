package com.flight.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Seat — the source of truth for availability. Owned by the {@code inventory} package.
 * <p>
 * State transitions (hold/commit/release) are NEVER performed by loading this entity and
 * saving it back — that is a check-then-set race. They are single atomic conditional
 * UPDATEs in {@link SeatRepository}. This entity is used only for reads.
 */
@Entity
@Table(name = "seat")
@Getter
@Setter
public class Seat {

    @Id
    @Column(name = "seat_id")
    private String seatId;

    @Column(name = "scheduled_flight_id")
    private String scheduledFlightId;

    @Column(name = "seat_no")
    private String seatNo;

    @Column(name = "cabin_class")
    private String cabinClass;

    private String status;

    @Column(name = "booking_id")
    private String bookingId;

    @Column(name = "hold_expires_at")
    private OffsetDateTime holdExpiresAt;
}
