package com.flight.booking;

import com.flight.booking.dto.BookingResponse;
import com.flight.booking.dto.InitiateBookingRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // POST /bookings/initiate, header Idempotency-Key. Returns 200 PENDING on success.
    @PostMapping("/initiate")
    public BookingResponse initiate(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody InitiateBookingRequest request) {
        return bookingService.initiate(idempotencyKey, request);
    }
}
