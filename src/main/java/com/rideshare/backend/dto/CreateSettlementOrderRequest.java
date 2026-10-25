package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class CreateSettlementOrderRequest {
    /**
     * Amount the driver wishes to settle. If null or <= 0, settles entire outstanding commission balance.
     */
    private Double amount;
    private String paymentMethod; // e.g. "UPI", "CARD", "WALLET"
}
