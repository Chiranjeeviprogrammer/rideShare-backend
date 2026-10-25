package com.rideshare.backend.controller;

import com.rideshare.backend.dto.DriverApplicationRequest;
import com.rideshare.backend.dto.ReviewApplicationRequest;
import com.rideshare.backend.model.CommissionLedger;
import com.rideshare.backend.model.DriverApplication;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.CommissionLedgerRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.DriverService;
import com.rideshare.backend.service.GoogleDriveStorageService;
import com.rideshare.backend.service.PayoutService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/driver", "/api/drivers"})
public class DriverController {

    @Autowired
    private DriverService driverService;

    @Autowired
    private PayoutService payoutService;

    @Autowired
    private GoogleDriveStorageService googleDriveStorageService;

    @Autowired
    private CommissionLedgerRepository commissionLedgerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.rideshare.backend.service.UserDocumentService userDocumentService;

    @Autowired
    private JwtUtil jwtUtil;

    private User getAuthenticatedUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            String email = jwtUtil.extractEmail(token);
            return userRepository.findByEmail(email);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the authenticated driver's real earnings, ride breakdowns, platform fee deductions, and cash settlements.
     */
    @GetMapping({"/earnings", "/me/earnings"})
    public ResponseEntity<?> getDriverEarnings(@RequestHeader("Authorization") String authHeader) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return driverService.getDriverEarningsSummary(user);
    }

    /**
     * Get Driver Payout Profile (Bank Account / UPI)
     */
    @GetMapping({"/payout-account", "/me/payout-account"})
    public ResponseEntity<?> getPayoutAccount(@RequestHeader("Authorization") String authHeader) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return payoutService.getPayoutProfile(user);
    }

    /**
     * Update Driver Payout Profile (Bank Account / UPI)
     */
    @PostMapping({"/payout-account", "/me/payout-account"})
    public ResponseEntity<?> updatePayoutAccount(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> request) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return payoutService.updatePayoutProfile(user, request);
    }

    /**
     * Get Payout History for Driver
     */
    @GetMapping({"/payouts", "/me/payouts"})
    public ResponseEntity<?> getPayouts(@RequestHeader("Authorization") String authHeader) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return payoutService.getDriverPayouts(user);
    }

    /**
     * Request Driver Payout of eligible earnings
     */
    @PostMapping({"/payouts/request", "/me/payouts/request"})
    public ResponseEntity<?> requestPayout(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody(required = false) Map<String, Object> request) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return payoutService.requestPayout(user, request);
    }

    /**
     * Get Driver's Immutable Financial Ledger entries
     */
    @GetMapping({"/ledger", "/me/ledger"})
    public ResponseEntity<?> getDriverLedger(@RequestHeader("Authorization") String authHeader) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        List<CommissionLedger> entries = commissionLedgerRepository.findByDriverIdOrderByCreatedAtDesc(user.getId());
        return ResponseEntity.ok(entries);
    }

    /**
     * Get the authenticated user's driver application status.
     */
    @GetMapping("/application")
    public ResponseEntity<?> getMyApplication(@RequestHeader("Authorization") String authHeader) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return driverService.getMyApplication(user);
    }

    /**
     * Submit or save draft driver application.
     */
    @PostMapping("/application")
    public ResponseEntity<?> submitApplication(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody DriverApplicationRequest request) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return driverService.submitApplication(user, request);
    }

    @PostMapping(value = "/documents/{documentType}", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadDocument(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable String documentType,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(value = "documentNumber", required = false) String documentNumber,
            @RequestParam(value = "expiryDate", required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate expiryDate) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        try {
            com.rideshare.backend.model.UserDocument savedDoc = userDocumentService.uploadDocument(user, documentType, file, documentNumber, expiryDate);
            return ResponseEntity.ok(Map.of(
                    "documentId", savedDoc.getId(),
                    "fileId", savedDoc.getId().toString(),
                    "url", savedDoc.getFileUrl() != null ? savedDoc.getFileUrl() : "",
                    "documentType", savedDoc.getDocumentType(),
                    "status", savedDoc.getVerificationStatus(),
                    "message", "Document uploaded successfully and registered in system."
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Document upload failed: " + ex.getMessage()));
        }
    }

    /**
     * Administrative review endpoint: approve, reject, or request more information.
     */
    @PutMapping("/applications/{id}/review")
    public ResponseEntity<?> reviewApplication(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody ReviewApplicationRequest request) {
        User reviewer = getAuthenticatedUser(authHeader);
        if (reviewer == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return driverService.reviewApplication(id, reviewer, request);
    }

    /**
     * Administrative list of applications by status.
     */
    @GetMapping("/applications")
    public ResponseEntity<?> listApplications(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status) {
        User reviewer = getAuthenticatedUser(authHeader);
        if (reviewer == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        List<DriverApplication> applications = driverService.getApplicationsByStatus(status);
        return ResponseEntity.ok(applications);
    }
}
