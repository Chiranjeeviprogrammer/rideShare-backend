package com.rideshare.backend.repository;

import com.rideshare.backend.model.EmailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    List<EmailLog> findTop20ByOrderBySentAtDesc();

    long countByStatus(String status);

    @Query("SELECT e FROM EmailLog e WHERE " +
           "(CAST(:status AS string) IS NULL OR e.status = :status) AND " +
           "(CAST(:category AS string) IS NULL OR e.category = :category) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(e.recipient) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(e.subject) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))" +
           "ORDER BY e.sentAt DESC")
    Page<EmailLog> searchEmailLogs(@Param("status") String status,
                                   @Param("category") String category,
                                   @Param("search") String search,
                                   Pageable pageable);
}
