package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class VerifySettlementRequest {
    private String orderId;
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private String gatewaySignature;
    private String paymentMethod;
}
