package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "recurring_ride_schedules")
public class RecurringRideSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "driver_id", nullable = false)
    private User driver;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    private String destination;

    private Double originLatitude;
    private Double originLongitude;
    private Double destinationLatitude;
    private Double destinationLongitude;

    /**
     * Comma-separated uppercase days of the week, e.g. "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY"
     */
    @Column(name = "days_of_week", nullable = false)
    private String daysOfWeek;

    /**
     * Departure time in 24-hr format "HH:mm", e.g. "08:30" or "17:45"
     */
    @Column(name = "departure_time", nullable = false, length = 10)
    private String departureTime;

    @Column(name = "timezone", length = 50)
    @Builder.Default
    private String timezone = "Asia/Kolkata";

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Builder.Default
    @Column(name = "available_seats", nullable = false)
    private Integer availableSeats = 3;

    @Builder.Default
    @Column(name = "total_seats", nullable = false)
    private Integer totalSeats = 3;

    @Builder.Default
    @Column(name = "fare_per_seat", nullable = false)
    private Double farePerSeat = 50.0;

    /**
     * Schedule lifecycle status: ACTIVE, PAUSED, CANCELLED
     */
    @Builder.Default
    @Column(nullable = false, length = 32)
    private String status = "ACTIVE";

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.updatedAt == null) this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = "ACTIVE";
        if (this.timezone == null) this.timezone = "Asia/Kolkata";
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
