package com.rideshare.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverMonitoringItemDto {
    private Long driverId;
    private String driverName;
    private String driverEmail;
    private String driverPhone;
    private String avatarUrl;
    private String userStatus; // "ACTIVE", "SUSPENDED"

    private String approvalStatus; // "APPROVED", "PENDING_REVIEW", "NOT_APPLIED", "REJECTED", "SUSPENDED"
    private boolean isApproved;

    private boolean isOnline;
    private String presenceStatus; // "ONLINE", "OFFLINE"
    private String availabilityStatus; // "AVAILABLE", "ON_TRIP", "UNAVAILABLE_UNAPPROVED", "UNAVAILABLE_SUSPENDED", "OFFLINE"

    private Long currentRideId;
    private String currentRidePickup;
    private String currentRideDropoff;
    private String currentRideStatus;

    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime lastLocationUpdatedAt;

    private Double latitude;
    private Double longitude;
    private Double heading;
    private Double speed;

    private boolean isLocationStale;
    private boolean locationSharingEnabled;
    private String deviceInfo;
}
