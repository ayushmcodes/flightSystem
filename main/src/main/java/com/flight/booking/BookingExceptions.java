package com.flight.booking;

/** Domain exceptions raised by the booking flow, each mapped to an HTTP status by the advice. */
public final class BookingExceptions {

    private BookingExceptions() {
    }

    /** 404 — flight or seat does not exist. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 409 — the seat was lost to another owner / is not available. */
    public static class SeatUnavailableException extends RuntimeException {
        public SeatUnavailableException(String message) {
            super(message);
        }
    }

    /** 422 — the idempotency key was reused with a different request body. */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }
}
