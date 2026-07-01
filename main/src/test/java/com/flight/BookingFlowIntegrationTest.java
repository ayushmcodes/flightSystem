package com.flight;

import com.flight.booking.BookingRepository;
import com.flight.booking.BookingService;
import com.flight.booking.dto.BookingResponse;
import com.flight.booking.dto.InitiateBookingRequest;
import com.flight.booking.entity.Booking;
import com.flight.inventory.SeatRepository;
import com.flight.inventory.entity.Seat;
import com.flight.payment.PaymentRepository;
import com.flight.payment.PaymentService;
import com.flight.payment.entity.Payment;
import com.flight.search.FlightSearchService;
import com.flight.search.dto.FlightSearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test against a real PostgreSQL container.
 * Covers the full booking lifecycle: search → initiate → confirm (mock webhook).
 *
 * Requires Docker. The container is started once for the class and shared across all
 * tests. Schema and seed data are applied via the existing db/init/ scripts — the same
 * files the production docker-compose uses, so no test-specific SQL is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BookingFlowIntegrationTest {

    @Container
    @SuppressWarnings("resource") // lifecycle managed by the @Testcontainers extension
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("flightdb")
            .withUsername("flight")
            .withPassword("flight")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("db/init/V1__schema.sql"),
                    "/docker-entrypoint-initdb.d/01-schema.sql")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("db/init/V2__seed.sql"),
                    "/docker-entrypoint-initdb.d/02-seed.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private FlightSearchService flightSearchService;
    @Autowired private BookingService bookingService;
    @Autowired private PaymentService paymentService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private PaymentRepository paymentRepository;

    @Test
    void fullBookingLifecycle_searchInitiateConfirm() {
        // --- Step 1: Search ---
        List<FlightSearchResult> flights = flightSearchService
                .search("BLR", "DEL", LocalDate.of(2026, 7, 15))
                .flights();
        assertThat(flights).isNotEmpty();

        FlightSearchResult flight = flights.get(0); // SF1: 6E-203, BLR→DEL

        // --- Step 2: Initiate ---
        BookingResponse response = bookingService.initiate(
                "idem-e2e-1",
                new InitiateBookingRequest("U1", flight.scheduledFlightId(), "3A"));

        // --- Step 3: Mid-flow assertions (PENDING / HELD / CREATED) ---
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.seatNo()).isEqualTo("3A");
        assertThat(response.paymentId()).isNotNull();
        assertThat(response.amount()).isEqualByComparingTo(flight.baseFare());

        Booking booking = bookingRepository.findById(response.bookingId()).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo("PENDING");
        assertThat(booking.getSeatId()).isNotNull();
        assertThat(booking.getPaymentId()).isEqualTo(response.paymentId());

        Seat seat = seatRepository
                .findByScheduledFlightIdAndSeatNo(flight.scheduledFlightId(), "3A")
                .orElseThrow();
        assertThat(seat.getStatus()).isEqualTo("HELD");
        assertThat(seat.getBookingId()).isEqualTo(response.bookingId());
        assertThat(seat.getHoldExpiresAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));

        Payment payment = paymentRepository.findById(response.paymentId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo("CREATED");
        assertThat(payment.getBookingId()).isEqualTo(response.bookingId());
        assertThat(payment.getAmount()).isEqualByComparingTo(response.amount());

        // --- Step 4: Confirm payment (mock webhook) ---
        paymentService.confirm(response.paymentId(), "evt-e2e-1");

        // --- Step 5: Final state assertions (CONFIRMED / BOOKED / SUCCESS) ---
        Booking confirmedBooking = bookingRepository.findById(response.bookingId()).orElseThrow();
        assertThat(confirmedBooking.getStatus()).isEqualTo("CONFIRMED");

        Seat bookedSeat = seatRepository
                .findByScheduledFlightIdAndSeatNo(flight.scheduledFlightId(), "3A")
                .orElseThrow();
        assertThat(bookedSeat.getStatus()).isEqualTo("BOOKED");
        assertThat(bookedSeat.getHoldExpiresAt()).isNull();

        Payment successPayment = paymentRepository.findById(response.paymentId()).orElseThrow();
        assertThat(successPayment.getStatus()).isEqualTo("SUCCESS");
    }
}
