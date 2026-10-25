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
public class DriverMonitoringMetricsDto {
    private long totalRegisteredDrivers;
    private long onlineDrivers;
    private long availableDrivers;
    private long driversOnTrip;
    private long offlineDrivers;
    private long driversWithStaleLocation;
    private LocalDateTime lastUpdated;
    private int onlineTimeoutSeconds;
    private int locationFreshnessThresholdSeconds;
}
