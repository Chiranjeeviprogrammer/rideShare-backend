package com.rideshare.backend.service;

import com.rideshare.backend.dto.DriverApplicationRequest;
import com.rideshare.backend.dto.ReviewApplicationRequest;
import com.rideshare.backend.model.DriverApplication;
import com.rideshare.backend.model.User;
import com.rideshare.backend.model.Vehicle;
import com.rideshare.backend.repository.DriverApplicationRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.repository.VehicleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DriverService {

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private com.rideshare.backend.repository.RideRepository rideRepository;

    @Autowired
    private com.rideshare.backend.repository.BookingRepository bookingRepository;

    @Autowired
    private com.rideshare.backend.repository.WalletRepository walletRepository;

    @Autowired
    private CommissionService commissionService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private PayoutService payoutService;

    public ResponseEntity<?> getDriverEarningsSummary(User driver) {
        if (!"DRIVER".equals(driver.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only approved drivers have access to earnings and settlements."));
        }

        List<com.rideshare.backend.model.Ride> allDriverRides = rideRepository.findByDriverId(driver.getId());
        double grossContribution = 0.0;
        double platformFees = 0.0;
        double cashCollectedByDriver = 0.0;
        double onlineCollectedByPlatform = 0.0;
        int completedRidesCount = 0;
        int totalBookingsCount = 0;

        List<Map<String, Object>> rideBreakdowns = new java.util.ArrayList<>();

        for (com.rideshare.backend.model.Ride ride : allDriverRides) {
            boolean isCompleted = "COMPLETED".equals(ride.getStatus());
            if (isCompleted) {
                completedRidesCount++;
            }

            List<com.rideshare.backend.model.Booking> bookings = bookingRepository.findByRideId(ride.getId());
            double rideGross = 0.0;
            double rideCommission = 0.0;
            double rideRiderFee = 0.0;
            double rideNet = 0.0;
            double rideCash = 0.0;
            double rideOnline = 0.0;

            for (com.rideshare.backend.model.Booking b : bookings) {
                if (!"CANCELLED".equals(b.getStatus())) {
                    totalBookingsCount++;
                    double farePerSeat = ride.getFare() != null ? ride.getFare() : 0.0;
                    double passengerRideContribution = b.getBaseFare() > 0 ? b.getBaseFare() : (farePerSeat * b.getSeats());
                    double comm = b.getDriverCommissionAmount();
                    double riderFee = b.getRiderPlatformFee();
                    double net = b.getDriverNetEarnings() > 0 ? b.getDriverNetEarnings() : Math.max(0.0, passengerRideContribution - comm);

                    rideGross += passengerRideContribution;
                    rideCommission += comm;
                    rideRiderFee += riderFee;
                    rideNet += net;

                    if ("PAID".equals(b.getPaymentStatus())) {
                        if ("CASH".equals(b.getPaymentMethod()) || (b.getTransactionReference() != null && b.getTransactionReference().startsWith("CASH_COLLECTED"))) {
                            rideCash += b.getTotalAmount();
                        } else {
                            rideOnline += b.getTotalAmount();
                        }
                    }
                }
            }

            if (isCompleted) {
                grossContribution += rideGross;
                platformFees += rideCommission;
                cashCollectedByDriver += rideCash;
                onlineCollectedByPlatform += rideOnline;
            }

            Map<String, Object> rItem = new HashMap<>();
            rItem.put("rideId", ride.getId());
            rItem.put("pickup", ride.getPickupLocation());
            rItem.put("dropoff", ride.getDropoffLocation());
            rItem.put("status", ride.getStatus());
            rItem.put("departureTime", ride.getDepartureTime());
            rItem.put("completedAt", ride.getCompletedAt());
            rItem.put("grossContribution", rideGross);
            rItem.put("driverCommission", rideCommission);
            rItem.put("riderPlatformFee", rideRiderFee);
            rItem.put("platformFee", rideCommission);
            rItem.put("netEarnings", rideNet);
            rItem.put("totalBookings", bookings.size());
            rideBreakdowns.add(rItem);
        }

        // Net driver earnings for completed rides = Gross ride contribution minus driver commission
        double netEarnings = Math.max(0.0, grossContribution - platformFees);
        double netPayableToDriver = Math.max(0.0, onlineCollectedByPlatform - platformFees);
        double platformOwedAmount = Math.max(0.0, platformFees - onlineCollectedByPlatform);

        Optional<com.rideshare.backend.model.Wallet> walletOpt = walletRepository.findByUserId(driver.getId());
        double walletBalance = walletOpt.map(com.rideshare.backend.model.Wallet::getBalance).orElse(0.0);

        // Fetch official commission ledger summary
        Map<String, Object> commSummary = commissionService.getDriverCommissionSummary(driver);

        Map<String, Object> summary = new HashMap<>();
        summary.put("driverId", driver.getId());
        summary.put("driverName", driver.getName());
        summary.put("completedRidesCount", completedRidesCount);
        summary.put("totalBookingsCount", totalBookingsCount);
        summary.put("grossRideContribution", grossContribution);
        summary.put("platformFees", platformFees);
        summary.put("netEarnings", netEarnings);
        summary.put("cashCollectedByDriver", cashCollectedByDriver);
        summary.put("success", true);
        summary.put("onlineCollectedByPlatform", onlineCollectedByPlatform);
        summary.put("netPayableToDriver", netPayableToDriver);
        summary.put("moneyOwedToPlatform", platformOwedAmount);
        summary.put("walletBalance", walletBalance);
        summary.put("rideBreakdowns", rideBreakdowns);

        // Explicit commission ledger properties
        summary.put("totalCommissionCharged", commSummary.get("totalCommissionCharged"));
        summary.put("settledCommission", commSummary.get("settledCommission"));
        summary.put("outstandingCommission", commSummary.get("outstandingCommission"));
        summary.put("outstandingThreshold", commSummary.get("outstandingThreshold"));
        summary.put("isPublishRestricted", commSummary.get("isPublishRestricted"));
        summary.put("commissionHistory", commSummary.get("history"));

        // Live dynamic payout & earnings metrics (Section 17 & 18)
        Map<String, Object> payoutBreakdown = payoutService.getDriverEarningsBreakdown(driver);
        summary.put("todayEarnings", payoutBreakdown.get("todayEarnings"));
        summary.put("weeklyEarnings", payoutBreakdown.get("weeklyEarnings"));
        summary.put("totalEarnings", payoutBreakdown.get("totalEarnings"));
        summary.put("pendingEarnings", payoutBreakdown.get("pendingEarnings"));
        summary.put("payoutEligible", payoutBreakdown.get("payoutEligible"));
        summary.put("paidEarnings", payoutBreakdown.get("paidEarnings"));
        summary.put("completedRides", payoutBreakdown.get("completedRides"));

        return ResponseEntity.ok(summary);
    }

    /**
     * Get the authenticated user's current driver application or NOT_APPLIED status.
     */
    public ResponseEntity<?> getMyApplication(User user) {
        Optional<DriverApplication> appOpt = driverApplicationRepository.findByUserId(user.getId());
        if (appOpt.isEmpty()) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "NOT_APPLIED");
            resp.put("role", user.getRole());
            resp.put("isApprovedDriver", "DRIVER".equals(user.getRole()));
            return ResponseEntity.ok(resp);
        }

        DriverApplication app = appOpt.get();
        Map<String, Object> resp = new HashMap<>();
        resp.put("id", app.getId());
        resp.put("status", app.getStatus());
        resp.put("role", user.getRole());
        resp.put("isApprovedDriver", "DRIVER".equals(user.getRole()));
        resp.put("legalName", app.getLegalName() != null ? app.getLegalName() : "");
        resp.put("contactNumber", app.getContactNumber() != null ? app.getContactNumber() : "");
        resp.put("city", app.getCity() != null ? app.getCity() : "");
        resp.put("licenseNumber", app.getLicenseNumber() != null ? app.getLicenseNumber() : "");
        resp.put("licenseState", app.getLicenseState() != null ? app.getLicenseState() : "");
        resp.put("licenseExpiryDate", app.getLicenseExpiryDate() != null ? app.getLicenseExpiryDate().toString() : "");
        resp.put("vehicleMake", app.getVehicleMake() != null ? app.getVehicleMake() : "");
        resp.put("vehicleModel", app.getVehicleModel() != null ? app.getVehicleModel() : "");
        resp.put("registrationNumber", app.getRegistrationNumber() != null ? app.getRegistrationNumber() : "");
        resp.put("vehicleColor", app.getVehicleColor() != null ? app.getVehicleColor() : "");
        resp.put("seatCapacity", app.getSeatCapacity() != null ? app.getSeatCapacity() : 4);
        resp.put("reviewFeedback", app.getReviewFeedback() != null ? app.getReviewFeedback() : "");
        resp.put("submittedAt", app.getSubmittedAt() != null ? app.getSubmittedAt().toString() : "");

        return ResponseEntity.ok(resp);
    }

    /**
     * Submit or update a driver application.
     */
    @Transactional
    public ResponseEntity<?> submitApplication(User user, DriverApplicationRequest request) {
        Optional<DriverApplication> existingOpt = driverApplicationRepository.findByUserId(user.getId());
        DriverApplication application;

        if (existingOpt.isPresent()) {
            application = existingOpt.get();
            // If already approved, prevent reapplication unless suspended
            if ("APPROVED".equals(application.getStatus())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "You are already an approved driver."));
            }
            if ("PENDING_REVIEW".equals(application.getStatus()) && !request.isDraft()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("message", "Your application is already submitted and under review."));
            }
        } else {
            application = new DriverApplication();
            application.setUser(user);
        }

        // Validate licence expiration on final submission
        if (!request.isDraft()) {
            if (request.getRawLicenseExpiryDate() != null && !request.getRawLicenseExpiryDate().isBlank() && request.getLicenseExpiryDate() == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "Invalid licence expiry date format. Please use YYYY-MM-DD."));
            }
            if (request.getLicenseExpiryDate() != null && request.getLicenseExpiryDate().isBefore(LocalDate.now())) {
                return ResponseEntity.badRequest().body(Map.of("message", "Driving licence has expired. Please provide a valid licence."));
            }
            if (request.getLicenseNumber() == null || request.getLicenseNumber().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Licence number is required."));
            }
            if (request.getRegistrationNumber() == null || request.getRegistrationNumber().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "Vehicle registration number is required."));
            }
        }

        // Populate fields
        application.setLegalName(request.getLegalName() != null ? request.getLegalName() : user.getName());
        application.setContactNumber(request.getContactNumber() != null ? request.getContactNumber() : user.getPhoneNumber());
        application.setAddress(request.getAddress());
        application.setCity(request.getCity());
        application.setEmergencyContact(request.getEmergencyContact());

        application.setLicenseNumber(request.getLicenseNumber());
        application.setLicenseState(request.getLicenseState());
        application.setLicenseExpiryDate(request.getLicenseExpiryDate());
        application.setLicenseFrontUrl(request.getLicenseFrontUrl());
        application.setLicenseBackUrl(request.getLicenseBackUrl());

        application.setVehicleType(request.getVehicleType() != null ? request.getVehicleType() : "Sedan");
        application.setVehicleMake(request.getVehicleMake());
        application.setVehicleModel(request.getVehicleModel());
        application.setVehicleYear(request.getVehicleYear());
        application.setRegistrationNumber(request.getRegistrationNumber());
        application.setVehicleColor(request.getVehicleColor());
        application.setSeatCapacity(request.getSeatCapacity() != null ? request.getSeatCapacity() : 4);
        application.setRcDocumentUrl(request.getRcDocumentUrl());
        application.setInsuranceNumber(request.getInsuranceNumber());
        application.setInsuranceExpiryDate(request.getInsuranceExpiryDate());
        application.setInsuranceDocumentUrl(request.getInsuranceDocumentUrl());

        if (request.isDraft()) {
            application.setStatus("DRAFT");
        } else {
            application.setStatus("PENDING_REVIEW");
            application.setSubmittedAt(LocalDateTime.now());
            application.setReviewFeedback(null); // Clear previous rejection notes
        }

        DriverApplication saved = driverApplicationRepository.save(application);

        notificationService.sendNotification(
                user,
                "Driver Application Update",
                request.isDraft() ? "Draft saved successfully." : "Your driver application has been submitted for review."
        );

        return ResponseEntity.ok(Map.of(
                "applicationId", saved.getId(),
                "status", saved.getStatus(),
                "message", request.isDraft() ? "Draft saved successfully." : "Application submitted for review."
        ));
    }

    /**
     * Admin review endpoint: Approve, Reject, Request info, or Suspend.
     */
    @Transactional
    public ResponseEntity<?> reviewApplication(Long applicationId, User reviewer, ReviewApplicationRequest request) {
        Optional<DriverApplication> appOpt = driverApplicationRepository.findById(applicationId);
        if (appOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Application not found."));
        }

        DriverApplication app = appOpt.get();
        String newStatus = request.getStatus();

        if (newStatus == null || (!newStatus.equals("APPROVED") && !newStatus.equals("REJECTED")
                && !newStatus.equals("ADDITIONAL_INFORMATION_REQUIRED") && !newStatus.equals("SUSPENDED"))) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid review status."));
        }

        app.setStatus(newStatus);
        app.setReviewedAt(LocalDateTime.now());
        app.setReviewedBy(reviewer);
        app.setReviewFeedback(request.getReviewFeedback());

        User applicant = app.getUser();

        if ("APPROVED".equals(newStatus)) {
            applicant.setRole("DRIVER");
            userRepository.save(applicant);

            // Create Vehicle entity if vehicle details are present
            if (app.getRegistrationNumber() != null && !app.getRegistrationNumber().trim().isEmpty()) {
                Vehicle vehicle = new Vehicle();
                vehicle.setDriver(applicant);
                vehicle.setMake(app.getVehicleMake() != null ? app.getVehicleMake() : "Standard");
                vehicle.setModel(app.getVehicleModel() != null ? app.getVehicleModel() : "Sedan");
                vehicle.setYear(app.getVehicleYear() != null ? app.getVehicleYear() : "2022");
                vehicle.setLicensePlate(app.getRegistrationNumber());
                vehicle.setColor(app.getVehicleColor() != null ? app.getVehicleColor() : "Black");
                vehicleRepository.save(vehicle);
            }
        } else if ("SUSPENDED".equals(newStatus) || "REJECTED".equals(newStatus)) {
            applicant.setRole("RIDER");
            userRepository.save(applicant);
        }

        driverApplicationRepository.save(app);

        notificationService.sendNotification(
                applicant,
                "Driver Application Status",
                "Your application status is now: " + newStatus
        );

        if (applicant.getEmail() != null) {
            emailService.sendDriverApplicationReviewedEmail(
                    applicant.getEmail(),
                    applicant.getName(),
                    newStatus,
                    request.getReviewFeedback()
            );
        }

        return ResponseEntity.ok(Map.of(
                "applicationId", app.getId(),
                "newStatus", newStatus,
                "userRole", applicant.getRole(),
                "message", "Application status updated successfully."
        ));
    }

    /**
     * List applications for administrative review.
     */
    public List<DriverApplication> getApplicationsByStatus(String status) {
        if (status != null && !status.trim().isEmpty()) {
            return driverApplicationRepository.findByStatus(status);
        }
        return driverApplicationRepository.findAll();
    }
}
