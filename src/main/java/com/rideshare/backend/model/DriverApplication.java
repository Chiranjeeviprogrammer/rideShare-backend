package com.rideshare.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "driver_applications")
public class DriverApplication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Step 1: Personal Information
    private String legalName;
    private String contactNumber;
    private String address;
    private String city;
    private String emergencyContact;

    // Step 2: Driving Licence
    private String licenseNumber;
    private String licenseState;
    private LocalDate licenseExpiryDate;
    private String licenseFrontUrl;
    private String licenseBackUrl;

    // Step 3: Vehicle Information
    private String vehicleType;
    private String vehicleMake;
    private String vehicleModel;
    private String vehicleYear;
    private String registrationNumber;
    private String vehicleColor;
    private Integer seatCapacity;
    private String rcDocumentUrl;
    private String insuranceNumber;
    private LocalDate insuranceExpiryDate;
    private String insuranceDocumentUrl;
    private String permitDocumentUrl;
    private LocalDate permitExpiryDate;
    private String fitnessDocumentUrl;
    private LocalDate fitnessExpiryDate;

    // Status: NOT_APPLIED, DRAFT, PENDING_REVIEW, ADDITIONAL_INFORMATION_REQUIRED, APPROVED, REJECTED, SUSPENDED
    @Column(nullable = false)
    private String status = "PENDING_REVIEW";

    @Column(length = 1000)
    private String reviewFeedback;

    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;

    @ManyToOne
    @JoinColumn(name = "reviewed_by_id")
    private User reviewedBy;

    @PrePersist
    public void prePersist() {
        if (submittedAt == null) {
            submittedAt = LocalDateTime.now();
        }
    }
}
