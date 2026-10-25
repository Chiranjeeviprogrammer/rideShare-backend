package com.rideshare.backend.repository;

import com.rideshare.backend.model.CancellationPolicyRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CancellationPolicyRuleRepository extends JpaRepository<CancellationPolicyRule, Long> {

    List<CancellationPolicyRule> findByActiveTrueOrderBySortOrderAsc();

    List<CancellationPolicyRule> findAllByOrderBySortOrderAsc();

    @Query("SELECT r FROM CancellationPolicyRule r WHERE r.active = true ORDER BY r.minHoursBeforeDeparture DESC")
    List<CancellationPolicyRule> findActiveRulesOrderedByWindow();

    List<CancellationPolicyRule> findByActiveTrueOrderByMinHoursBeforeDepartureDesc();
}
