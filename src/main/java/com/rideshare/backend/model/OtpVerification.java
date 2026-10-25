package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "otp_verifications")
public class OtpVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String identifier; // email or phone number

    @Column(nullable = false)
    private String otpHash; // BCrypt hash of 6-digit OTP

    @Column(nullable = false)
    private String purpose; // "SIGNUP" or "PASSWORD_RESET"

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private boolean used;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_resend_at")
    private LocalDateTime lastResendAt;
}
