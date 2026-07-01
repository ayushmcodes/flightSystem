package com.flight.inventory;

/**
 * Outcome of a hold attempt.
 *
 * @param outcome HELD (won the seat), CONFLICT (seat exists but was lost to another
 *                owner / not reclaimable), or NOT_FOUND (no such seat on the flight).
 * @param seatId  the held seat's id when {@code outcome == HELD}, otherwise null.
 * @param seatNo  the held seat's number when {@code outcome == HELD}, otherwise null.
 */
public record HoldResult(Outcome outcome, String seatId, String seatNo) {

    public enum Outcome { HELD, CONFLICT, NOT_FOUND }

    public boolean isHeld() {
        return outcome == Outcome.HELD;
    }

    static HoldResult held(String seatId, String seatNo) {
        return new HoldResult(Outcome.HELD, seatId, seatNo);
    }

    static HoldResult conflict() {
        return new HoldResult(Outcome.CONFLICT, null, null);
    }

    static HoldResult notFound() {
        return new HoldResult(Outcome.NOT_FOUND, null, null);
    }
}
