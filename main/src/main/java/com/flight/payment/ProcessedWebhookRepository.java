package com.flight.payment;

import com.flight.payment.entity.ProcessedWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedWebhookRepository extends JpaRepository<ProcessedWebhook, String> {

    /**
     * Record an event id as processed. A single atomic INSERT — a duplicate PK raises
     * {@link org.springframework.dao.DataIntegrityViolationException}, which the caller
     * treats as "already processed".
     * <p>
     * Native INSERT (not {@code saveAndFlush}) on purpose: the dedupe key IS the PK, so
     * Hibernate's assigned-id {@code save()} would SELECT-then-merge and silently no-op on a
     * duplicate instead of failing. The native INSERT forces the write with no pre-SELECT.
     */
    @Modifying
    @Query(value = "INSERT INTO processed_webhook (event_id) VALUES (:eventId)", nativeQuery = true)
    int insert(@Param("eventId") String eventId);
}
