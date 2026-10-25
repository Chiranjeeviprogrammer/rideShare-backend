package com.rideshare.backend.repository;

import com.rideshare.backend.model.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findByUserId(Long userId);
    java.util.List<Wallet> findByUserIdIn(java.util.List<Long> userIds);
}
