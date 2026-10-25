package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class ResendOtpRequest {
    private String identifier;
    private String purpose; // "SIGNUP" or "PASSWORD_RESET"
}
