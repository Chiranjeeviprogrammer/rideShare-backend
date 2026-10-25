package com.rideshare.backend.controller;

import com.rideshare.backend.model.Transaction;
import com.rideshare.backend.model.Wallet;
import com.rideshare.backend.service.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    @Autowired
    private WalletService walletService;

    @GetMapping
    public Wallet getMyWallet(@RequestHeader("Authorization") String token) {
        return walletService.getMyWallet(token);
    }

    @GetMapping("/transactions")
    public List<Transaction> getMyTransactions(@RequestHeader("Authorization") String token) {
        return walletService.getMyTransactions(token);
    }

    @PostMapping("/create-order")
    public ResponseEntity<?> createWalletTopupOrder(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) Double amount,
            @RequestBody(required = false) String rawBody) {
        return walletService.createWalletTopupOrder(token, amount, rawBody);
    }

    @PostMapping("/verify-topup")
    public ResponseEntity<?> verifyWalletTopup(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> body) {
        return walletService.verifyWalletTopup(token, body);
    }

    /**
     * Legacy arbitrary funding endpoint - disabled to prevent unverified financial mutations.
     */
    @PostMapping("/add-funds")
    public ResponseEntity<?> addFunds(@RequestHeader("Authorization") String token, @RequestParam double amount) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "success", false,
                "message", "Direct unverified wallet modification is disabled. All top-ups must be verified through Razorpay test mode."
        ));
    }
}
