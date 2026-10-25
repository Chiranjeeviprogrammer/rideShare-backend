package com.rideshare.backend.dto;

import lombok.Data;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

@Data
public class DriverApplicationRequest {
    // Step 1: Personal
    private String legalName;
    private String contactNumber;
    private String address;
    private String city;
    private String emergencyContact;

    // Step 2: License
    private String licenseNumber;
    private String licenseState;
    private String licenseExpiryDate;
    private String licenseFrontUrl;
    private String licenseBackUrl;

    // Step 3: Vehicle
    private String vehicleType;
    private String vehicleMake;
    private String vehicleModel;
    private String vehicleYear;
    private String registrationNumber;
    private String vehicleColor;
    private Integer seatCapacity;
    private String rcDocumentUrl;
    private String insuranceNumber;
    private String insuranceExpiryDate;
    private String insuranceDocumentUrl;

    // Draft or final submission
    private boolean isDraft = false;

    private static final DateTimeFormatter FLEXIBLE_FORMATTER = new DateTimeFormatterBuilder()
            .optionalStart()
                .appendValue(ChronoField.YEAR, 4)
                .appendLiteral('-')
                .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
                .appendLiteral('-')
                .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .optionalEnd()
            .optionalStart()
                .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
                .appendLiteral('/')
                .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
                .appendLiteral('/')
                .appendValue(ChronoField.YEAR, 4)
            .optionalEnd()
            .optionalStart()
                .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
                .appendLiteral('-')
                .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
                .appendLiteral('-')
                .appendValue(ChronoField.YEAR, 4)
            .optionalEnd()
            .optionalStart()
                .appendValue(ChronoField.YEAR, 4)
                .appendLiteral('/')
                .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
                .appendLiteral('/')
                .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .optionalEnd()
            .toFormatter();

    public static LocalDate parseDate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        text = text.trim();
        if (text.contains("T")) {
            try {
                return java.time.OffsetDateTime.parse(text).toLocalDate();
            } catch (Exception ignored) {
                try {
                    return java.time.LocalDateTime.parse(text).toLocalDate();
                } catch (Exception ignored2) {}
            }
        }
        try {
            return LocalDate.parse(text, FLEXIBLE_FORMATTER);
        } catch (Exception e) {
            try {
                return LocalDate.parse(text);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    public LocalDate getLicenseExpiryDate() {
        return parseDate(this.licenseExpiryDate);
    }

    public String getRawLicenseExpiryDate() {
        return this.licenseExpiryDate;
    }

    public void setLicenseExpiryDate(LocalDate date) {
        this.licenseExpiryDate = date != null ? date.toString() : null;
    }

    public void setLicenseExpiryDate(String dateStr) {
        this.licenseExpiryDate = dateStr;
    }

    public LocalDate getInsuranceExpiryDate() {
        return parseDate(this.insuranceExpiryDate);
    }

    public String getRawInsuranceExpiryDate() {
        return this.insuranceExpiryDate;
    }

    public void setInsuranceExpiryDate(LocalDate date) {
        this.insuranceExpiryDate = date != null ? date.toString() : null;
    }

    public void setInsuranceExpiryDate(String dateStr) {
        this.insuranceExpiryDate = dateStr;
    }
}
