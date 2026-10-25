package com.rideshare.backend.repository;

import com.rideshare.backend.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findTop20ByOrderByTimestampDesc();

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(CAST(:action AS string) IS NULL OR a.action = :action) AND " +
           "(CAST(:entityType AS string) IS NULL OR a.entityType = :entityType) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(a.adminEmail) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(a.details) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "a.entityId LIKE CONCAT('%', CAST(:search AS string), '%')) " +
           "ORDER BY a.timestamp DESC")
    Page<AuditLog> searchAuditLogs(@Param("action") String action,
                                  @Param("entityType") String entityType,
                                  @Param("search") String search,
                                  Pageable pageable);
}
