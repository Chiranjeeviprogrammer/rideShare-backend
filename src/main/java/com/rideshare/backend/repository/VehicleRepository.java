package com.rideshare.backend.repository;

import com.rideshare.backend.model.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {
    List<Vehicle> findByDriverId(Long driverId);
    long countByDriverId(Long driverId);
}
