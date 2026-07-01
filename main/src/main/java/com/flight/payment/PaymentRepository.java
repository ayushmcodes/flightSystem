package com.flight.payment;

import com.flight.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findByBookingId(String bookingId);

    /** CREATED → SUCCESS. State-guarded so repeated calls are no-ops. rowcount 1 = transitioned. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE payment SET status = 'SUCCESS', updated_at = now() " +
                   "WHERE payment_id = :pid AND status = 'CREATED'", nativeQuery = true)
    int confirmPayment(@Param("pid") String paymentId);
}
