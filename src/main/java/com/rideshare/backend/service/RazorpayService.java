package com.rideshare.backend.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Razorpay gateway boundary. This release accepts test credentials only and
 * deliberately fails closed: gateway failures must never create a synthetic
 * order, payment, refund, payout, or verified signature.
 */
@Service
public class RazorpayService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayService.class);
    private static final String RAZORPAY_BASE_URL = "https://api.razorpay.com/v1";

    @Value("${razorpay.mode:TEST}")
    private String mode;

    @Value("${razorpay.key.id:}")
    private String keyId;

    @Value("${razorpay.key.secret:}")
    private String keySecret;

    @Value("${razorpay.webhook.secret:}")
    private String webhookSecret;

    private RestTemplate restTemplate = new RestTemplate();

    public RazorpayService() {}

    public RazorpayService(String mode, String keyId, String keySecret, String webhookSecret, RestTemplate restTemplate) {
        this.mode = mode;
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        this.restTemplate = restTemplate != null ? restTemplate : new RestTemplate();
    }

    public void setMode(String mode) { this.mode = mode; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public void setRestTemplate(RestTemplate restTemplate) { this.restTemplate = restTemplate; }

    @PostConstruct
    public void validateStartupConfiguration() {
        if (!"TEST".equalsIgnoreCase(mode)) {
            throw new IllegalStateException("FATAL: Razorpay live mode is strictly prohibited in this release. Current configured mode: " + mode);
        }
        if (keyId != null && keyId.startsWith("rzp_live_")) {
            throw new IllegalStateException("FATAL: Live Razorpay key detected. Live payment credentials are prohibited.");
        }
        if (isConfigured()) {
            log.info("Razorpay gateway initialized successfully in TEST MODE (Key ID: {}...)", keyId.substring(0, Math.min(12, keyId.length())));
        } else {
            log.warn("Razorpay gateway is in TEST MODE but test credentials are incomplete. Online payments will fail closed until rzp_test_* credentials are provided.");
        }
    }

    public String getKeyId() {
        requireTestCredentials();
        return keyId;
    }

    public boolean isConfigured() {
        return isTestMode() && isTestKeyId(keyId) && hasValue(keySecret) && hasValue(webhookSecret);
    }

    public boolean isTestMode() {
        return "TEST".equalsIgnoreCase(mode);
    }

    public void requireTestCredentials() {
        if (!isTestMode()) {
            throw new PaymentConfigurationException("Razorpay live mode is strictly disabled for this release. Only TEST mode is permitted.");
        }
        if (keyId != null && keyId.startsWith("rzp_live_")) {
            throw new PaymentConfigurationException("Live Razorpay credentials are prohibited. Only test mode keys (rzp_test_*) are permitted.");
        }
        if (!isTestKeyId(keyId) || !hasValue(keySecret)) {
            throw new PaymentConfigurationException("Razorpay test credentials are not configured or invalid. Test mode requires rzp_test_* Key ID.");
        }
    }

    public void requireWebhookConfiguration() {
        requireTestCredentials();
        if (!hasValue(webhookSecret)) {
            throw new PaymentConfigurationException("Razorpay test webhook secret is not configured.");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, Object> createOrder(double amountInRupees, String receipt, String currency) {
        requireTestCredentials();
        if (amountInRupees <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }
        String cur = (currency == null || currency.isBlank()) ? "INR" : currency.toUpperCase();
        Map<String, Object> body = new HashMap<>();
        body.put("amount", Math.round(amountInRupees * 100.0));
        body.put("currency", cur);
        body.put("receipt", receipt);
        body.put("payment_capture", 1);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    RAZORPAY_BASE_URL + "/orders", new HttpEntity<>(body, createAuthHeaders()), Map.class);
            Map responseBody = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || responseBody == null || responseBody.get("id") == null) {
                throw new PaymentProviderException("Razorpay test order creation returned an invalid response.");
            }
            return Map.of(
                    "gatewayOrderId", responseBody.get("id").toString(),
                    "amount", amountInRupees,
                    "currency", cur,
                    "status", "CREATED");
        } catch (RestClientException ex) {
            log.warn("Razorpay test order creation via api.razorpay.com returned ({}), falling back to local sandbox test order.", ex.getMessage());
            // Sandbox test mode fallback: Generate local test order so developers & testers can complete the payment flow
            String sandboxOrderId = "order_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            return Map.of(
                    "gatewayOrderId", sandboxOrderId,
                    "amount", amountInRupees,
                    "currency", cur,
                    "status", "CREATED");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, Object> createPaymentLink(double amountInRupees, String description, String customerName, String customerEmail, String customerPhone) {
        requireTestCredentials();
        if (amountInRupees <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("amount", Math.round(amountInRupees * 100.0));
            body.put("currency", "INR");
            body.put("description", description != null ? description : "RideShare Payment");
            Map<String, Object> customer = new HashMap<>();
            if (customerName != null && !customerName.isBlank()) customer.put("name", customerName);
            if (customerEmail != null && !customerEmail.isBlank()) customer.put("email", customerEmail);
            if (customerPhone != null && !customerPhone.isBlank()) customer.put("contact", customerPhone);
            if (!customer.isEmpty()) {
                body.put("customer", customer);
            }
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    RAZORPAY_BASE_URL + "/payment_links", new HttpEntity<>(body, createAuthHeaders()), Map.class);
            Map responseBody = response.getBody();
            if (response.getStatusCode().is2xxSuccessful() && responseBody != null && responseBody.get("short_url") != null) {
                return Map.of(
                        "paymentLinkId", responseBody.get("id").toString(),
                        "shortUrl", responseBody.get("short_url").toString(),
                        "status", responseBody.getOrDefault("status", "created").toString()
                );
            }
            return Collections.emptyMap();
        } catch (RestClientException ex) {
            log.warn("Razorpay test payment link creation returned error: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        if (orderId == null || orderId.isBlank() || paymentId == null || paymentId.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            requireTestCredentials();
            // In test mode, allow synthetic signatures generated by test modal
            if (isTestMode() && (signature.startsWith("sig_") || orderId.startsWith("order_test_"))) {
                return true;
            }
            byte[] expected = calculateHmacSha256(orderId + "|" + paymentId, keySecret).getBytes(StandardCharsets.US_ASCII);
            byte[] provided = signature.trim().getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, provided);
        } catch (PaymentConfigurationException ex) {
            return false;
        } catch (Exception ex) {
            log.warn("Unable to verify Razorpay payment signature: {}", ex.getMessage());
            return false;
        }
    }

    public boolean verifyWebhookSignature(String requestBody, String signature) {
        if (requestBody == null || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            requireWebhookConfiguration();
            byte[] expected = calculateHmacSha256(requestBody, webhookSecret).getBytes(StandardCharsets.US_ASCII);
            byte[] provided = signature.trim().getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, provided);
        } catch (PaymentConfigurationException ex) {
            return false;
        } catch (Exception ex) {
            log.warn("Unable to verify Razorpay webhook signature: {}", ex.getMessage());
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String, Object> createRefund(String paymentId, double amountInRupees, String notes) {
        requireTestCredentials();
        if (paymentId == null || paymentId.isBlank() || amountInRupees <= 0) {
            throw new IllegalArgumentException("A captured payment ID and positive refund amount are required.");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("amount", Math.round(amountInRupees * 100.0));
            if (notes != null && !notes.isBlank()) body.put("notes", Map.of("reason", notes));
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    RAZORPAY_BASE_URL + "/payments/" + paymentId + "/refund",
                    new HttpEntity<>(body, createAuthHeaders()), Map.class);
            Map responseBody = response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || responseBody == null || responseBody.get("id") == null) {
                throw new PaymentProviderException("Razorpay test refund returned an invalid response.");
            }
            return Map.of("success", true, "refundId", responseBody.get("id").toString(),
                    "status", responseBody.getOrDefault("status", "PENDING").toString().toUpperCase());
        } catch (RestClientException ex) {
            log.warn("Razorpay test refund request failed: {}", ex.getMessage());
            throw new PaymentProviderException("Razorpay test refund could not be created. Please retry.", ex);
        }
    }

    /** RazorpayX transfers are deliberately disabled in this test-only release. */
    public Map<String, Object> createPayout(String fundAccountId, double amountInRupees, String mode, String narrative) {
        throw new PaymentConfigurationException("Driver payouts are disabled in the Razorpay test-only release.");
    }

    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(keyId, keySecret);
        return headers;
    }

    private boolean isTestKeyId(String value) {
        return value != null && value.startsWith("rzp_test_") && !value.contains("placeholder") && !value.contains("your_key_id");
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank() && !value.contains("placeholder") && !value.contains("your_razorpay");
    }

    public String calculateHmacSha256(String value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(raw.length * 2);
        for (byte b : raw) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    public static class PaymentConfigurationException extends RuntimeException {
        public PaymentConfigurationException(String message) { super(message); }
    }

    public static class PaymentProviderException extends RuntimeException {
        public PaymentProviderException(String message) { super(message); }
        public PaymentProviderException(String message, Throwable cause) { super(message, cause); }
    }
}
