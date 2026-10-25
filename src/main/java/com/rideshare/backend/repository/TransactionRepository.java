package com.rideshare.backend.repository;

import com.rideshare.backend.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByWalletIdOrderByCreatedAtDesc(Long walletId);
}
