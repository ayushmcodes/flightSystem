package com.flight.inventory;

import com.flight.inventory.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, String> {

    /**
     * Acquire a hold as a SINGLE atomic, owner-and-state-guarded conditional UPDATE —
     * no SELECT-then-update, no application lock.
     * <p>
     * The returned rowcount IS the no-oversell guarantee: the DB serializes concurrent
     * contenders on the seat row, so exactly one writer sees rowcount 1 (held) and the
     * rest see 0 (lost). The {@code booking_id = :bid} branch makes a retried hold by the
     * same owner an idempotent no-op success.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE seat
            SET status = 'HELD', booking_id = :bid, hold_expires_at = :exp
            WHERE scheduled_flight_id = :flightId AND seat_no = :seatNo
              AND ( status = 'AVAILABLE'
                    OR (status = 'HELD' AND hold_expires_at < now()))
            """, nativeQuery = true)
    int holdSeat(@Param("flightId") String flightId,
                 @Param("seatNo") String seatNo,
                 @Param("bid") String bookingId,
                 @Param("exp") OffsetDateTime holdExpiresAt);

    /** Commit a held seat to BOOKED — owner + state guarded. rowcount 1 = committed. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE seat
            SET status = 'BOOKED', hold_expires_at = NULL
            WHERE booking_id = :bid AND status = 'HELD'
            """, nativeQuery = true)
    int commitSeat(@Param("bid") String bookingId);

    /** Release a held seat back to AVAILABLE — owner + state guarded. rowcount 1 = released. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE seat
            SET status = 'AVAILABLE', booking_id = NULL, hold_expires_at = NULL
            WHERE booking_id = :bid AND status = 'HELD'
            """, nativeQuery = true)
    int releaseSeat(@Param("bid") String bookingId);

    /** Sweeper: flip stale HELD holds back to AVAILABLE (reporting only; lazy expiry already covers correctness). */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE seat
            SET status = 'AVAILABLE', booking_id = NULL, hold_expires_at = NULL
            WHERE status = 'HELD' AND hold_expires_at < now()
            """, nativeQuery = true)
    int expireStaleHolds();

    Optional<Seat> findByScheduledFlightIdAndSeatNo(String scheduledFlightId, String seatNo);
}
