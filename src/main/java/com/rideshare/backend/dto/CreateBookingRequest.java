package com.rideshare.backend.dto;

import lombok.Data;

@Data
public class CreateBookingRequest {
    private Long rideId;
    private int seats = 1;
    private String pickupAddress;
    private String dropoffAddress;
    private String paymentMethod = "ONLINE"; // ONLINE, CASH, PAY_AFTER_RIDE
    private String couponCode;

}
