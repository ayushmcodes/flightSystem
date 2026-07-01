package com.flight.payment;

import com.flight.payment.dto.PaymentIntent;
import com.flight.payment.entity.Payment;
import com.flight.payment.event.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private ProcessedWebhookRepository processedWebhookRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentServiceImpl(paymentRepository, processedWebhookRepository, eventPublisher);
    }

    // =========================================================================
    // createPayment
    // =========================================================================

    @Test
    void createPayment_firstCall_insertsAndReturnsIntent() {
        Payment saved = payment("PAY-1", "BK-1", "CREATED");
        when(paymentRepository.saveAndFlush(any())).thenReturn(saved);

        PaymentIntent intent = paymentService.createPayment("BK-1", new BigDecimal("5000.00"));

        assertThat(intent.paymentId()).isEqualTo("PAY-1");
        assertThat(intent.status()).isEqualTo("CREATED");
    }

    @Test
    void createPayment_duplicateBookingId_returnsExistingIntent() {
        when(paymentRepository.saveAndFlush(any())).thenThrow(DataIntegrityViolationException.class);
        when(paymentRepository.findByBookingId("BK-1")).thenReturn(Optional.of(payment("PAY-existing", "BK-1", "CREATED")));

        PaymentIntent intent = paymentService.createPayment("BK-1", new BigDecimal("5000.00"));

        assertThat(intent.paymentId()).isEqualTo("PAY-existing");
        verify(paymentRepository, times(1)).saveAndFlush(any());
    }

    @Test
    void createPayment_duplicateBookingId_lookupAlsoMissing_rethrows() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("duplicate key");
        when(paymentRepository.saveAndFlush(any())).thenThrow(original);
        when(paymentRepository.findByBookingId("BK-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.createPayment("BK-1", new BigDecimal("5000.00")))
                .isSameAs(original);
    }

    // =========================================================================
    // confirm
    // =========================================================================

    @Test
    void confirm_duplicateEventId_doesNotUpdatePaymentOrPublishEvent() {
        when(processedWebhookRepository.insert("evt-001")).thenThrow(DataIntegrityViolationException.class);

        paymentService.confirm("PAY-1", "evt-001");

        verify(paymentRepository, never()).confirmPayment(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void confirm_paymentUpdateNoOp_noEventPublished() {
        when(paymentRepository.confirmPayment("PAY-1")).thenReturn(0);

        paymentService.confirm("PAY-1", "evt-001");

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void confirm_happyPath_publishesPaymentConfirmedEvent() {
        when(paymentRepository.confirmPayment("PAY-1")).thenReturn(1);
        when(paymentRepository.findById("PAY-1")).thenReturn(Optional.of(payment("PAY-1", "BK-1", "SUCCESS")));

        paymentService.confirm("PAY-1", "evt-001");

        verify(eventPublisher).publishEvent(new PaymentConfirmedEvent("BK-1"));
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private static Payment payment(String paymentId, String bookingId, String status) {
        Payment p = new Payment();
        p.setPaymentId(paymentId);
        p.setBookingId(bookingId);
        p.setStatus(status);
        p.setAmount(new BigDecimal("5000.00"));
        p.setIdempotencyKey("pay-" + bookingId);
        return p;
    }
}
