package com.rideshare.backend.controller;

import com.rideshare.backend.dto.CouponAnalyticsDto;
import com.rideshare.backend.dto.CouponDto;
import com.rideshare.backend.model.CouponRedemption;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.CouponService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/coupons")
public class AdminCouponController {

    @Autowired
    private CouponService couponService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User getAuthenticatedAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            String email = jwtUtil.extractEmail(token);
            User user = userRepository.findByEmail(email);
            if (user != null && ("ADMIN".equalsIgnoreCase(user.getRole()) || "SUPPORT".equalsIgnoreCase(user.getRole()))
                    && !"SUSPENDED".equalsIgnoreCase(user.getStatus())) {
                return user;
            }
        } catch (Exception e) {}
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isEmpty()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @GetMapping
    public ResponseEntity<?> getCoupons(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "discountType", required = false) String discountType,
            @RequestParam(value = "fundingType", required = false) String fundingType,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied. Admin authorization required."));
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<CouponDto> result = couponService.getCouponsAdmin(search, status, discountType, fundingType, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCoupon(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("id") Long id) {

        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied."));
        }

        CouponDto coupon = couponService.getCouponById(id);
        if (coupon == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Coupon not found."));
        }
        return ResponseEntity.ok(coupon);
    }

    @PostMapping
    public ResponseEntity<?> createCoupon(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody CouponDto request,
            HttpServletRequest servletRequest) {

        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied."));
        }

        try {
            CouponDto created = couponService.createCoupon(request, admin.getEmail(), getClientIp(servletRequest));
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to create coupon: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateCoupon(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("id") Long id,
            @RequestBody CouponDto request,
            HttpServletRequest servletRequest) {

        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied."));
        }

        try {
            CouponDto updated = couponService.updateCoupon(id, request, admin.getEmail(), getClientIp(servletRequest));
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to update coupon: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}/toggle-status")
    public ResponseEntity<?> toggleStatus(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("id") Long id,
            HttpServletRequest servletRequest) {

        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied."));
        }

        try {
            CouponDto updated = couponService.toggleStatus(id, admin.getEmail(), getClientIp(servletRequest));
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/{id}/expire")
    public ResponseEntity<?> expireCoupon(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("id") Long id,
            HttpServletRequest servletRequest) {

        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied."));
        }

        try {
            CouponDto updated = couponService.expireCoupon(id, admin.getEmail(), getClientIp(servletRequest));
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/analytics")
    public ResponseEntity<?> getAnalytics(@RequestHeader("Authorization") String authHeader) {
        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied."));
        }

        CouponAnalyticsDto analytics = couponService.getAnalytics();
        return ResponseEntity.ok(analytics);
    }

    @GetMapping("/{id}/redemptions")
    public ResponseEntity<?> getRedemptions(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("id") Long id,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied."));
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "redeemedAt"));
        Page<CouponRedemption> redemptions = couponService.getCouponRedemptions(id, pageable);
        return ResponseEntity.ok(redemptions);
    }
}
