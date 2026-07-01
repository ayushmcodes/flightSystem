package com.flight.inventory;

import com.flight.inventory.dto.HoldResult;

import java.time.Duration;
import java.util.Optional;

/**
 * Public in-process interface of the {@code inventory} package — the owner of seat state
 * and the no-oversell guarantee. The {@code booking} orchestrator calls these methods
 * directly (not over HTTP); it never touches the seat table.
 */
public interface InventoryService {

    /**
     * Attempt to hold a seat for a booking via the atomic conditional UPDATE. Idempotent
     * for the same owner (a retry by the same {@code bookingId} succeeds again).
     */
    HoldResult holdSeat(String scheduledFlightId, String seatNo, String bookingId, Duration ttl);

    /** Commit the seat held by this booking to BOOKED. Returns true iff a HELD seat was committed. */
    boolean commitSeat(String bookingId);

    /** Release the seat held by this booking back to AVAILABLE. Returns true iff a HELD seat was released. */
    boolean releaseSeat(String bookingId);

    /** Seat number for a seat id (used to reconstruct responses); empty if the seat is unknown. */
    Optional<String> getSeatNo(String seatId);
}
