package com.flight.inventory;

import com.flight.inventory.dto.HoldResult;
import com.flight.inventory.entity.Seat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class InventoryServiceImpl implements InventoryService {

    private final SeatRepository seatRepository;

    public InventoryServiceImpl(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Override
    @Transactional
    public HoldResult holdSeat(String scheduledFlightId, String seatNo, String bookingId, Duration ttl) {
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(ttl);
        int updated = seatRepository.holdSeat(scheduledFlightId, seatNo, bookingId, expiresAt);
        if (updated == 1) {
            // rowcount 1 -> we won the seat. Read it back to surface its id to the caller.
            Seat seat = seatRepository.findByScheduledFlightIdAndSeatNo(scheduledFlightId, seatNo).orElseThrow();
            return HoldResult.held(seat.getSeatId(), seat.getSeatNo());
        }
        // rowcount 0 -> either the seat does not exist (404) or it was lost to another owner (409).
        return seatRepository.findByScheduledFlightIdAndSeatNo(scheduledFlightId, seatNo)
                .map(s -> HoldResult.conflict())
                .orElseGet(HoldResult::notFound);
    }

    @Override
    @Transactional
    public boolean commitSeat(String bookingId) {
        return seatRepository.commitSeat(bookingId) == 1;
    }

    @Override
    @Transactional
    public boolean releaseSeat(String bookingId) {
        return seatRepository.releaseSeat(bookingId) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getSeatNo(String seatId) {
        return seatRepository.findById(seatId).map(Seat::getSeatNo);
    }
}
