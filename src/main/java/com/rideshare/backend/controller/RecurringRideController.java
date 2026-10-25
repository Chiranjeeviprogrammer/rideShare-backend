package com.rideshare.backend.controller;

import com.rideshare.backend.dto.RecurringRideScheduleRequest;
import com.rideshare.backend.model.RecurringRideSchedule;
import com.rideshare.backend.model.Ride;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.RecurringRideService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rides/recurring")
public class RecurringRideController {

    @Autowired
    private RecurringRideService recurringRideService;

    @Autowired
    private UserRepository userRepository;

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

    @PostMapping
    public ResponseEntity<?> createSchedule(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody RecurringRideScheduleRequest request
    ) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required."));
        }
        return recurringRideService.createSchedule(user, request);
    }

    @GetMapping("/my-schedules")
    public ResponseEntity<?> getMySchedules(@RequestHeader("Authorization") String authHeader) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required."));
        }
        List<RecurringRideSchedule> schedules = recurringRideService.getDriverSchedules(user.getId());
        return ResponseEntity.ok(schedules);
    }

    @GetMapping("/{id}/departures")
    public ResponseEntity<?> getScheduleDepartures(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id
    ) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required."));
        }
        List<Ride> departures = recurringRideService.getScheduleDepartures(id);
        return ResponseEntity.ok(departures);
    }

    @PutMapping("/{id}/pause")
    public ResponseEntity<?> pauseSchedule(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id
    ) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required."));
        }
        return recurringRideService.pauseSchedule(id, user);
    }

    @PutMapping("/{id}/resume")
    public ResponseEntity<?> resumeSchedule(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id
    ) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required."));
        }
        return recurringRideService.resumeSchedule(id, user);
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancelSchedule(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body
    ) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication required."));
        }
        String reason = (body != null && body.containsKey("reason")) ? body.get("reason") : "Driver cancelled schedule";
        return recurringRideService.cancelSchedule(id, user, reason);
    }
}
