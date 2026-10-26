package com.rideshare.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OtpDeliveryService {

    private static final Logger logger = LoggerFactory.getLogger(OtpDeliveryService.class);

    @Autowired
    private EmailService emailService;

    /** Dispatches an OTP without ever logging the code itself. */
    public boolean sendOtp(String recipient, String otp, String purpose) {
        if (recipient == null || recipient.isBlank()) {
            logger.warn("Cannot send OTP: recipient identifier is empty.");
            return false;
        }

        logger.info("Dispatching OTP for recipient: {}, purpose: {}", maskRecipient(recipient), purpose);

        boolean sent = emailService.sendOtpEmail(recipient, otp, purpose, 5);
        if (!sent) {
            logger.warn("OTP email delivery failed for recipient: {}", maskRecipient(recipient));
        }
        return sent;
    }

    private String maskRecipient(String recipient) {
        if (recipient == null || recipient.length() < 4) return "****";
        if (recipient.contains("@")) {
            int atIndex = recipient.indexOf('@');
            if (atIndex > 2) {
                return recipient.substring(0, 2) + "***" + recipient.substring(atIndex);
            }
            return "***" + recipient.substring(atIndex);
        }
        return recipient.substring(0, 2) + "****" + recipient.substring(recipient.length() - 2);
    }
}
