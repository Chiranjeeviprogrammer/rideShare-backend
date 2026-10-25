package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class VerifyPaymentRequest {
    private String orderId;
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private String gatewaySignature;
    private String status; // optional status from frontend
}
