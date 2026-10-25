package com.rideshare.backend.controller;

import com.rideshare.backend.config.AdminDriverMonitoringWebSocketHandler;
import com.rideshare.backend.dto.DriverMonitoringItemDto;
import com.rideshare.backend.dto.DriverMonitoringMetricsDto;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.DriverTrackingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/driver-monitoring")
public class AdminDriverMonitoringController {

    @Autowired
    private DriverTrackingService driverTrackingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AdminDriverMonitoringWebSocketHandler webSocketHandler;

    private User getAuthenticatedAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            String email = jwtUtil.extractEmail(token);
            User user = userRepository.findByEmail(email);
            if (user != null && "ADMIN".equalsIgnoreCase(user.getRole()) && !"SUSPENDED".equalsIgnoreCase(user.getStatus())) {
                return user;
            }
        } catch (Exception e) {
            // Bad token
        }
        return null;
    }

    /**
     * Real-time metrics counters for Admin Dashboard.
     */
    @GetMapping("/metrics")
    public ResponseEntity<?> getMetrics(@RequestHeader("Authorization") String authHeader) {
        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Administrative authorization required"));
        }
        DriverMonitoringMetricsDto metrics = driverTrackingService.calculateMetrics();
        return ResponseEntity.ok(metrics);
    }

    /**
     * Filterable, searchable, paginated list of driver presence records.
     */
    @GetMapping("/drivers")
    public ResponseEntity<?> getDrivers(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String search,
            @RequestParam(required = false, defaultValue = "ALL") String filter,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size
    ) {
        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Administrative authorization required"));
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<DriverMonitoringItemDto> result = driverTrackingService.getDriverMonitoringList(search, filter, pageable);
        return ResponseEntity.ok(result);
    }

    /**
     * WebSocket connection telemetry and health check.
     */
    @GetMapping("/feed-status")
    public ResponseEntity<?> getFeedStatus(@RequestHeader("Authorization") String authHeader) {
        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Administrative authorization required"));
        }
        return ResponseEntity.ok(Map.of(
                "status", "HEALTHY",
                "activeAdminListeners", webSocketHandler.getActiveAdminConnectionsCount(),
                "serverTime", LocalDateTime.now()
        ));
    }
}
