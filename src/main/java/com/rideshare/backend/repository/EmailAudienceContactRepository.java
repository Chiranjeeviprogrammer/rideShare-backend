package com.rideshare.backend.repository;

import com.rideshare.backend.model.EmailAudienceContact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailAudienceContactRepository extends JpaRepository<EmailAudienceContact, Long> {

    Optional<EmailAudienceContact> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<EmailAudienceContact> findByUnsubscribeToken(String unsubscribeToken);

    List<EmailAudienceContact> findByStatus(String status);

    List<EmailAudienceContact> findByAudienceTypeAndStatus(String audienceType, String status);

    long countByStatus(String status);

    long countByAudienceType(String audienceType);

    long countByAudienceTypeAndStatus(String audienceType, String status);

    @Query("SELECT c FROM EmailAudienceContact c WHERE " +
           "(:audienceType IS NULL OR c.audienceType = :audienceType) AND " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:search IS NULL OR LOWER(c.email) LIKE :search " +
           "OR LOWER(c.fullName) LIKE :search " +
           "OR LOWER(c.city) LIKE :search)")
    Page<EmailAudienceContact> searchContacts(
            @Param("audienceType") String audienceType,
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable
    );
}
