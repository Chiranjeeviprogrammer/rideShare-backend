package com.rideshare.backend.controller;

import com.rideshare.backend.model.Ride;
import com.rideshare.backend.service.RideService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rides")
public class RideController {

    @Autowired
    private RideService rideService;

    @GetMapping
    public List<Ride> getRides(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer seats,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String time) {
        if (from != null || to != null || seats != null || date != null || time != null) {
            return rideService.searchRides(from, to, seats, date, time);
        }
        return rideService.getAllRides();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getRideById(@PathVariable Long id) {
        return rideService.getRideById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/my-rides")
    public List<Ride> getMyRides(@RequestHeader("Authorization") String token) {
        return rideService.getMyRides(token);
    }

    @GetMapping("/active")
    public ResponseEntity<?> getActiveRide(@RequestHeader("Authorization") String token) {
        return rideService.getActiveRide(token);
    }

    @PostMapping
    public ResponseEntity<?> requestRide(@RequestHeader(value = "Authorization", required = false) String token, @RequestBody Ride ride) {
        return rideService.publishRide(token, ride);
    }

    @PutMapping("/{id}/start")
    public ResponseEntity<?> startRide(@RequestHeader("Authorization") String token, @PathVariable Long id) {
        return rideService.startRide(token, id);
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<?> completeRide(@RequestHeader("Authorization") String token, @PathVariable Long id) {
        return rideService.completeRide(token, id);
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<?> cancelRide(@RequestHeader("Authorization") String token, @PathVariable Long id) {
        return rideService.cancelRide(token, id);
    }

    @PutMapping("/{id}/accept")
    public ResponseEntity<?> acceptRide(@PathVariable Long id, @RequestParam(required = false) Long driverId) {
        // Business Rule: Drivers cannot manually accept or reject bookings. Automatic confirmation is enforced.
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "message", "Manual booking/ride acceptance has been removed. Bookings are automatically confirmed."
        ));
    }
}

