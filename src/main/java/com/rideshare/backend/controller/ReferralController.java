package com.rideshare.backend.controller;

import com.rideshare.backend.model.Referral;
import com.rideshare.backend.model.User;
import com.rideshare.backend.model.Wallet;
import com.rideshare.backend.repository.ReferralRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.repository.WalletRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/referrals")
public class ReferralController {

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @GetMapping("/summary")
    public Map<String, Object> getReferralSummary(@RequestHeader("Authorization") String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);

        List<Referral> referrals = referralRepository.findByReferrerId(user.getId());
        
        long totalInvitations = referrals.size();
        long successfulReferrals = referrals.stream().filter(r -> "REWARDED".equals(r.getStatus())).count();
        long pendingReferrals = referrals.stream().filter(r -> "PENDING".equals(r.getStatus())).count();
        
        double totalRewardsEarned = referrals.stream()
            .filter(r -> "REWARDED".equals(r.getStatus()))
            .mapToDouble(Referral::getRewardAmount)
            .sum();

        Wallet wallet = walletRepository.findByUserId(user.getId()).orElse(null);
        double currentBalance = wallet != null ? wallet.getBalance() : 0.0;

        Map<String, Object> summary = new HashMap<>();
        summary.put("referralCode", user.getReferralCode());
        summary.put("totalInvitations", totalInvitations);
        summary.put("successfulReferrals", successfulReferrals);
        summary.put("pendingReferrals", pendingReferrals);
        summary.put("totalRewardsEarned", totalRewardsEarned);
        summary.put("currentBalance", currentBalance);
        summary.put("history", referrals);

        return summary;
    }
}
