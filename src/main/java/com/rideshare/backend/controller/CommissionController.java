package com.rideshare.backend.controller;

import com.rideshare.backend.dto.CreateSettlementOrderRequest;
import com.rideshare.backend.dto.VerifySettlementRequest;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.CommissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/commission")
public class CommissionController {

    @Autowired
    private CommissionService commissionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private User getAuthenticatedUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        String email = jwtUtil.extractEmail(token);
        return userRepository.findByEmail(email);
    }

    /**
     * Get commission balance and history for authenticated driver.
     */
    @GetMapping("/summary")
    public ResponseEntity<?> getCommissionSummary(@RequestHeader("Authorization") String token) {
        User user = getAuthenticatedUser(token);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        if (!"DRIVER".equals(user.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Only drivers can access commission summary"));
        }
        return ResponseEntity.ok(commissionService.getDriverCommissionSummary(user));
    }

    /**
     * Create payment order to settle outstanding commission.
     */
    @PostMapping("/settle/order")
    public ResponseEntity<?> createSettlementOrder(@RequestHeader("Authorization") String token,
                                                  @RequestBody CreateSettlementOrderRequest request) {
        return commissionService.createSettlementOrder(token, request);
    }

    /**
     * Verify payment provider settlement transaction and reconcile ledger.
     */
    @PostMapping("/settle/verify")
    public ResponseEntity<?> verifySettlement(@RequestHeader("Authorization") String token,
                                              @RequestBody VerifySettlementRequest request) {
        return commissionService.verifySettlement(token, request);
    }

    /**
     * Admin reporting: View system-wide commissions, settled amounts, and driver breakdown.
     */
    @GetMapping("/admin/report")
    public ResponseEntity<?> getAdminCommissionReport(@RequestHeader("Authorization") String token) {
        User user = getAuthenticatedUser(token);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return commissionService.getAdminCommissionReport(user);
    }
}
