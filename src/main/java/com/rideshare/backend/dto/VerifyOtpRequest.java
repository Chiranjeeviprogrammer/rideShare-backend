package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class VerifyOtpRequest {
    private String identifier; // email or phone
    private String otp;
    private String purpose; // "SIGNUP" or "PASSWORD_RESET"
}
