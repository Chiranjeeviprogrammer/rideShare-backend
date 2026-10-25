package com.rideshare.backend.repository;

import com.rideshare.backend.model.Referral;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReferralRepository extends JpaRepository<Referral, Long> {
    List<Referral> findByReferrerId(Long referrerId);
    Optional<Referral> findByReferredUserId(Long referredUserId);
}
