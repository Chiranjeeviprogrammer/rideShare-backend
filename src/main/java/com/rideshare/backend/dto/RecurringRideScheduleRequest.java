package com.rideshare.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringRideScheduleRequest {
    private String origin;
    private String destination;
    private Double originLatitude;
    private Double originLongitude;
    private Double destinationLatitude;
    private Double destinationLongitude;

    /**
     * List of days e.g. ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"]
     */
    private List<String> daysOfWeek;

    /**
     * 24-hr format "HH:mm" e.g. "08:30" or "18:00"
     */
    private String departureTime;

    private String timezone;

    private LocalDate startDate;
    private LocalDate endDate;

    private Integer availableSeats;
    private Integer totalSeats;
    private Double farePerSeat;

    private String notes;
}
