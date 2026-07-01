package com.flight.booking;

import com.flight.booking.dto.BookingResponse;
import com.flight.booking.dto.InitiateBookingRequest;
import com.flight.booking.entity.Booking;
import com.flight.inventory.CatalogQueryService;
import com.flight.inventory.InventoryService;
import com.flight.inventory.dto.HoldResult;
import com.flight.payment.PaymentService;
import com.flight.payment.dto.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    @Mock private CatalogQueryService catalogQueryService;
    @Mock private InventoryService inventoryService;
    @Mock private PaymentService paymentService;
    @Mock private BookingRepository bookingRepository;

    private BookingServiceImpl bookingService;

    private static final BigDecimal FARE = new BigDecimal("5000.00");
    private static final String FLIGHT_ID = "SF1";
    private static final String SEAT_NO = "3A";
    private static final String SEAT_ID = "SEAT-SF1-3A";
    private static final String IDEM_KEY = "idem-key-1";

    @BeforeEach
    void setUp() {
        bookingService = new BookingServiceImpl(
                catalogQueryService, inventoryService, paymentService,
                bookingRepository, Duration.ofMinutes(10));
    }

    // =========================================================================
    // initiate
    // =========================================================================

    @Test
    void initiate_happyPath_createBookingInPendingState() {
        when(catalogQueryService.findFare(FLIGHT_ID)).thenReturn(Optional.of(FARE));
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryService.holdSeat(eq(FLIGHT_ID), eq(SEAT_NO), any(), any()))
                .thenReturn(HoldResult.held(SEAT_ID, SEAT_NO));
        when(paymentService.createIntent(any(), eq(FARE))).thenReturn(new PaymentIntent("PAY-1", "CREATED"));

        BookingResponse response = bookingService.initiate(IDEM_KEY, request("U1"));

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.seatNo()).isEqualTo(SEAT_NO);
        assertThat(response.paymentId()).isEqualTo("PAY-1");
        assertThat(response.amount()).isEqualByComparingTo(FARE);
        verify(bookingRepository).linkSeat(any(), eq(SEAT_ID));
        verify(bookingRepository).linkPayment(any(), eq("PAY-1"));
    }

    @Test
    void initiate_duplicateKey_sameBody_returnsExistingBooking() {
        when(catalogQueryService.findFare(FLIGHT_ID)).thenReturn(Optional.of(FARE));
        when(bookingRepository.saveAndFlush(any())).thenThrow(DataIntegrityViolationException.class);

        Booking existing = pendingBooking("BK-existing", "U1", SEAT_ID, "PAY-1");
        when(bookingRepository.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.of(existing));
        when(inventoryService.getSeatNo(SEAT_ID)).thenReturn(Optional.of(SEAT_NO));

        BookingResponse response = bookingService.initiate(IDEM_KEY, request("U1"));

        assertThat(response.bookingId()).isEqualTo("BK-existing");
        assertThat(response.status()).isEqualTo("PENDING");
        verify(inventoryService, never()).holdSeat(any(), any(), any(), any());
    }

    @Test
    void initiate_duplicateKey_differentBody_throws422() {
        when(catalogQueryService.findFare(FLIGHT_ID)).thenReturn(Optional.of(FARE));
        when(bookingRepository.saveAndFlush(any())).thenThrow(DataIntegrityViolationException.class);

        Booking existing = pendingBooking("BK-existing", "DIFFERENT_USER", SEAT_ID, "PAY-1");
        when(bookingRepository.findByIdempotencyKey(IDEM_KEY)).thenReturn(Optional.of(existing));
        when(inventoryService.getSeatNo(SEAT_ID)).thenReturn(Optional.of(SEAT_NO));

        assertThatThrownBy(() -> bookingService.initiate(IDEM_KEY, request("U1")))
                .isInstanceOf(BookingExceptions.IdempotencyConflictException.class);
    }

    @Test
    void initiate_seatConflict_marksFailedAndThrows409() {
        when(catalogQueryService.findFare(FLIGHT_ID)).thenReturn(Optional.of(FARE));
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryService.holdSeat(eq(FLIGHT_ID), eq(SEAT_NO), any(), any()))
                .thenReturn(HoldResult.conflict());

        assertThatThrownBy(() -> bookingService.initiate(IDEM_KEY, request("U1")))
                .isInstanceOf(BookingExceptions.SeatUnavailableException.class);
        verify(bookingRepository).markFailed(any());
    }

    @Test
    void initiate_seatNotFound_marksFailedAndThrows404() {
        when(catalogQueryService.findFare(FLIGHT_ID)).thenReturn(Optional.of(FARE));
        when(bookingRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryService.holdSeat(eq(FLIGHT_ID), eq(SEAT_NO), any(), any()))
                .thenReturn(HoldResult.notFound());

        assertThatThrownBy(() -> bookingService.initiate(IDEM_KEY, request("U1")))
                .isInstanceOf(BookingExceptions.NotFoundException.class);
        verify(bookingRepository).markFailed(any());
    }

    @Test
    void initiate_flightNotFound_throws404() {
        when(catalogQueryService.findFare(FLIGHT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.initiate(IDEM_KEY, request("U1")))
                .isInstanceOf(BookingExceptions.NotFoundException.class);
        verifyNoInteractions(bookingRepository);
    }

    // =========================================================================
    // confirm
    // =========================================================================

    @Test
    void confirm_bookingNotFound_doesNotAttemptSeatCommit() {
        when(bookingRepository.findById("BK-1")).thenReturn(Optional.empty());

        bookingService.confirm("BK-1");

        verifyNoInteractions(inventoryService);
    }

    @Test
    void confirm_bookingAlreadyConfirmed_doesNotAttemptSeatCommit() {
        Booking booking = new Booking();
        booking.setStatus("CONFIRMED");
        when(bookingRepository.findById("BK-1")).thenReturn(Optional.of(booking));

        bookingService.confirm("BK-1");

        verifyNoInteractions(inventoryService);
    }

    @Test
    void confirm_seatCommitFails_marksBookingFailed() {
        Booking booking = pendingBooking("BK-1", "U1", SEAT_ID, null);
        when(bookingRepository.findById("BK-1")).thenReturn(Optional.of(booking));
        when(inventoryService.commitSeat(SEAT_ID, "BK-1")).thenReturn(false);

        bookingService.confirm("BK-1");

        verify(bookingRepository).markFailed("BK-1");
        verify(bookingRepository, never()).confirmBooking(any());
    }

    @Test
    void confirm_happyPath_orderingEnforced() {
        // Seat commit MUST happen before booking confirm — the InOrder assertion is the proof.
        Booking booking = pendingBooking("BK-1", "U1", SEAT_ID, null);
        when(bookingRepository.findById("BK-1")).thenReturn(Optional.of(booking));
        when(inventoryService.commitSeat(SEAT_ID, "BK-1")).thenReturn(true);

        bookingService.confirm("BK-1");

        InOrder inOrder = inOrder(inventoryService, bookingRepository);
        inOrder.verify(inventoryService).commitSeat(SEAT_ID, "BK-1");
        inOrder.verify(bookingRepository).confirmBooking("BK-1");
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private static InitiateBookingRequest request(String userId) {
        return new InitiateBookingRequest(userId, FLIGHT_ID, SEAT_NO);
    }

    private static Booking pendingBooking(String bookingId, String userId, String seatId, String paymentId) {
        Booking b = new Booking();
        b.setBookingId(bookingId);
        b.setUserId(userId);
        b.setScheduledFlightId(FLIGHT_ID);
        b.setSeatId(seatId);
        b.setPaymentId(paymentId);
        b.setStatus("PENDING");
        b.setAmount(FARE);
        b.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        b.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return b;
    }
}
