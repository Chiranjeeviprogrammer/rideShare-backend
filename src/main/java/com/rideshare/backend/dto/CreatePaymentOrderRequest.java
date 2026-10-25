package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class CreatePaymentOrderRequest {
    private Long bookingId;
    private Long rideId;
    private Integer seats;
    private String pickupAddress;
    private String dropoffAddress;
    private double amount;
    private String currency = "INR";
    private String paymentMethod = "ONLINE";
    private String couponCode;

}
