package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rides", indexes = {
    @Index(name = "idx_rides_status_dept", columnList = "status, departureTime"),
    @Index(name = "idx_rides_driver_id", columnList = "driver_id"),
    @Index(name = "idx_rides_rider_id", columnList = "rider_id"),
    @Index(name = "idx_rides_status", columnList = "status")
})
public class Ride {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "rider_id", nullable = true)
    private User rider;

    @ManyToOne
    @JoinColumn(name = "driver_id")
    private User driver;

    private String pickupLocation;
    private String dropoffLocation;
    private Double pickupLatitude;
    private Double pickupLongitude;
    private Double dropoffLatitude;
    private Double dropoffLongitude;
    private String status; // PUBLISHED, FULL, IN_PROGRESS, COMPLETED, CANCELLED, EXPIRED
    private Double fare;
    private LocalDateTime requestedAt;

    private Integer availableSeats;
    private Integer totalSeats;
    private LocalDateTime departureTime;

    @Column(name = "recurring_schedule_id")
    private Long recurringScheduleId;

    private LocalDateTime completedAt;
    private String completedBy;
}
