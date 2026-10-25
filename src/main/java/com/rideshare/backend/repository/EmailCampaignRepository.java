package com.rideshare.backend.repository;

import com.rideshare.backend.model.EmailCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmailCampaignRepository extends JpaRepository<EmailCampaign, Long> {

    List<EmailCampaign> findByStatus(String status);

    @Query("SELECT c FROM EmailCampaign c WHERE " +
           "(:status IS NULL OR c.status = :status) AND " +
           "(:campaignType IS NULL OR c.campaignType = :campaignType) AND " +
           "(:search IS NULL OR LOWER(c.name) LIKE :search OR LOWER(c.subject) LIKE :search)")
    Page<EmailCampaign> searchCampaigns(
            @Param("status") String status,
            @Param("campaignType") String campaignType,
            @Param("search") String search,
            Pageable pageable
    );
}
