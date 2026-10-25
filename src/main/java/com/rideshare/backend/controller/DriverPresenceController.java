package com.rideshare.backend.controller;

import com.rideshare.backend.config.DriverTrackingProperties;
import com.rideshare.backend.dto.DriverHeartbeatRequest;
import com.rideshare.backend.model.DriverPresence;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.DriverTrackingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/driver/presence")
public class DriverPresenceController {

    @Autowired
    private DriverTrackingService driverTrackingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private DriverTrackingProperties properties;

    private User getAuthenticatedDriver(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            String email = jwtUtil.extractEmail(token);
            User user = userRepository.findByEmail(email);
            if (user != null && "DRIVER".equalsIgnoreCase(user.getRole()) && !"SUSPENDED".equalsIgnoreCase(user.getStatus())) {
                return user;
            }
        } catch (Exception e) {
            // Bad token
        }
        return null;
    }

    /**
     * Send authenticated driver heartbeat with optional location coordinates and sharing preferences.
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<?> sendHeartbeat(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody(required = false) DriverHeartbeatRequest request
    ) {
        User driver = getAuthenticatedDriver(authHeader);
        if (driver == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only active drivers can send presence telemetry."));
        }

        try {
            DriverPresence presence = driverTrackingService.recordHeartbeat(driver, request);
            boolean isApproved = driverTrackingService.isDriverApproved(driver.getId());
            return ResponseEntity.ok(Map.of(
                    "status", "ONLINE",
                    "driverId", presence.getDriverId(),
                    "isOnline", true,
                    "isApproved", isApproved,
                    "lastHeartbeatAt", presence.getLastHeartbeatAt(),
                    "locationSharingEnabled", presence.getLocationSharingEnabled(),
                    "heartbeatIntervalSeconds", properties.getHeartbeatIntervalSeconds(),
                    "onlineTimeoutSeconds", properties.getOnlineTimeoutSeconds()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Explicitly update presence to offline upon toggle, app closing, or logout.
     */
    @PostMapping("/offline")
    public ResponseEntity<?> setOffline(@RequestHeader("Authorization") String authHeader) {
        User driver = getAuthenticatedDriver(authHeader);
        if (driver == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only active drivers can update presence state."));
        }

        DriverPresence presence = driverTrackingService.setDriverOffline(driver);
        return ResponseEntity.ok(Map.of(
                "status", "OFFLINE",
                "driverId", presence.getDriverId(),
                "isOnline", false
        ));
    }

    /**
     * Query driver's own presence status with authoritative server-side calculation.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyPresence(@RequestHeader("Authorization") String authHeader) {
        User driver = getAuthenticatedDriver(authHeader);
        if (driver == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only active drivers can query presence state."));
        }

        DriverPresence presence = driverTrackingService.getDriverPresence(driver);
        boolean isApproved = driverTrackingService.isDriverApproved(driver.getId());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime onlineThreshold = now.minusSeconds(properties.getOnlineTimeoutSeconds());

        boolean isOnline = presence != null &&
                Boolean.TRUE.equals(presence.getIsOnline()) &&
                presence.getLastHeartbeatAt() != null &&
                presence.getLastHeartbeatAt().isAfter(onlineThreshold) &&
                !"OFFLINE".equalsIgnoreCase(presence.getLastStatus());

        return ResponseEntity.ok(Map.of(
                "driverId", driver.getId(),
                "isOnline", isOnline,
                "lastStatus", isOnline ? "ONLINE" : "OFFLINE",
                "isApproved", isApproved,
                "currentRideId", (presence != null && presence.getCurrentRideId() != null) ? presence.getCurrentRideId() : 0,
                "lastHeartbeatAt", (presence != null && presence.getLastHeartbeatAt() != null) ? presence.getLastHeartbeatAt() : now,
                "heartbeatIntervalSeconds", properties.getHeartbeatIntervalSeconds(),
                "onlineTimeoutSeconds", properties.getOnlineTimeoutSeconds()
        ));
    }
}
