package com.flight.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Dedupe guard for gateway webhook redelivery. Owned by the {@code payment} package.
 * Rows are only ever inserted (via a native INSERT); the PK is the dedupe mechanism.
 */
@Entity
@Table(name = "processed_webhook")
@Getter
@Setter
public class ProcessedWebhook {

    @Id
    @Column(name = "event_id")
    private String eventId;
}
