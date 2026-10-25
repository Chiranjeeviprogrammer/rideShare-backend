package com.rideshare.backend.repository;

import com.rideshare.backend.model.UserDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserDocumentRepository extends JpaRepository<UserDocument, Long> {

    List<UserDocument> findByUserIdOrderByUploadedAtDesc(Long userId);

    List<UserDocument> findByUserIdAndIsCurrentTrueOrderByUploadedAtDesc(Long userId);

    Optional<UserDocument> findByUserIdAndDocumentTypeAndIsCurrentTrue(Long userId, String documentType);

    List<UserDocument> findByVerificationStatus(String verificationStatus);

    @Query("SELECT COUNT(d) FROM UserDocument d WHERE d.user.id = :userId AND d.isCurrent = true AND d.verificationStatus = 'APPROVED'")
    long countApprovedDocumentsByUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(d) FROM UserDocument d WHERE d.user.id = :userId AND d.isCurrent = true AND d.verificationStatus = 'PENDING'")
    long countPendingDocumentsByUserId(@Param("userId") Long userId);
}
