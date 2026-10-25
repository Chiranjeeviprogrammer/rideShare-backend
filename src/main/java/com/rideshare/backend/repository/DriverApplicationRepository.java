package com.rideshare.backend.repository;

import com.rideshare.backend.model.DriverApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface DriverApplicationRepository extends JpaRepository<DriverApplication, Long> {
    Optional<DriverApplication> findByUserId(Long userId);
    List<DriverApplication> findByUserIdIn(List<Long> userIds);
    List<DriverApplication> findByStatus(String status);
    long countByStatus(String status);

    @Query("SELECT d FROM DriverApplication d WHERE " +
           "(CAST(:status AS string) IS NULL OR d.status = :status) AND " +
           "(CAST(:search AS string) IS NULL OR LOWER(d.legalName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(d.contactNumber) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(d.licenseNumber) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "LOWER(d.registrationNumber) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')) OR " +
           "(d.user IS NOT NULL AND LOWER(d.user.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))) " +
           "ORDER BY d.submittedAt DESC")
    Page<DriverApplication> searchApplications(@Param("status") String status,
                                               @Param("search") String search,
                                               Pageable pageable);
}
