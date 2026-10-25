package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "referrals")
public class Referral {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "referrer_id", nullable = false)
    private User referrer;

    @ManyToOne
    @JoinColumn(name = "referred_user_id", nullable = false, unique = true)
    private User referredUser;

    private String status; // PENDING, REWARDED, INELIGIBLE

    @Column(name = "reward_amount")
    private Double rewardAmount;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "rewarded_at")
    private LocalDateTime rewardedAt;
}
