package com.rideshare.backend.repository;

import com.rideshare.backend.model.DriverLocation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverLocationRepository extends JpaRepository<DriverLocation, Long> {
}
