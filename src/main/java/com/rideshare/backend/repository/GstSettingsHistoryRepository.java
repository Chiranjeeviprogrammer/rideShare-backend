package com.rideshare.backend.repository;

import com.rideshare.backend.model.GstSettingsHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GstSettingsHistoryRepository extends JpaRepository<GstSettingsHistory, Long> {

    List<GstSettingsHistory> findBySettingIdOrderByCreatedAtDesc(Long settingId);

    Page<GstSettingsHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
