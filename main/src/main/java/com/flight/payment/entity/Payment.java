package com.flight.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Payment intent. Owned by the {@code payment} package. At most one per booking. */
@Entity
@Table(name = "payment")
@Getter
@Setter
public class Payment {

    @Id
    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "booking_id")
    private String bookingId;

    private BigDecimal amount;

    private String status;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
