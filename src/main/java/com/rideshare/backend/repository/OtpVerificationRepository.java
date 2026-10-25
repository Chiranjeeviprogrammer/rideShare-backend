package com.rideshare.backend.repository;

import com.rideshare.backend.model.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {
    Optional<OtpVerification> findTopByIdentifierAndPurposeAndUsedFalseOrderByCreatedAtDesc(String identifier, String purpose);
    void deleteByIdentifierAndPurpose(String identifier, String purpose);
}
