package com.flight.booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Booking — the saga record. Owned by the {@code booking} package. */
@Entity
@Table(name = "booking")
@Getter
@Setter
public class Booking {

    @Id
    @Column(name = "booking_id")
    private String bookingId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "scheduled_flight_id")
    private String scheduledFlightId;

    @Column(name = "seat_id")
    private String seatId;

    @Column(name = "payment_id")
    private String paymentId;

    private String status;

    private BigDecimal amount;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
