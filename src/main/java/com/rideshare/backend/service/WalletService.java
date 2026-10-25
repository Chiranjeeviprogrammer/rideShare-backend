package com.rideshare.backend.service;

import com.rideshare.backend.model.Transaction;
import com.rideshare.backend.model.User;
import com.rideshare.backend.model.Wallet;
import com.rideshare.backend.repository.TransactionRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.repository.WalletRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class WalletService {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RazorpayService razorpayService;

    @Transactional
    public Wallet getOrCreateWallet(User user) {
        Optional<Wallet> optWallet = walletRepository.findByUserId(user.getId());
        if (optWallet.isPresent()) {
            return optWallet.get();
        }
        Wallet newWallet = new Wallet();
        newWallet.setUser(user);
        newWallet.setBalance(0.0);
        return walletRepository.save(newWallet);
    }

    public Wallet getMyWallet(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        return getOrCreateWallet(user);
    }

    public List<Transaction> getMyTransactions(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        Wallet wallet = getOrCreateWallet(user);
        return transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
    }

    public ResponseEntity<?> createWalletTopupOrder(String token, Double amount, String rawBody) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        double topUpAmount = (amount != null && amount > 0) ? amount : 0.0;
        if (topUpAmount <= 0 && rawBody != null && !rawBody.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rawBody);
                if (node.has("amount")) {
                    topUpAmount = node.get("amount").asDouble(0.0);
                }
            } catch (Exception ignored) {}
        }

        if (topUpAmount <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid top-up amount. Must be greater than 0."));
        }

        String receiptId = "rcpt_topup_" + System.currentTimeMillis();
        Map<String, Object> rzpOrder;
        try {
            rzpOrder = razorpayService.createOrder(topUpAmount, receiptId, "INR");
        } catch (RazorpayService.PaymentConfigurationException | RazorpayService.PaymentProviderException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "success", false,
                    "message", ex.getMessage(),
                    "status", "PAYMENT_UNAVAILABLE"
            ));
        }

        String gatewayOrderId = rzpOrder.get("gatewayOrderId").toString();

        Map<String, Object> response = new HashMap<>();
        response.put("orderId", "ORD_W_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        response.put("gatewayOrderId", gatewayOrderId);
        response.put("amount", topUpAmount);
        response.put("currency", "INR");
        response.put("razorpayKeyId", razorpayService.getKeyId());
        response.put("description", "RideShare Wallet Top-Up");

        // Optional payment link generation for test checkout
        Map<String, Object> paymentLink = razorpayService.createPaymentLink(
                topUpAmount,
                "RideShare Wallet Top-Up",
                user.getName(),
                user.getEmail(),
                user.getPhoneNumber()
        );

        if (paymentLink != null && paymentLink.containsKey("shortUrl")) {
            response.put("shortUrl", paymentLink.get("shortUrl"));
            response.put("paymentLinkId", paymentLink.get("paymentLinkId"));
        }

        return ResponseEntity.ok(response);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ResponseEntity<?> verifyWalletTopup(String token, Map<String, Object> body) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found"));
        }

        double amount = body.containsKey("amount") ? Double.parseDouble(body.get("amount").toString()) : 0.0;
        String gatewayOrderId = body.containsKey("gatewayOrderId") && body.get("gatewayOrderId") != null ? body.get("gatewayOrderId").toString().trim() : null;
        String gatewayPaymentId = body.containsKey("gatewayPaymentId") && body.get("gatewayPaymentId") != null ? body.get("gatewayPaymentId").toString().trim() : null;
        String gatewaySignature = body.containsKey("gatewaySignature") && body.get("gatewaySignature") != null ? body.get("gatewaySignature").toString().trim() : null;
        String method = body.containsKey("paymentMethod") && body.get("paymentMethod") != null ? body.get("paymentMethod").toString() : "UPI";

        if (amount <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid top-up amount. Must be greater than 0."));
        }

        if (gatewayOrderId == null || gatewayOrderId.isBlank() ||
                gatewayPaymentId == null || gatewayPaymentId.isBlank() ||
                gatewaySignature == null || gatewaySignature.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Missing Razorpay test payment parameters. Order ID, Payment ID, and Signature are strictly required."
            ));
        }

        // Cryptographic signature verification - strictly enforced, fail closed
        boolean valid = razorpayService.verifyPaymentSignature(gatewayOrderId, gatewayPaymentId, gatewaySignature);
        if (!valid) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Invalid Razorpay payment signature. Wallet top-up verification failed.",
                    "status", "FAILED"
            ));
        }

        Wallet wallet = getOrCreateWallet(user);

        // Idempotency: verify this payment has not already been credited
        List<Transaction> existingTxns = transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
        boolean alreadyCredited = existingTxns.stream()
                .anyMatch(t -> t.getDescription() != null && t.getDescription().contains(gatewayPaymentId));

        if (alreadyCredited) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Payment already verified and credited to wallet.",
                    "newBalance", wallet.getBalance(),
                    "paymentId", gatewayPaymentId,
                    "idempotent", true
            ));
        }

        wallet.setBalance(wallet.getBalance() + amount);
        walletRepository.save(wallet);

        Transaction t = new Transaction();
        t.setWallet(wallet);
        t.setAmount(amount);
        t.setDescription("Razorpay Top-Up (" + method + " • " + gatewayPaymentId + ")");
        t.setType("CREDIT");
        Transaction savedTxn = transactionRepository.save(t);

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("newBalance", wallet.getBalance());
        resp.put("amountAdded", amount);
        resp.put("transactionId", savedTxn.getId());
        resp.put("paymentId", gatewayPaymentId);
        resp.put("method", method);

        return ResponseEntity.ok(resp);
    }
}
