package com.rideshare.backend.repository;

import com.rideshare.backend.model.GstSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GstSettingsRepository extends JpaRepository<GstSettings, Long> {

    Optional<GstSettings> findTopByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM GstSettings g WHERE g.id = :id")
    Optional<GstSettings> findByIdForUpdate(@Param("id") Long id);
}
