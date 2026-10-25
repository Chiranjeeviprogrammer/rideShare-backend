package com.rideshare.backend.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Data
@Entity
@Table(name = "driver_locations")
public class DriverLocation {
    @Id
    private Long driverId;
    
    private Double latitude;
    private Double longitude;
}
