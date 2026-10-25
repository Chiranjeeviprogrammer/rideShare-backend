package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class ReviewApplicationRequest {
    // APPROVED, REJECTED, ADDITIONAL_INFORMATION_REQUIRED, SUSPENDED
    private String status;
    private String reviewFeedback;
}
