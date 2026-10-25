package com.rideshare.backend.repository;

import com.rideshare.backend.model.DriverPresence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DriverPresenceRepository extends JpaRepository<DriverPresence, Long> {

    List<DriverPresence> findByIsOnlineTrue();

    List<DriverPresence> findByIsOnlineTrueAndLastHeartbeatAtBefore(LocalDateTime threshold);

    @Modifying
    @Query("UPDATE DriverPresence dp SET dp.isOnline = false, dp.lastStatus = 'OFFLINE', dp.updatedAt = :now WHERE dp.isOnline = true AND dp.lastHeartbeatAt < :threshold")
    int markStaleDriversOffline(@Param("threshold") LocalDateTime threshold, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(dp) FROM DriverPresence dp WHERE dp.isOnline = true AND dp.lastHeartbeatAt >= :threshold")
    long countOnlineDrivers(@Param("threshold") LocalDateTime threshold);
}
