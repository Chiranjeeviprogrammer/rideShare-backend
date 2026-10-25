package com.rideshare.backend.controller;

import com.rideshare.backend.dto.CreateBookingRequest;
import com.rideshare.backend.model.Booking;
import com.rideshare.backend.service.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    @Autowired
    private BookingService bookingService;

    @PostMapping
    public ResponseEntity<?> createBooking(@RequestHeader("Authorization") String token,
                                          @RequestBody CreateBookingRequest request) {
        return bookingService.createBooking(token, request);
    }

    @GetMapping("/my-bookings")
    public List<Booking> getMyBookings(@RequestHeader("Authorization") String token) {
        return bookingService.getMyBookings(token);
    }

    @GetMapping("/ride/{rideId}")
    public ResponseEntity<?> getBookingsForRide(@RequestHeader("Authorization") String token,
                                               @PathVariable Long rideId) {
        return bookingService.getBookingsForRide(token, rideId);
    }

    @GetMapping("/{id}/cancellation-quote")
    public ResponseEntity<?> getCancellationQuote(@RequestHeader("Authorization") String token,
                                                 @PathVariable Long id) {
        return bookingService.getCancellationQuote(token, id);
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancelBooking(@RequestHeader("Authorization") String token,
                                           @PathVariable Long id,
                                           @RequestBody(required = false) java.util.Map<String, String> body) {
        String reason = body != null ? body.get("reason") : "Passenger requested cancellation";
        return bookingService.cancelBooking(token, id, reason);
    }

    public ResponseEntity<?> cancelBooking(String token, Long id) {
        return cancelBooking(token, id, (java.util.Map<String, String>) null);
    }

    @PutMapping("/{id}/confirm-cash")
    public ResponseEntity<?> confirmCashPayment(@RequestHeader("Authorization") String token,
                                               @PathVariable Long id) {
        return bookingService.confirmCashPayment(token, id);
    }

    @PutMapping("/{id}/pay")
    public ResponseEntity<?> payBooking(@RequestHeader("Authorization") String token,
                                        @PathVariable Long id,
                                        @RequestBody(required = false) java.util.Map<String, Object> payload) {
        return bookingService.payBooking(token, id, payload);
    }

    @GetMapping("/active")
    public ResponseEntity<?> getActiveBooking(@RequestHeader("Authorization") String token) {
        return bookingService.getActiveBooking(token);
    }
}
