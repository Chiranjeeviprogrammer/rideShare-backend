package com.rideshare.backend.controller;

import com.rideshare.backend.dto.CouponOfferDto;
import com.rideshare.backend.dto.CouponValidationRequest;
import com.rideshare.backend.dto.CouponValidationResponse;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.CouponService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    @Autowired
    private CouponService couponService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User extractUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String email = jwtUtil.extractEmail(authHeader.substring(7));
            return userRepository.findByEmail(email);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validate coupon code before booking.
     * Evaluates ride fare, user limits, first-ride eligibility, expiration, and calculates discount.
     */
    @PostMapping("/validate")
    public ResponseEntity<CouponValidationResponse> validateCoupon(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody CouponValidationRequest request) {

        User passenger = extractUser(authHeader);
        CouponValidationResponse response = couponService.validateCoupon(
                request.getCode(),
                request.getRideId(),
                request.getSeats() > 0 ? request.getSeats() : 1,
                passenger
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Get active promotional offers available for passengers.
     */
    @GetMapping("/active-offers")
    public ResponseEntity<List<CouponOfferDto>> getActiveOffers(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "rideId", required = false) Long rideId) {

        User passenger = extractUser(authHeader);
        List<CouponOfferDto> offers = couponService.getActiveOffers(passenger, rideId);
        return ResponseEntity.ok(offers);
    }
}
