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
@Table(name = "driver_presence", indexes = {
    @Index(name = "idx_driver_presence_online_heartbeat", columnList = "is_online, last_heartbeat_at"),
    @Index(name = "idx_driver_presence_ride", columnList = "current_ride_id")
})
public class DriverPresence {

    @Id
    @Column(name = "driver_id")
    private Long driverId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id", insertable = false, updatable = false)
    private User driver;

    @Column(name = "last_heartbeat_at", nullable = false)
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "last_status", nullable = false, length = 32)
    @Builder.Default
    private String lastStatus = "OFFLINE"; // ONLINE, OFFLINE

    @Column(name = "is_online", nullable = false)
    @Builder.Default
    private Boolean isOnline = false;

    @Column(name = "current_ride_id")
    private Long currentRideId;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "heading")
    private Double heading;

    @Column(name = "speed")
    private Double speed;

    @Column(name = "accuracy")
    private Double accuracy;

    @Column(name = "location_updated_at")
    private LocalDateTime locationUpdatedAt;

    @Column(name = "location_sharing_enabled", nullable = false)
    @Builder.Default
    private Boolean locationSharingEnabled = true;

    @Column(name = "device_info")
    private String deviceInfo;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void onUpdate() {
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
        if (this.lastHeartbeatAt == null) {
            this.lastHeartbeatAt = LocalDateTime.now();
        }
        if (this.locationSharingEnabled == null) {
            this.locationSharingEnabled = true;
        }
        if (this.isOnline == null) {
            this.isOnline = false;
        }
        if (this.lastStatus == null) {
            this.lastStatus = "OFFLINE";
        }
    }
}
