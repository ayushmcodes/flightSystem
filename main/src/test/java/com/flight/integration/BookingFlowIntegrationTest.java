package com.flight.integration;

import com.flight.booking.BookingRepository;
import com.flight.booking.dto.BookingResponse;
import com.flight.booking.dto.InitiateBookingRequest;
import com.flight.booking.entity.Booking;
import com.flight.inventory.SeatRepository;
import com.flight.inventory.entity.Seat;
import com.flight.payment.PaymentRepository;
import com.flight.payment.entity.Payment;
import com.flight.search.dto.FlightSearchResponse;
import com.flight.search.dto.FlightSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test against the running Docker Compose stack.
 *
 * Prerequisites: docker compose up (app on :8080, postgres exposed on :5433).
 * The test Spring context connects to the same postgres for DB assertions only —
 * no embedded server is started.
 *
 * Re-runnable: @BeforeEach resets seat 3A to AVAILABLE before each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5433/flightdb",
        "spring.datasource.username=flight",
        "spring.datasource.password=flight"
})
class BookingFlowIntegrationTest {

    private static final String BASE_URL = "http://localhost:8080";

    private final RestTemplate http = new RestTemplate();

    @Autowired private BookingRepository bookingRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void resetSeat() {
        jdbc.update("UPDATE seat SET status = 'AVAILABLE', booking_id = NULL, hold_expires_at = NULL WHERE seat_no = '3A'");
    }

    @Test
    void fullBookingLifecycle_searchInitiateConfirm() {
        // --- Step 1: Search ---
        FlightSearchResponse searchResp = http.getForObject(
                BASE_URL + "/flights/search?source=BLR&destination=DEL&date=2026-07-15",
                FlightSearchResponse.class);
        assertThat(searchResp).isNotNull();
        assertThat(searchResp.flights()).isNotEmpty();

        FlightSearchResult flight = searchResp.flights().get(0);

        // --- Step 2: Initiate booking ---
        String idempotencyKey = "idem-e2e-" + UUID.randomUUID();
        HttpHeaders initiateHeaders = new HttpHeaders();
        initiateHeaders.set("Idempotency-Key", idempotencyKey);
        initiateHeaders.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<BookingResponse> initiateResp = http.exchange(
                BASE_URL + "/bookings/initiate",
                HttpMethod.POST,
                new HttpEntity<>(new InitiateBookingRequest("U1", flight.scheduledFlightId(), "3A"), initiateHeaders),
                BookingResponse.class);

        assertThat(initiateResp.getStatusCode().is2xxSuccessful()).isTrue();
        BookingResponse bookingResp = initiateResp.getBody();
        assertThat(bookingResp).isNotNull();

        // --- Step 3: Mid-flow assertions (PENDING / HELD / CREATED) ---
        assertThat(bookingResp.status()).isEqualTo("PENDING");
        assertThat(bookingResp.seatNo()).isEqualTo("3A");
        assertThat(bookingResp.paymentId()).isNotNull();
        assertThat(bookingResp.amount()).isEqualByComparingTo(flight.baseFare());

        Booking booking = bookingRepository.findById(bookingResp.bookingId()).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo("PENDING");
        assertThat(booking.getSeatId()).isNotNull();
        assertThat(booking.getPaymentId()).isEqualTo(bookingResp.paymentId());

        Seat seat = seatRepository
                .findByScheduledFlightIdAndSeatNo(flight.scheduledFlightId(), "3A")
                .orElseThrow();
        assertThat(seat.getStatus()).isEqualTo("HELD");
        assertThat(seat.getBookingId()).isEqualTo(bookingResp.bookingId());
        assertThat(seat.getHoldExpiresAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));

        Payment payment = paymentRepository.findById(bookingResp.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo("CREATED");
        assertThat(payment.getBookingId()).isEqualTo(bookingResp.bookingId());
        assertThat(payment.getAmount()).isEqualByComparingTo(bookingResp.amount());

        // --- Step 4: Confirm payment (mock webhook) ---
        HttpHeaders confirmHeaders = new HttpHeaders();
        confirmHeaders.set("Event-Id", "evt-e2e-" + UUID.randomUUID());

        http.exchange(
                BASE_URL + "/payments/" + bookingResp.paymentId() + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(null, confirmHeaders),
                Void.class);

        // --- Step 5: Final state assertions (CONFIRMED / BOOKED / SUCCESS) ---
        Booking confirmedBooking = bookingRepository.findById(bookingResp.bookingId()).orElseThrow();
        assertThat(confirmedBooking.getStatus()).isEqualTo("CONFIRMED");

        Seat bookedSeat = seatRepository
                .findByScheduledFlightIdAndSeatNo(flight.scheduledFlightId(), "3A")
                .orElseThrow();
        assertThat(bookedSeat.getStatus()).isEqualTo("BOOKED");
        assertThat(bookedSeat.getHoldExpiresAt()).isNull();

        Payment successPayment = paymentRepository.findById(bookingResp.paymentId()).orElseThrow();
        assertThat(successPayment.getStatus()).isEqualTo("SUCCESS");
    }
}
