package com.rideshare.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rideshare.commission")
public class CommissionProperties {

    /**
     * Commission percentage applied to gross fare contribution (e.g., 15.0 for 15%).
     */
    private double ratePercent = 15.0;

    /**
     * Fixed per-booking fee component (e.g., 20.0).
     */
    private double fixedFee = 20.0;

    /**
     * Maximum unpaid commission permitted before blocking driver from publishing new rides.
     */
    private double outstandingThreshold = 500.0;

    /**
     * Whether to enforce the blocking threshold when driver publishes new rides.
     */
    private boolean enforcePublishThreshold = true;

    /**
     * PERCENTAGE, FIXED, or PERCENT_PLUS_FIXED.
     */
    private String calculationType = "PERCENT_PLUS_FIXED";
}
