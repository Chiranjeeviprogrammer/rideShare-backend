package com.rideshare.backend.controller;

import com.rideshare.backend.dto.CreatePaymentOrderRequest;
import com.rideshare.backend.dto.VerifyPaymentRequest;
import com.rideshare.backend.model.PaymentOrder;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.PaymentOrderRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/quote")
    public ResponseEntity<?> getQuote(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestBody Map<String, Object> request) {
        Long rideId = request.containsKey("rideId") ? Long.parseLong(request.get("rideId").toString()) : null;
        int seats = request.containsKey("seats") ? Integer.parseInt(request.get("seats").toString()) : 1;
        String couponCode = request.containsKey("couponCode") && request.get("couponCode") != null ? request.get("couponCode").toString() : null;
        return paymentService.getQuoteForRide(rideId, seats, couponCode, token);
    }


    @PostMapping("/orders")
    public ResponseEntity<?> createOrder(@RequestHeader("Authorization") String token,
                                         @RequestBody CreatePaymentOrderRequest request) {
        return paymentService.createOrder(token, request);
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifyPayment(@RequestHeader("Authorization") String token,
                                           @RequestBody VerifyPaymentRequest request) {
        return paymentService.verifyPayment(token, request);
    }

    @PostMapping("/webhook")
    public ResponseEntity<?> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        return paymentService.handleWebhook(payload, signature);
    }

    @PostMapping("/{bookingId}/refund")
    public ResponseEntity<?> refundBooking(
            @RequestHeader("Authorization") String token,
            @PathVariable Long bookingId,
            @RequestBody(required = false) Map<String, String> payload) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }
        String reason = payload != null ? payload.get("reason") : "Passenger cancellation";
        return paymentService.processRefund(user, bookingId, reason);
    }

    @GetMapping("/history")
    public ResponseEntity<?> getPaymentHistory(@RequestHeader("Authorization") String token) {
        return paymentService.getPaymentHistory(token);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getPaymentOrder(@RequestHeader("Authorization") String token, @PathVariable Long id) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }
        PaymentOrder order = paymentOrderRepository.findById(id).orElse(null);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Payment order not found."));
        }
        boolean isOwner = order.getUser().getId().equals(user.getId());
        boolean isAdmin = "ADMIN".equals(user.getRole());
        if (!isOwner && !isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Not authorized to view this payment order."));
        }
        return ResponseEntity.ok(order);
    }

    @GetMapping("/methods")
    public ResponseEntity<?> getPaymentMethodsConfig() {
        return paymentService.getPaymentMethodsConfig();
    }

    @GetMapping("/receipts/{bookingId}")
    public ResponseEntity<?> getPaymentReceipt(@RequestHeader("Authorization") String token,
                                               @PathVariable Long bookingId) {
        return paymentService.getPaymentReceipt(token, bookingId);
    }
}
