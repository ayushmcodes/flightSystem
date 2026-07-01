package com.flight.booking;

import com.flight.booking.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, String> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /** PENDING -> FAILED. State-guarded so it is a no-op once the booking has left PENDING. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Booking b SET b.status = 'FAILED', b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.bookingId = :bid AND b.status = 'PENDING'")
    int markFailed(@Param("bid") String bookingId);

    /** Link the held seat onto the booking (stays PENDING). */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Booking b SET b.seatId = :seatId, b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.bookingId = :bid AND b.status = 'PENDING'")
    int linkSeat(@Param("bid") String bookingId, @Param("seatId") String seatId);

    /** Link the payment intent onto the booking (stays PENDING). */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Booking b SET b.paymentId = :paymentId, b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.bookingId = :bid AND b.status = 'PENDING'")
    int linkPayment(@Param("bid") String bookingId, @Param("paymentId") String paymentId);
}
