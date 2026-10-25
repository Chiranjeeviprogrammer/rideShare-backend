package com.rideshare.backend.controller;

import com.rideshare.backend.model.User;
import com.rideshare.backend.model.Vehicle;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.repository.VehicleRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.rideshare.backend.model.CarMake;
import com.rideshare.backend.repository.CarMakeRepository;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    @Autowired
    private VehicleRepository vehicleRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private CarMakeRepository carMakeRepository;

    @GetMapping
    public List<Vehicle> getMyVehicles(@RequestHeader("Authorization") String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User driver = userRepository.findByEmail(email);
        return vehicleRepository.findByDriverId(driver.getId());
    }

    @PostMapping
    public Vehicle addVehicle(@RequestHeader("Authorization") String token, @RequestBody Vehicle vehicle) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User driver = userRepository.findByEmail(email);
        
        vehicle.setDriver(driver);
        return vehicleRepository.save(vehicle);
    }

    @PutMapping("/{id}")
    public Vehicle updateVehicle(@RequestHeader("Authorization") String token, @PathVariable Long id, @RequestBody Vehicle updatedVehicle) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User driver = userRepository.findByEmail(email);
        
        Vehicle vehicle = vehicleRepository.findById(id).orElseThrow(() -> new RuntimeException("Vehicle not found"));
        if (!vehicle.getDriver().getId().equals(driver.getId())) {
            throw new RuntimeException("Not authorized to edit this vehicle");
        }
        
        vehicle.setMake(updatedVehicle.getMake());
        vehicle.setModel(updatedVehicle.getModel());
        vehicle.setYear(updatedVehicle.getYear());
        vehicle.setLicensePlate(updatedVehicle.getLicensePlate());
        vehicle.setColor(updatedVehicle.getColor());
        
        return vehicleRepository.save(vehicle);
    }

    @DeleteMapping("/{id}")
    public org.springframework.http.ResponseEntity<?> deleteVehicle(@RequestHeader("Authorization") String token, @PathVariable Long id) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User driver = userRepository.findByEmail(email);
        
        Vehicle vehicle = vehicleRepository.findById(id).orElseThrow(() -> new RuntimeException("Vehicle not found"));
        if (!vehicle.getDriver().getId().equals(driver.getId())) {
            return org.springframework.http.ResponseEntity.status(403).body("Not authorized");
        }
        
        vehicleRepository.delete(vehicle);
        return org.springframework.http.ResponseEntity.ok().build();
    }

    @GetMapping("/reference")
    public List<CarMake> getVehicleReferenceData() {
        return carMakeRepository.findAllWithModels();
    }
}
