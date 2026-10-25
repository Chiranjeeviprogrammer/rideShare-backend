package com.rideshare.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverHeartbeatRequest {
    private Double latitude;
    private Double longitude;
    private Double heading;
    private Double speed;
    private Double accuracy;
    private Boolean locationSharingEnabled;
    private String deviceInfo;
    private String status; // "ONLINE" or "OFFLINE"
}
