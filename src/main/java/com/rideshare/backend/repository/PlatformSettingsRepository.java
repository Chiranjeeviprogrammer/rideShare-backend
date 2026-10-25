package com.rideshare.backend.repository;

import com.rideshare.backend.model.PlatformSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformSettingsRepository extends JpaRepository<PlatformSettings, Long> {
    Optional<PlatformSettings> findTopByOrderByIdAsc();
}
