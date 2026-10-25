package com.rideshare.backend.repository;

import com.rideshare.backend.model.RecurringRideSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecurringRideScheduleRepository extends JpaRepository<RecurringRideSchedule, Long> {

    List<RecurringRideSchedule> findByDriverId(Long driverId);

    List<RecurringRideSchedule> findByDriverIdAndStatus(Long driverId, String status);

    List<RecurringRideSchedule> findByStatus(String status);

    org.springframework.data.domain.Page<RecurringRideSchedule> findByStatus(String status, org.springframework.data.domain.Pageable pageable);

    long countByStatus(String status);
}

