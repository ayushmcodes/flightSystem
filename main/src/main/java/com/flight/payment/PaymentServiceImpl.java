package com.flight.payment;

import com.flight.payment.dto.PaymentIntent;
import com.flight.payment.entity.Payment;
import com.flight.payment.event.PaymentConfirmedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final ProcessedWebhookRepository processedWebhookRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentServiceImpl(PaymentRepository paymentRepository,
                               ProcessedWebhookRepository processedWebhookRepository,
                               ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.processedWebhookRepository = processedWebhookRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public PaymentIntent createIntent(String bookingId, BigDecimal amount) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Payment payment = new Payment();
        payment.setPaymentId("PAY-" + UUID.randomUUID());
        payment.setBookingId(bookingId);
        payment.setAmount(amount);
        payment.setStatus("CREATED");
        // Deterministic from booking_id (never a fresh value per attempt) so retries converge.
        payment.setIdempotencyKey("pay-" + bookingId);
        payment.setCreatedAt(now);
        payment.setUpdatedAt(now);

        try {
            // Attempt the insert; UNIQUE(booking_id) is the at-most-one-intent guard.
            Payment saved = paymentRepository.saveAndFlush(payment);
            return toIntent(saved);
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent/retried attempt already created the intent — return it, don't double-charge.
            return paymentRepository.findByBookingId(bookingId)
                    .map(this::toIntent)
                    .orElseThrow(() -> duplicate);
        }
    }

    @Override
    @Transactional
    public void confirm(String paymentId, String eventId) {
        // 1. Webhook dedupe: attempt INSERT; duplicate PK means already processed → silent no-op.
        try {
            processedWebhookRepository.insert(eventId);
        } catch (DataIntegrityViolationException alreadyProcessed) {
            return;
        }

        // 2. Guarded update: CREATED → SUCCESS. rowcount 0 means already processed or bad state.
        int updated = paymentRepository.confirmPayment(paymentId);
        if (updated == 0) {
            return;
        }

        // 3. Publish in-process event; the booking package's listener drives seat commit + confirm.
        String bookingId = paymentRepository.findById(paymentId)
                .map(Payment::getBookingId)
                .orElseThrow(() -> new IllegalStateException("Payment vanished after confirm: " + paymentId));
        eventPublisher.publishEvent(new PaymentConfirmedEvent(bookingId));
    }

    private PaymentIntent toIntent(Payment payment) {
        return new PaymentIntent(payment.getPaymentId(), payment.getStatus());
    }
}
