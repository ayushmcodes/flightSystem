package com.flight.inventory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background sweeper that flips stale {@code HELD} seats back to {@code AVAILABLE} for
 * clean reporting. Not required for correctness — hold acquisition already reclaims an
 * expired hold lazily (see {@link SeatRepository#holdSeat}). Purely housekeeping.
 */
@Component
public class HoldSweeper {

    private final SeatRepository seatRepository;

    public HoldSweeper(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Scheduled(fixedDelayString = "${flight.hold.sweep-interval-ms:60000}")
    @Transactional
    public void sweep() {
        seatRepository.expireStaleHolds();
    }
}
