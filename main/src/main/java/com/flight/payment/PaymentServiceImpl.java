package com.flight.payment;

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

    public PaymentServiceImpl(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
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

    private PaymentIntent toIntent(Payment payment) {
        return new PaymentIntent(payment.getPaymentId(), payment.getStatus());
    }
}
