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
@Table(name = "invoices", indexes = {
    @Index(name = "idx_invoices_invoice_number", columnList = "invoice_number"),
    @Index(name = "idx_invoices_booking_id", columnList = "booking_id"),
    @Index(name = "idx_invoices_passenger_id", columnList = "passenger_id"),
    @Index(name = "idx_invoices_driver_id", columnList = "driver_id"),
    @Index(name = "idx_invoices_status", columnList = "status"),
    @Index(name = "idx_invoices_invoice_date", columnList = "invoice_date")
})
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 100)
    private String invoiceNumber;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id")
    private User passenger;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private User driver;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ride_id")
    private Ride ride;

    public Long getBookingId() { return booking != null ? booking.getId() : null; }
    public Long getPassengerId() { return passenger != null ? passenger.getId() : null; }
    public Long getDriverId() { return driver != null ? driver.getId() : null; }
    public Long getRideId() { return ride != null ? ride.getId() : null; }

    @Builder.Default
    @Column(name = "invoice_date", nullable = false)
    private LocalDateTime invoiceDate = LocalDateTime.now();

    @Column(name = "place_of_supply", nullable = false)
    private String placeOfSupply;

    @Builder.Default
    @Column(name = "place_of_supply_state_code")
    private String placeOfSupplyStateCode = "29";

    @Builder.Default
    @Column(name = "is_interstate", nullable = false)
    private Boolean isInterstate = false;

    // Supplier Details
    @Column(name = "supplier_legal_name", nullable = false)
    private String supplierLegalName;

    @Column(name = "supplier_trade_name")
    private String supplierTradeName;

    @Column(name = "supplier_gstin", nullable = false, length = 32)
    private String supplierGstin;

    @Column(name = "supplier_address", nullable = false, columnDefinition = "TEXT")
    private String supplierAddress;

    @Column(name = "supplier_state", nullable = false)
    private String supplierState;

    @Column(name = "supplier_state_code", nullable = false, length = 10)
    private String supplierStateCode;

    // Customer Details
    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_email", nullable = false)
    private String customerEmail;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "customer_address", columnDefinition = "TEXT")
    private String customerAddress;

    @Column(name = "customer_state")
    private String customerState;

    @Column(name = "customer_gstin", length = 32)
    private String customerGstin;

    // Trip Details
    @Column(name = "trip_pickup", columnDefinition = "TEXT")
    private String tripPickup;

    @Column(name = "trip_dropoff", columnDefinition = "TEXT")
    private String tripDropoff;

    @Builder.Default
    @Column(name = "trip_seats")
    private Integer tripSeats = 1;

    @Builder.Default
    @Column(name = "hsn_sac_code", nullable = false)
    private String hsnSacCode = "996412";

    // Amounts
    @Builder.Default
    @Column(name = "base_amount", nullable = false)
    private Double baseAmount = 0.0;

    @Builder.Default
    @Column(name = "platform_fee_amount", nullable = false)
    private Double platformFeeAmount = 0.0;

    @Builder.Default
    @Column(name = "discount_amount", nullable = false)
    private Double discountAmount = 0.0;

    @Builder.Default
    @Column(name = "taxable_amount", nullable = false)
    private Double taxableAmount = 0.0;

    @Builder.Default
    @Column(name = "gst_rate", nullable = false)
    private Double gstRate = 0.0;

    @Builder.Default
    @Column(name = "cgst_rate", nullable = false)
    private Double cgstRate = 0.0;

    @Builder.Default
    @Column(name = "cgst_amount", nullable = false)
    private Double cgstAmount = 0.0;

    @Builder.Default
    @Column(name = "sgst_rate", nullable = false)
    private Double sgstRate = 0.0;

    @Builder.Default
    @Column(name = "sgst_amount", nullable = false)
    private Double sgstAmount = 0.0;

    @Builder.Default
    @Column(name = "igst_rate", nullable = false)
    private Double igstRate = 0.0;

    @Builder.Default
    @Column(name = "igst_amount", nullable = false)
    private Double igstAmount = 0.0;

    @Builder.Default
    @Column(name = "total_tax_amount", nullable = false)
    private Double totalTaxAmount = 0.0;

    @Builder.Default
    @Column(name = "total_amount", nullable = false)
    private Double totalAmount = 0.0;

    @Builder.Default
    @Column(name = "driver_amount", nullable = false)
    private Double driverAmount = 0.0;

    // Payment & Status
    @Builder.Default
    @Column(name = "payment_method")
    private String paymentMethod = "ONLINE";

    @Builder.Default
    @Column(name = "payment_status")
    private String paymentStatus = "PAID";

    @Column(name = "payment_reference")
    private String paymentReference;

    @Builder.Default
    @Column(name = "status", nullable = false)
    private String status = "ISSUED"; // ISSUED, CANCELLED, REFUNDED, CREDIT_NOTE_ISSUED

    @Column(name = "invoice_footer", columnDefinition = "TEXT")
    private String invoiceFooter;

    @Builder.Default
    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.invoiceDate == null) this.invoiceDate = LocalDateTime.now();
    }
}
