package com.flight.booking;

import com.flight.booking.dto.BookingResponse;
import com.flight.booking.dto.InitiateBookingRequest;
import com.flight.booking.entity.Booking;
import com.flight.inventory.CatalogQueryService;
import com.flight.inventory.dto.HoldResult;
import com.flight.inventory.InventoryService;
import com.flight.payment.PaymentService;
import com.flight.payment.dto.PaymentIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The booking orchestrator (the saga). It brackets the whole flow — first and last
 * writer — and drives the other packages purely through their in-process interfaces
 * ({@link CatalogQueryService}, {@link InventoryService}, {@link PaymentService}); it never
 * touches their tables.
 * <p>
 * Deliberately NOT wrapped in a single {@code @Transactional}: each step is its own
 * transaction (mirroring the saga-of-services model in DESIGN.md §1). This also keeps the
 * idempotency-key collision clean — the failed INSERT rolls back its own transaction and
 * the replay lookup runs fresh, with no rollback-only transaction to poison.
 */
@Service
public class BookingServiceImpl implements BookingService {

    private final CatalogQueryService catalogQueryService;
    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final BookingRepository bookingRepository;
    private final Duration holdTtl;

    public BookingServiceImpl(CatalogQueryService catalogQueryService,
                              InventoryService inventoryService,
                              PaymentService paymentService,
                              BookingRepository bookingRepository,
                              @Value("${flight.hold.ttl:PT10M}") Duration holdTtl) {
        this.catalogQueryService = catalogQueryService;
        this.inventoryService = inventoryService;
        this.paymentService = paymentService;
        this.bookingRepository = bookingRepository;
        this.holdTtl = holdTtl;
    }

    @Override
    public BookingResponse initiate(String idempotencyKey, InitiateBookingRequest request) {
        // Amount comes from the flight's base fare, resolved via the search package's
        // interface (booking must not read the flight table). Missing flight -> 404.
        BigDecimal amount = catalogQueryService.findFare(request.scheduledFlightId())
                .orElseThrow(() -> new BookingExceptions.NotFoundException(
                        "Flight not found: " + request.scheduledFlightId()));

        // Step 1: INSERT booking(PENDING). UNIQUE(idempotency_key) lets exactly one win;
        // a collision means this is a replay, so return the original response unchanged.
        Booking booking = createBooking(idempotencyKey, request, amount);
        if (booking == null) {
            return resolveIdempotentRetry(idempotencyKey, request);
        }

        // Step 2: hold the seat via a single atomic conditional UPDATE.
        HoldResult hold = inventoryService.holdSeat(
                request.scheduledFlightId(), request.seatNo(), booking.getBookingId(), holdTtl);
        switch (hold.outcome()) {
            case NOT_FOUND -> {
                bookingRepository.markFailed(booking.getBookingId());
                throw new BookingExceptions.NotFoundException(
                        "Seat not found: " + request.seatNo() + " on flight " + request.scheduledFlightId());
            }
            case CONFLICT -> {
                bookingRepository.markFailed(booking.getBookingId());
                throw new BookingExceptions.SeatUnavailableException(
                        "Seat unavailable: " + request.seatNo());
            }
            case HELD -> {
                bookingRepository.linkSeat(booking.getBookingId(), hold.seatId());// comment:why can't we use JPA here
            }
        }

        // Step 3: create the payment intent (idempotent on bookingId), then link it.
        PaymentIntent intent = paymentService.createPayment(booking.getBookingId(), amount);
        bookingRepository.linkPaymentToBooking(booking.getBookingId(), intent.paymentId());//comment: what if payment is complete in case of retry attempt

        // Step 4: return PENDING. Payment success -> commit -> CONFIRMED is out of scope.
        return new BookingResponse(booking.getBookingId(), "PENDING", hold.seatNo(), intent.paymentId(), amount);
    }

    @Override
    @Transactional
    public void confirm(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || !"PENDING".equals(booking.getStatus())) {
            return; // already confirmed / failed / unknown — idempotent no-op
        }

        // Seat commit MUST precede booking confirm (design.md §7 ordering rule).
        boolean confirmed = inventoryService.confirmSeat(booking.getSeatId(), bookingId);
        if (!confirmed) {
            // Hold expired or seat taken — money taken but seat is gone.
            bookingRepository.markFailed(bookingId);
            // TODO: trigger refund — money taken but seat unavailable (re-accommodate or refund)
            return;
        }

        bookingRepository.confirmBooking(bookingId);
    }

    /** Insert a PENDING booking; returns null if the idempotency key already exists (a replay). */
    private Booking createBooking(String idempotencyKey, InitiateBookingRequest request, BigDecimal amount) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Booking booking = new Booking();
        booking.setBookingId("BK-" + UUID.randomUUID());
        booking.setUserId(request.userId());
        booking.setScheduledFlightId(request.scheduledFlightId());
        booking.setStatus("PENDING");
        booking.setAmount(amount);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setCreatedAt(now);
        booking.setUpdatedAt(now);
        try {
            return bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException duplicateKey) {
            return null;
        }
    }

    /**
     * Replay path for a duplicate idempotency key. Binds the key to the request: same key
     * with a different body -> 422; otherwise return the original booking's response.
     * <p>
     * Binding compares the persisted identifying fields (userId, scheduledFlightId, seatNo);
     * the fixed 4-column booking schema has no request-hash column, so binding is by these
     * fields only — a documented tradeoff.
     */
    private BookingResponse resolveIdempotentRetry(String idempotencyKey, InitiateBookingRequest request) {
        Booking existing = bookingRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency key vanished after collision: " + idempotencyKey));

        String existingSeatNo = existing.getSeatId() == null
                ? null
                : inventoryService.getSeatNo(existing.getSeatId()).orElse(null);

        boolean sameBody = existing.getUserId().equals(request.userId())
                && existing.getScheduledFlightId().equals(request.scheduledFlightId())
                && java.util.Objects.equals(existingSeatNo, request.seatNo());
        if (!sameBody) {
            throw new BookingExceptions.IdempotencyConflictException(
                    "Idempotency-Key reused with a different request body");
        }

        return new BookingResponse(existing.getBookingId(), existing.getStatus(),
                existingSeatNo, existing.getPaymentId(), existing.getAmount());
    }
}
