package com.rideshare.backend.controller;

import com.rideshare.backend.service.EmailService;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/email")
public class EmailController {

    @Autowired
    private EmailService emailService;

    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository userRepository;

    private boolean isAdmin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        try {
            User user = userRepository.findByEmail(jwtUtil.extractEmail(authorization.substring(7)));
            return user != null && "ADMIN".equalsIgnoreCase(user.getRole());
        } catch (Exception ignored) { return false; }
    }

    /**
     * Diagnostic endpoint: verifies whether Gmail SMTP is configured without leaking credentials.
     */
    @GetMapping("/diagnostics")
    public ResponseEntity<?> getDiagnostics(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!isAdmin(authorization)) return ResponseEntity.status(403).body(Map.of("message", "Admin access required."));
        return ResponseEntity.ok(emailService.getDiagnostics());
    }

    /**
     * Safe connectivity / test email sending endpoint.
     */
    @PostMapping("/test")
    public ResponseEntity<?> sendTestEmail(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, String> payload) {
        if (!isAdmin(authorization)) return ResponseEntity.status(403).body(Map.of("message", "Admin access required."));
        String recipient = payload.get("to");
        if (recipient == null || recipient.trim().isEmpty() || !recipient.contains("@")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "A valid 'to' email address is required."
            ));
        }

        if (!emailService.isConfigured()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "configured", false,
                    "message", "Email service is not configured. Please supply BREVO_API_KEY (or SMTP credentials) in environment."
            ));
        }

        long start = System.currentTimeMillis();
        boolean sent = emailService.sendPlainTextEmail(
                recipient.trim(),
                "RideShare Email Service Test Notification",
                "Hello,\n\nThis is a test notification confirming that your RideShare transactional email service is active and operational!\n\nTimestamp: " + java.time.LocalDateTime.now()
        );
        long duration = System.currentTimeMillis() - start;

        Map<String, Object> response = new HashMap<>();
        response.put("success", sent);
        response.put("latencyMs", duration);
        response.put("recipient", recipient.trim());
        response.put("message", sent
                ? "Test email dispatched successfully."
                : "Failed to dispatch test email. Check server logs for details.");

        return ResponseEntity.ok(response);
    }
}
