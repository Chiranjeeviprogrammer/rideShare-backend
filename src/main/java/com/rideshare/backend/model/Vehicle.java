package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.Data;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Data
@Entity
@Table(name = "vehicles")
public class Vehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String make;
    private String model;
    
    @Column(name = "manufacture_year")
    private String year;
    
    private String licensePlate;
    private String color;

    @Column(name = "image_url")
    private String imageUrl;

    @ManyToOne
    @JoinColumn(name = "driver_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User driver;
}
