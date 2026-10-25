package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class ResetPasswordRequest {
    private String identifier; // email or phone
    private String resetToken;
    private String newPassword;
}
