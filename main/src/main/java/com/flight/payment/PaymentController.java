package com.flight.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    // POST /payments/{paymentId}/confirm   header: Event-Id (stands in for a gateway webhook)
    @PostMapping("/{paymentId}/confirm")
    @ResponseStatus(HttpStatus.OK)
    public void confirm(@PathVariable String paymentId,
                        @RequestHeader("Event-Id") String eventId) {
        // TODO: verify gateway signature before trusting this event
        paymentService.confirm(paymentId, eventId);
    }
}
