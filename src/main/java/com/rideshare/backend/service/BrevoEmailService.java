package com.rideshare.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class BrevoEmailService {

    private static final Logger logger = LoggerFactory.getLogger(BrevoEmailService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${brevo.api.key:}")
    private String brevoApiKey;

    @Value("${brevo.sender.email:}")
    private String brevoSenderEmail;

    @Value("${brevo.sender.name:RideShare}")
    private String brevoSenderName;

    @Value("${brevo.api.url:https://api.brevo.com/v3/smtp/email}")
    private String brevoApiUrl;

    public BrevoEmailService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(6000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Checks if Brevo API is configured with an API key.
     */
    public boolean isConfigured() {
        return brevoApiKey != null && !brevoApiKey.trim().isEmpty() && !brevoApiKey.startsWith("your-")
                && brevoSenderEmail != null && !brevoSenderEmail.trim().isEmpty()
                && !brevoSenderEmail.startsWith("verified-sender@");
    }

    public String getSenderEmail() {
        return (brevoSenderEmail != null && !brevoSenderEmail.trim().isEmpty())
                ? brevoSenderEmail.trim()
                : "";
    }

    public String getSenderName() {
        return (brevoSenderName != null && !brevoSenderName.trim().isEmpty())
                ? brevoSenderName.trim()
                : "RideShare";
    }

    public String maskSenderEmail() {
        String email = getSenderEmail();
        int at = email.indexOf('@');
        if (at > 2) {
            return email.substring(0, 2) + "***" + email.substring(at);
        }
        return "***" + (at >= 0 ? email.substring(at) : "");
    }

    /**
     * Diagnostic view: returns sanitized status without revealing secret keys.
     */
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> diagnostics = new HashMap<>();
        diagnostics.put("provider", "BREVO_API");
        diagnostics.put("configured", isConfigured());
        diagnostics.put("senderEmail", maskSenderEmail());
        diagnostics.put("senderName", getSenderName());
        diagnostics.put("apiUrl", brevoApiUrl);
        return diagnostics;
    }

    /**
     * Result holder containing delivery status, messageId, and error details.
     */
    public static class BrevoSendResult {
        private final boolean success;
        private final String messageId;
        private final String errorMessage;
        private final int statusCode;

        public BrevoSendResult(boolean success, String messageId, String errorMessage, int statusCode) {
            this.success = success;
            this.messageId = messageId;
            this.errorMessage = errorMessage;
            this.statusCode = statusCode;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    /**
     * Sends transactional email using Brevo REST API (v3/smtp/email).
     */
    public BrevoSendResult sendTransactionalEmail(String recipientEmail, String recipientName, String subject, String htmlContent, String textContent) {
        if (!isConfigured()) {
            return new BrevoSendResult(false, null, "Brevo API key is not configured.", 0);
        }

        if (recipientEmail == null || recipientEmail.trim().isEmpty() || !recipientEmail.contains("@")) {
            return new BrevoSendResult(false, null, "Invalid recipient email address.", 400);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("api-key", brevoApiKey.trim());

            Map<String, Object> senderMap = new HashMap<>();
            senderMap.put("name", getSenderName());
            senderMap.put("email", getSenderEmail());

            Map<String, String> recipientMap = new HashMap<>();
            recipientMap.put("email", recipientEmail.trim().toLowerCase());
            if (recipientName != null && !recipientName.isBlank()) {
                recipientMap.put("name", recipientName.trim());
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("sender", senderMap);
            payload.put("to", List.of(recipientMap));
            payload.put("subject", subject);
            if (htmlContent != null && !htmlContent.isBlank()) {
                payload.put("htmlContent", htmlContent);
            }
            if (textContent != null && !textContent.isBlank()) {
                payload.put("textContent", textContent);
            }

            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    brevoApiUrl,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                String responseBody = response.getBody();
                String messageId = null;
                if (responseBody != null) {
                    try {
                        JsonNode root = objectMapper.readTree(responseBody);
                        if (root.has("messageId")) {
                            messageId = root.get("messageId").asText();
                        }
                    } catch (Exception ignored) {}
                }
                logger.info("Brevo email dispatched successfully to {} | messageId: {}", maskEmail(recipientEmail), messageId);
                return new BrevoSendResult(true, messageId, null, response.getStatusCode().value());
            } else {
                logger.error("Brevo API returned unexpected status {}: {}", response.getStatusCode(), response.getBody());
                return new BrevoSendResult(false, null, "Brevo API returned status: " + response.getStatusCode(), response.getStatusCode().value());
            }

        } catch (HttpStatusCodeException ex) {
            String errorMsg = extractBrevoErrorMessage(ex.getResponseBodyAsString(), ex.getMessage());
            logger.error("Brevo API error (HTTP {}): {}", ex.getStatusCode(), errorMsg);
            return new BrevoSendResult(false, null, errorMsg, ex.getStatusCode().value());
        } catch (Exception ex) {
            logger.error("Failed to deliver transactional email via Brevo to {}: {}", maskEmail(recipientEmail), ex.getMessage());
            return new BrevoSendResult(false, null, ex.getMessage(), 500);
        }
    }

    private String extractBrevoErrorMessage(String responseBody, String defaultMsg) {
        if (responseBody == null || responseBody.isBlank()) return defaultMsg;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.has("message")) {
                return root.get("message").asText();
            }
        } catch (Exception ignored) {}
        return defaultMsg;
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "not-set";
        int at = email.indexOf('@');
        if (at > 2) {
            return email.substring(0, 2) + "***" + email.substring(at);
        }
        return "***" + (at >= 0 ? email.substring(at) : "");
    }
}
