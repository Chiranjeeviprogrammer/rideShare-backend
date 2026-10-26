package com.rideshare.backend.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Autowired
    private BrevoEmailService brevoEmailService;

    @Autowired
    private EmailTemplateService templateService;

    @Autowired(required = false)
    private com.rideshare.backend.repository.EmailLogRepository emailLogRepository;

    @Value("${spring.mail.host:smtp.gmail.com}")
    private String mailHost;

    @Value("${spring.mail.port:587}")
    private int mailPort;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${rideshare.mail.from:}")
    private String mailFrom;

    @Value("${rideshare.mail.from-name:RideShare}")
    private String mailFromName;

    @Value("${rideshare.mail.enabled:true}")
    private boolean mailEnabled;

    @Value("${rideshare.mail.fallback-enabled:false}")
    private boolean fallbackEnabled;

    /**
     * Checks if either Brevo API or Gmail SMTP credentials are appropriately configured.
     */
    public boolean isConfigured() {
        if (!mailEnabled) {
            return false;
        }
        if (brevoEmailService != null && brevoEmailService.isConfigured()) {
            return true;
        }
        return mailSender != null
                && mailUsername != null
                && !mailUsername.trim().isEmpty()
                && mailPassword != null
                && !mailPassword.trim().isEmpty();
    }

    /**
     * Diagnostic endpoint helper: returns sanitized status without revealing sensitive credentials.
     */
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> status = new HashMap<>();
        status.put("mailEnabled", mailEnabled);
        status.put("configured", isConfigured());
        status.put("activeProvider", brevoEmailService != null && brevoEmailService.isConfigured() ? "BREVO_API" : "GMAIL_SMTP");
        if (brevoEmailService != null) {
            status.put("brevo", brevoEmailService.getDiagnostics());
        }
        status.put("fromAddress", maskEmail(getFromAddress()));
        Map<String, Object> smtpDiagnostics = new HashMap<>();
        smtpDiagnostics.put("host", mailHost);
        smtpDiagnostics.put("port", mailPort);
        smtpDiagnostics.put("configured", mailSender != null && mailUsername != null && !mailUsername.isBlank() && mailPassword != null && !mailPassword.isBlank());
        smtpDiagnostics.put("senderConfigured", mailUsername != null && !mailUsername.isBlank());
        smtpDiagnostics.put("fromAddress", maskEmail(getFromAddress()));
        smtpDiagnostics.put("fromName", mailFromName);
        status.put("smtp", smtpDiagnostics);
        return status;
    }

    private String getFromAddress() {
        if (mailFrom != null && !mailFrom.isBlank()) {
            return mailFrom.trim();
        }
        if (mailUsername != null && !mailUsername.isBlank()) {
            return mailUsername.trim();
        }
        return "no-reply@rideshare.com";
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) return "not-set";
        int at = email.indexOf('@');
        if (at > 2) {
            return email.substring(0, 2) + "***" + email.substring(at);
        }
        return "***" + (at >= 0 ? email.substring(at) : "");
    }

    private void recordLog(String to, String subject, String category, String status, String errorMessage, long latencyMs) {
        if (emailLogRepository != null) {
            try {
                com.rideshare.backend.model.EmailLog log = com.rideshare.backend.model.EmailLog.builder()
                        .recipient(to)
                        .subject(subject)
                        .category(category != null ? category : "NOTIFICATION")
                        .status(status)
                        .errorMessage(errorMessage)
                        .latencyMs(latencyMs)
                        .sentAt(java.time.LocalDateTime.now())
                        .build();
                emailLogRepository.save(log);
            } catch (Exception e) {
                logger.warn("Failed to save email delivery log: {}", e.getMessage());
            }
        }
    }

    /**
     * Sends plain text email synchronously.
     */
    public boolean sendPlainTextEmail(String to, String subject, String body) {
        return sendPlainTextEmail(to, subject, body, "NOTIFICATION");
    }

    public boolean sendPlainTextEmail(String to, String subject, String body, String category) {
        if (!validateRecipient(to)) {
            recordLog(to, subject, category, "FAILED", "Invalid recipient address", 0);
            return false;
        }

        long start = System.currentTimeMillis();

        // 1. Primary: Send via Brevo Transactional Email API if configured
        if (brevoEmailService != null && brevoEmailService.isConfigured()) {
            BrevoEmailService.BrevoSendResult result = brevoEmailService.sendTransactionalEmail(
                    to,
                    null,
                    subject,
                    "<pre style=\"font-family: sans-serif; font-size: 15px;\">" + org.springframework.web.util.HtmlUtils.htmlEscape(body) + "</pre>",
                    body
            );
            long latency = System.currentTimeMillis() - start;
            if (result.isSuccess()) {
                logger.info("Plain text email successfully dispatched via Brevo to {} | Subject: {}", maskEmail(to), subject);
                recordLog(to, subject, category, "SENT", "Brevo msgId: " + result.getMessageId(), latency);
                return true;
            } else {
                logger.warn("Brevo API send failed for {}: {}. Attempting fallback...", maskEmail(to), result.getErrorMessage());
                recordLog(to, subject, category, "BREVO_FAILED", result.getErrorMessage(), latency);
            }
        }

        // 2. Secondary Fallback: Send via Gmail SMTP if configured and fallback is explicitly enabled
        if (fallbackEnabled && mailSender != null && mailUsername != null && !mailUsername.isBlank() && mailPassword != null && !mailPassword.isBlank()) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(getFromAddress());
                message.setTo(to.trim());
                message.setSubject(subject);
                message.setText(body);

                mailSender.send(message);
                long latency = System.currentTimeMillis() - start;
                logger.info("Plain text email successfully sent via SMTP to {} | Subject: {}", maskEmail(to), subject);
                recordLog(to, subject, category, "SENT", "SMTP", latency);
                return true;
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                logger.error("Failed to send plain text email via SMTP to {}: {}", maskEmail(to), e.getMessage());
                recordLog(to, subject, category, "FAILED", e.getMessage(), latency);
                return false;
            }
        }

        recordLog(to, subject, category, "FAILED", "No email provider is configured", System.currentTimeMillis() - start);
        return false;
    }

    /**
     * Sends responsive HTML email synchronously.
     */
    public boolean sendHtmlEmail(String to, String subject, String htmlContent, String fallbackText) {
        return sendHtmlEmail(to, subject, htmlContent, fallbackText, "NOTIFICATION");
    }

    public boolean sendHtmlEmail(String to, String subject, String htmlContent, String fallbackText, String category) {
        if (!validateRecipient(to)) {
            recordLog(to, subject, category, "FAILED", "Invalid recipient address", 0);
            return false;
        }

        long start = System.currentTimeMillis();

        // 1. Primary: Send via Brevo Transactional Email API if configured
        if (brevoEmailService != null && brevoEmailService.isConfigured()) {
            BrevoEmailService.BrevoSendResult result = brevoEmailService.sendTransactionalEmail(
                    to,
                    null,
                    subject,
                    htmlContent,
                    fallbackText
            );
            long latency = System.currentTimeMillis() - start;
            if (result.isSuccess()) {
                logger.info("HTML email successfully dispatched via Brevo to {} | Subject: {}", maskEmail(to), subject);
                recordLog(to, subject, category, "SENT", "Brevo msgId: " + result.getMessageId(), latency);
                return true;
            } else {
                logger.warn("Brevo API send failed for {}: {}. Attempting fallback...", maskEmail(to), result.getErrorMessage());
                recordLog(to, subject, category, "BREVO_FAILED", result.getErrorMessage(), latency);
            }
        }

        // 2. Secondary Fallback: Send via Gmail SMTP if configured and fallback is explicitly enabled
        if (fallbackEnabled && mailSender != null && mailUsername != null && !mailUsername.isBlank() && mailPassword != null && !mailPassword.isBlank()) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

                helper.setFrom(new InternetAddress(getFromAddress(), mailFromName));
                helper.setTo(to.trim());
                helper.setSubject(subject);
                helper.setText(fallbackText != null ? fallbackText : "Please view this message in an HTML-capable email client.", htmlContent);

                mailSender.send(message);
                long latency = System.currentTimeMillis() - start;
                logger.info("HTML email successfully delivered via SMTP to {} | Subject: {}", maskEmail(to), subject);
                recordLog(to, subject, category, "SENT", "SMTP", latency);
                return true;
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                logger.error("Failed to deliver HTML email via SMTP to {}: {}", maskEmail(to), e.getMessage());
                recordLog(to, subject, category, "FAILED", e.getMessage(), latency);
                return false;
            }
        }

        recordLog(to, subject, category, "FAILED", "No email provider is configured", System.currentTimeMillis() - start);
        return false;
    }

    // --- High-Level Notification Dispatchers (Asynchronous / Non-blocking) ---

    public boolean sendOtpEmail(String to, String otp, String purpose, int expiryMinutes) {
        String subject;
        if ("SIGNUP".equalsIgnoreCase(purpose)) {
            subject = "Verify your RideShare account";
        } else if ("LOGIN".equalsIgnoreCase(purpose)) {
            subject = "Your RideShare login verification code";
        } else {
            subject = "Reset your RideShare password";
        }
        String html = templateService.buildOtpTemplate(otp, purpose, expiryMinutes);
        String fallback = "Your RideShare 6-digit verification code is " + otp + ". It expires in " + expiryMinutes + " minutes. Do not share this code with anyone.";
        String category = "SIGNUP".equalsIgnoreCase(purpose) ? "SIGNUP_OTP" : ("LOGIN".equalsIgnoreCase(purpose) ? "LOGIN_OTP" : "PASSWORD_RESET");
        return sendHtmlEmail(to, subject, html, fallback, category);
    }

    @org.springframework.scheduling.annotation.Async
    public void sendPasswordResetSuccessEmail(String to, String userName) {
        String subject = "Your RideShare Password Has Been Updated";
        String html = templateService.buildPasswordResetSuccessTemplate(userName);
        String fallback = "Hello " + userName + ", your RideShare account password was successfully updated.";
        sendHtmlEmail(to, subject, html, fallback, "PASSWORD_RESET");
    }

    @Async
    public void sendBookingConfirmedEmail(String to, String passengerName, Long bookingId, String pickup, String dropoff, Double fare, String driverName, LocalDateTime departureTime) {
        String subject = "Booking Confirmed: #" + bookingId + " with " + driverName;
        String html = templateService.buildBookingConfirmedTemplate(passengerName, bookingId, pickup, dropoff, fare, driverName, departureTime);
        String fallback = "Hello " + passengerName + ", your RideShare booking #" + bookingId + " is confirmed. Route: " + pickup + " to " + dropoff + ". Fare: ₹" + fare;
        sendHtmlEmail(to, subject, html, fallback, "BOOKING_CONFIRMATION");
    }

    @Async
    public void sendNewBookingAlertDriverEmail(String to, String driverName, Long bookingId, String passengerName, String pickup, String dropoff, Double fare) {
        String subject = "New Passenger Booked: Seat reserved on your ride #" + bookingId;
        String html = templateService.buildNewPassengerAlertDriverTemplate(driverName, bookingId, passengerName, pickup, dropoff, fare);
        String fallback = "Hello " + driverName + ", passenger " + passengerName + " booked a seat on your ride. Route: " + pickup + " to " + dropoff;
        sendHtmlEmail(to, subject, html, fallback, "BOOKING_ALERT");
    }

    @Async
    public void sendBookingCancelledEmail(String to, String name, Long bookingId, String reason) {
        String subject = "RideShare Booking Cancelled: #" + bookingId;
        String html = templateService.buildBookingCancelledTemplate(name, bookingId, reason);
        String fallback = "Hello " + name + ", booking #" + bookingId + " has been cancelled. Details: " + reason;
        sendHtmlEmail(to, subject, html, fallback, "BOOKING_CANCEL");
    }

    @Async
    public void sendTripStartedEmail(String to, String passengerName, Long rideId, String driverName) {
        String subject = "Trip Started: Ride #" + rideId;
        String html = templateService.buildTripStartedTemplate(passengerName, rideId, driverName);
        String fallback = "Hello " + passengerName + ", your ride #" + rideId + " with driver " + driverName + " has started.";
        sendHtmlEmail(to, subject, html, fallback, "RIDE_UPDATE");
    }

    @Async
    public void sendTripCompletedEmail(String to, String passengerName, Long rideId, Double fare) {
        String subject = "Trip Completed: Thank you for riding with RideShare!";
        String html = templateService.buildTripCompletedTemplate(passengerName, rideId, fare);
        String fallback = "Hello " + passengerName + ", your trip #" + rideId + " is completed. Total fare: ₹" + fare;
        sendHtmlEmail(to, subject, html, fallback, "RIDE_UPDATE");
    }

    @Async
    public void sendDriverApplicationReviewedEmail(String to, String applicantName, String status, String feedback) {
        String subject = "RideShare Driver Application Update: " + status;
        String html = templateService.buildDriverApplicationReviewTemplate(applicantName, status, feedback);
        String fallback = "Hello " + applicantName + ", your driver application status is now: " + status + ". Feedback: " + (feedback != null ? feedback : "None");
        sendHtmlEmail(to, subject, html, fallback, "DRIVER_STATUS");
    }

    private boolean validateRecipient(String to) {
        if (to == null || to.trim().isEmpty() || !to.contains("@")) {
            logger.warn("Invalid email recipient provided: {}", to);
            return false;
        }
        return true;
    }
}
