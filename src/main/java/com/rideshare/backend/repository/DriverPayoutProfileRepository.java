package com.rideshare.backend.repository;

import com.rideshare.backend.model.DriverPayoutProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DriverPayoutProfileRepository extends JpaRepository<DriverPayoutProfile, Long> {
    Optional<DriverPayoutProfile> findByDriverId(Long driverId);
}
