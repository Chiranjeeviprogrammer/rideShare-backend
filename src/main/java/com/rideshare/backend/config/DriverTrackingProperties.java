package com.rideshare.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rideshare.driver-tracking")
public class DriverTrackingProperties {
    /**
     * Recommended heartbeat interval expected from active driver mobile clients (in seconds).
     */
    private int heartbeatIntervalSeconds = 15;

    /**
     * Timeout after which a driver without a heartbeat is automatically marked offline (in seconds).
     */
    private int onlineTimeoutSeconds = 45;

    /**
     * Threshold after which a driver's location is considered stale (in seconds).
     */
    private int locationFreshnessThresholdSeconds = 90;
}
