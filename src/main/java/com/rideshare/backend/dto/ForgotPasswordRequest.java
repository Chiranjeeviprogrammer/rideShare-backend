package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class ForgotPasswordRequest {
    private String identifier; // email or phone
}
