package com.rideshare.backend.controller;

import com.rideshare.backend.model.DriverLocation;
import com.rideshare.backend.repository.DriverLocationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/location")
public class LocationController {

    @Autowired
    private DriverLocationRepository locationRepository;

    @PostMapping("/update")
    public DriverLocation updateLocation(@RequestBody DriverLocation location) {
        return locationRepository.save(location); // Overwrites if driverId already exists
    }

    @GetMapping("/{driverId}")
    public DriverLocation getLocation(@PathVariable Long driverId) {
        return locationRepository.findById(driverId).orElse(null);
    }
}
