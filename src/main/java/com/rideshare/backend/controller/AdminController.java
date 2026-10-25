package com.rideshare.backend.controller;

import com.rideshare.backend.dto.ReviewApplicationRequest;
import com.rideshare.backend.model.*;
import com.rideshare.backend.repository.*;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.AuditLogService;
import com.rideshare.backend.service.DriverService;
import com.rideshare.backend.service.EmailService;
import com.rideshare.backend.service.PaymentService;
import com.rideshare.backend.service.RazorpayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.rideshare.backend.service.SupportPermissionService;
import com.rideshare.backend.service.DatabaseCleanupAndSeedService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private CommissionLedgerRepository commissionLedgerRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private com.rideshare.backend.repository.DriverPayoutRepository driverPayoutRepository;

    @Autowired
    private com.rideshare.backend.repository.PaymentRefundRepository paymentRefundRepository;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private PaymentService paymentService;

    @Autowired
    private RazorpayService razorpayService;

    @Autowired
    private SupportTicketRepository supportTicketRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private PlatformSettingsRepository platformSettingsRepository;

    @Autowired
    private DriverService driverService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private FaqItemRepository faqItemRepository;

    @Autowired
    private RecurringRideScheduleRepository recurringRideScheduleRepository;

    @Autowired
    private DriverPresenceRepository driverPresenceRepository;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private SupportPermissionService supportPermissionService;

    @Autowired
    private SupportPermissionRepository supportPermissionRepository;

    @Autowired
    private DatabaseCleanupAndSeedService databaseCleanupAndSeedService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CancellationPolicyRuleRepository cancellationPolicyRuleRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private DriverPayoutProfileRepository driverPayoutProfileRepository;

    @Autowired
    private com.rideshare.backend.service.UserDocumentService userDocumentService;

    @Autowired(required = false)
    private com.rideshare.backend.service.RedisCacheService redisCacheService;

    public static class StaffAuthResult {
        public final User user;
        public final ResponseEntity<?> errorResponse;

        public StaffAuthResult(User user) {
            this.user = user;
            this.errorResponse = null;
        }

        public StaffAuthResult(ResponseEntity<?> errorResponse) {
            this.user = null;
            this.errorResponse = errorResponse;
        }

        public boolean isAuthorized() {
            return user != null;
        }
    }

    private StaffAuthResult authenticateStaff(String authHeader, String requiredPermission) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return new StaffAuthResult(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication token required.")));
        }
        String token = authHeader.substring(7);
        try {
            String email = jwtUtil.extractEmail(token);
            User user = userRepository.findByEmail(email);
            if (user == null) {
                return new StaffAuthResult(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found.")));
            }
            if ("SUSPENDED".equalsIgnoreCase(user.getStatus())) {
                return new StaffAuthResult(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Account is suspended.")));
            }
            if ("ADMIN".equalsIgnoreCase(user.getRole())) {
                return new StaffAuthResult(user);
            }
            if ("SUPPORT".equalsIgnoreCase(user.getRole())) {
                if (requiredPermission == null || supportPermissionService.hasPermission(user, requiredPermission)) {
                    return new StaffAuthResult(user);
                }
                return new StaffAuthResult(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "message", "Access denied: Missing required permission " + requiredPermission,
                        "requiredPermission", requiredPermission
                )));
            }
            return new StaffAuthResult(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative or Support access required.")));
        } catch (Exception e) {
            return new StaffAuthResult(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid or expired token.")));
        }
    }

    private StaffAuthResult authenticateAdminOnly(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return new StaffAuthResult(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Authentication token required.")));
        }
        String token = authHeader.substring(7);
        try {
            String email = jwtUtil.extractEmail(token);
            User user = userRepository.findByEmail(email);
            if (user == null) {
                return new StaffAuthResult(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "User not found.")));
            }
            if ("SUSPENDED".equalsIgnoreCase(user.getStatus())) {
                return new StaffAuthResult(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Account is suspended.")));
            }
            if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
                return new StaffAuthResult(ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Administrative access required. Only primary Admin can perform this operation.")));
            }
            return new StaffAuthResult(user);
        } catch (Exception e) {
            return new StaffAuthResult(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid or expired token.")));
        }
    }

    private User getAuthenticatedAdmin(String authHeader) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        return auth.isAuthorized() ? auth.user : null;
    }

    private DriverApplication sanitizeDriverApplication(DriverApplication app, boolean canViewDocs) {
        if (app == null) return null;
        if (canViewDocs) return app;
        DriverApplication sanitized = new DriverApplication();
        sanitized.setId(app.getId());
        sanitized.setUser(app.getUser());
        sanitized.setLegalName(app.getLegalName());
        sanitized.setContactNumber(app.getContactNumber());
        sanitized.setEmergencyContact(app.getEmergencyContact());
        sanitized.setAddress(app.getAddress());
        sanitized.setCity(app.getCity());
        sanitized.setVehicleMake(app.getVehicleMake());
        sanitized.setVehicleModel(app.getVehicleModel());
        sanitized.setVehicleYear(app.getVehicleYear());
        sanitized.setVehicleColor(app.getVehicleColor());
        sanitized.setRegistrationNumber(app.getRegistrationNumber());
        sanitized.setVehicleType(app.getVehicleType());
        sanitized.setSeatCapacity(app.getSeatCapacity());
        sanitized.setStatus(app.getStatus());
        sanitized.setSubmittedAt(app.getSubmittedAt());
        sanitized.setReviewedAt(app.getReviewedAt());
        sanitized.setReviewedBy(app.getReviewedBy());
        sanitized.setReviewFeedback(app.getReviewFeedback());
        sanitized.setLicenseNumber(app.getLicenseNumber() != null ? "****" + (app.getLicenseNumber().length() > 4 ? app.getLicenseNumber().substring(app.getLicenseNumber().length() - 4) : "") : null);
        sanitized.setLicenseState(app.getLicenseState());
        sanitized.setLicenseExpiryDate(app.getLicenseExpiryDate());
        sanitized.setLicenseFrontUrl(null);
        sanitized.setLicenseBackUrl(null);
        sanitized.setRcDocumentUrl(null);
        sanitized.setInsuranceNumber(null);
        sanitized.setInsuranceExpiryDate(null);
        sanitized.setInsuranceDocumentUrl(null);
        return sanitized;
    }


    // ==========================================
    // 1. DASHBOARD OVERVIEW
    // ==========================================

    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboardSummary(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_DASHBOARD);
        if (!auth.isAuthorized()) return auth.errorResponse;

        long totalUsers = userRepository.count();
        long totalRiders = userRepository.countByRole("RIDER");
        long totalDrivers = userRepository.countByRole("DRIVER");
        long totalAdmins = userRepository.countByRole("ADMIN");

        long pendingApplications = driverApplicationRepository.countByStatus("PENDING_REVIEW");
        long approvedApplications = driverApplicationRepository.countByStatus("APPROVED");
        long rejectedApplications = driverApplicationRepository.countByStatus("REJECTED");

        long totalRides = rideRepository.count();
        long publishedRides = rideRepository.countByStatus("PUBLISHED");
        long inProgressRides = rideRepository.countByStatus("IN_PROGRESS");
        long completedRides = rideRepository.countByStatus("COMPLETED");
        long cancelledRides = rideRepository.countByStatus("CANCELLED");

        long totalBookings = bookingRepository.count();
        long confirmedBookings = bookingRepository.countByStatus("CONFIRMED");
        long completedBookings = bookingRepository.countByStatus("COMPLETED");
        long cancelledBookings = bookingRepository.countByStatus("CANCELLED");

        long onlinePaymentsCount = bookingRepository.countByPaymentMethod("ONLINE");
        long cashPaymentsCount = bookingRepository.countByPaymentMethod("CASH");

        // Financial aggregations
        double grossBookingValue = bookingRepository.getGrossBookingValue();

        Double charged = commissionLedgerRepository.sumPlatformCommissionCharged();
        Double settled = commissionLedgerRepository.sumPlatformCommissionSettled();
        Double outstanding = commissionLedgerRepository.sumPlatformCommissionOutstanding();
        Double driverEarnings = commissionLedgerRepository.sumDriverNetFromCash();

        double platformCommissionCharged = charged != null ? charged : 0.0;
        double platformCommissionSettled = settled != null ? settled : 0.0;
        double platformCommissionOutstanding = outstanding != null ? outstanding : 0.0;
        double driverNetEarnings = driverEarnings != null ? driverEarnings : 0.0;

        long openTickets = supportTicketRepository.countByStatus("OPEN");
        long inProgressTickets = supportTicketRepository.countByStatus("IN_PROGRESS");

        List<AuditLog> recentAudit = auditLogRepository.findTop20ByOrderByTimestampDesc();
        List<EmailLog> recentEmails = emailLogRepository.findTop20ByOrderBySentAtDesc();

        Map<String, Object> dashboard = new HashMap<>();
        dashboard.put("users", Map.of(
                "total", totalUsers,
                "riders", totalRiders,
                "drivers", totalDrivers,
                "admins", totalAdmins
        ));
        dashboard.put("driverApplications", Map.of(
                "pending", pendingApplications,
                "approved", approvedApplications,
                "rejected", rejectedApplications
        ));
        dashboard.put("rides", Map.of(
                "total", totalRides,
                "published", publishedRides,
                "inProgress", inProgressRides,
                "completed", completedRides,
                "cancelled", cancelledRides
        ));
        dashboard.put("bookings", Map.of(
                "total", totalBookings,
                "confirmed", confirmedBookings,
                "completed", completedBookings,
                "cancelled", cancelledBookings,
                "onlineCount", onlinePaymentsCount,
                "cashCount", cashPaymentsCount,
                "grossBookingValue", Math.round(grossBookingValue * 100.0) / 100.0
        ));
        dashboard.put("finances", Map.of(
                "commissionCharged", Math.round(platformCommissionCharged * 100.0) / 100.0,
                "commissionSettled", Math.round(platformCommissionSettled * 100.0) / 100.0,
                "commissionOutstanding", Math.round(platformCommissionOutstanding * 100.0) / 100.0,
                "driverNetEarnings", Math.round(driverNetEarnings * 100.0) / 100.0
        ));
        dashboard.put("supportTickets", Map.of(
                "open", openTickets,
                "inProgress", inProgressTickets
        ));
        LocalDateTime onlineThreshold = LocalDateTime.now().minusSeconds(120);
        long onlineDriversCount = driverPresenceRepository.countOnlineDrivers(onlineThreshold);
        long totalRecurringSchedules = recurringRideScheduleRepository.count();
        long activeRecurringSchedules = recurringRideScheduleRepository.countByStatus("ACTIVE");

        dashboard.put("recurringSchedules", Map.of(
                "total", totalRecurringSchedules,
                "active", activeRecurringSchedules
        ));
        dashboard.put("driverPresence", Map.of(
                "onlineDrivers", onlineDriversCount
        ));
        dashboard.put("emailHealth", emailService.getDiagnostics());
        dashboard.put("recentAuditLogs", recentAudit);
        dashboard.put("recentEmailLogs", recentEmails);

        return ResponseEntity.ok(dashboard);
    }

    // ==========================================
    // 2. USER MANAGEMENT
    // ==========================================

    @GetMapping("/users")
    public ResponseEntity<?> getUsers(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String verification,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_USERS);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        String cleanRole = (role != null && !role.isBlank() && !role.equalsIgnoreCase("ALL")) ? role.toUpperCase() : null;
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;

        Boolean cleanVerified = null;
        if (verification != null && !verification.isBlank() && !verification.equalsIgnoreCase("ALL")) {
            if ("VERIFIED".equalsIgnoreCase(verification) || "TRUE".equalsIgnoreCase(verification)) {
                cleanVerified = true;
            } else if ("UNVERIFIED".equalsIgnoreCase(verification) || "FALSE".equalsIgnoreCase(verification)) {
                cleanVerified = false;
            }
        }

        String cleanSearch = (search != null && !search.isBlank()) ? search.trim().replaceAll("^#", "") : null;

        Page<User> usersPage = (cleanRole == null && cleanStatus == null && cleanVerified == null && cleanSearch == null)
                ? userRepository.findAll(pageable)
                : userRepository.searchUsers(cleanRole, cleanStatus, cleanVerified, cleanSearch, pageable);

        List<Long> pageUserIds = usersPage.getContent().stream().map(User::getId).collect(Collectors.toList());

        // Batch queries to avoid N+1 queries across table view
        Map<Long, String> driverAppStatusMap = new HashMap<>();
        if (!pageUserIds.isEmpty()) {
            driverApplicationRepository.findByUserIdIn(pageUserIds).forEach(app -> {
                if (app.getUser() != null) {
                    driverAppStatusMap.put(app.getUser().getId(), app.getStatus());
                }
            });
        }

        Map<Long, Long> bookingsCountMap = new HashMap<>();
        if (!pageUserIds.isEmpty()) {
            bookingRepository.findByPassengerIdIn(pageUserIds).forEach(b -> {
                if (b.getPassenger() != null) {
                    Long pid = b.getPassenger().getId();
                    bookingsCountMap.put(pid, bookingsCountMap.getOrDefault(pid, 0L) + 1);
                }
            });
        }

        Map<Long, Long> ridesCountMap = new HashMap<>();
        if (!pageUserIds.isEmpty()) {
            rideRepository.findByDriverIdIn(pageUserIds).forEach(r -> {
                if (r.getDriver() != null) {
                    Long did = r.getDriver().getId();
                    ridesCountMap.put(did, ridesCountMap.getOrDefault(did, 0L) + 1);
                }
            });
        }

        Map<Long, Double> walletsMap = new HashMap<>();
        if (!pageUserIds.isEmpty()) {
            walletRepository.findByUserIdIn(pageUserIds).forEach(w -> {
                if (w.getUser() != null) {
                    walletsMap.put(w.getUser().getId(), w.getBalance());
                }
            });
        }

        // Sanitize sensitive credentials (password hashes, reset tokens)
        List<Map<String, Object>> safeUsers = new ArrayList<>();
        for (User u : usersPage.getContent()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("name", u.getName());
            map.put("email", u.getEmail());
            map.put("phoneNumber", u.getPhoneNumber());
            map.put("role", u.getRole());
            map.put("status", u.getStatus() != null ? u.getStatus() : "ACTIVE");
            map.put("suspensionReason", u.getSuspensionReason());
            map.put("suspendedAt", u.getSuspendedAt());
            map.put("verified", u.getVerified());
            map.put("referralCode", u.getReferralCode());
            map.put("avatarUrl", u.getAvatarUrl());
            map.put("createdAt", u.getCreatedAt());
            map.put("preferredLanguage", u.getPreferredLanguage());
            map.put("homeAddress", u.getHomeAddress());
            map.put("officeAddress", u.getOfficeAddress());
            map.put("workCompany", u.getWorkCompany());
            map.put("walletBalance", walletsMap.getOrDefault(u.getId(), 0.0));
            map.put("totalBookings", bookingsCountMap.getOrDefault(u.getId(), 0L));
            map.put("totalPublishedRides", ridesCountMap.getOrDefault(u.getId(), 0L));
            map.put("driverKycStatus", driverAppStatusMap.get(u.getId()));
            safeUsers.add(map);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("content", safeUsers);
        response.put("totalElements", usersPage.getTotalElements());
        response.put("totalPages", usersPage.getTotalPages());
        response.put("page", usersPage.getNumber());
        response.put("size", usersPage.getSize());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserDetails(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_USERS);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found"));
        User user = userOpt.get();

        Map<String, Object> details = new HashMap<>();
        details.put("id", user.getId());
        details.put("name", user.getName());
        details.put("email", user.getEmail());
        details.put("phoneNumber", user.getPhoneNumber());
        details.put("role", user.getRole());
        details.put("status", user.getStatus());
        details.put("suspensionReason", user.getSuspensionReason());
        details.put("suspendedAt", user.getSuspendedAt());
        details.put("suspendedBy", user.getSuspendedBy());
        details.put("verified", user.getVerified());
        details.put("referralCode", user.getReferralCode());
        details.put("referredBy", user.getReferredBy());
        details.put("avatarUrl", user.getAvatarUrl());
        details.put("createdAt", user.getCreatedAt());

        // Commuter / Language Preferences
        details.put("preferredLanguage", user.getPreferredLanguage());
        details.put("homeAddress", user.getHomeAddress());
        details.put("homeLatitude", user.getHomeLatitude());
        details.put("homeLongitude", user.getHomeLongitude());
        details.put("officeAddress", user.getOfficeAddress());
        details.put("officeLatitude", user.getOfficeLatitude());
        details.put("officeLongitude", user.getOfficeLongitude());
        details.put("workCompany", user.getWorkCompany());

        // Digital Wallet
        details.put("wallet", walletRepository.findByUserId(user.getId()).orElse(null));

        // Associated driver application if exists (redact docs if caller lacks VIEW_DRIVER_DOCS)
        boolean canViewDocs = "ADMIN".equalsIgnoreCase(admin.getRole()) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.VIEW_DRIVER_DOCS);
        Optional<DriverApplication> appOpt = driverApplicationRepository.findByUserId(user.getId());
        details.put("driverApplication", appOpt.map(a -> sanitizeDriverApplication(a, canViewDocs)).orElse(null));
        details.put("uploadedDocuments", canViewDocs ? userDocumentService.getUserDocuments(user.getId(), admin) : Collections.emptyList());

        // Emergency Contacts list
        List<Map<String, Object>> emergencyContacts = new ArrayList<>();
        if (appOpt.isPresent() && appOpt.get().getEmergencyContact() != null && !appOpt.get().getEmergencyContact().isBlank()) {
            Map<String, Object> ec = new HashMap<>();
            ec.put("name", "Emergency Contact");
            ec.put("phone", appOpt.get().getEmergencyContact());
            ec.put("relationship", "Designated Emergency Contact");
            ec.put("source", "Driver KYC Application");
            emergencyContacts.add(ec);
        }
        details.put("emergencyContacts", emergencyContacts);

        // Vehicles
        details.put("vehicles", vehicleRepository.findByDriverId(user.getId()));

        // Rides published or booked
        List<Ride> publishedRides = rideRepository.findByDriverId(user.getId());
        List<Booking> passengerBookings = bookingRepository.findByPassengerIdOrderByBookedAtDesc(user.getId());
        details.put("publishedRides", publishedRides);
        details.put("bookings", passengerBookings);

        // Driver Payout Profile & Payouts (if driver)
        if ("DRIVER".equalsIgnoreCase(user.getRole()) || appOpt.isPresent()) {
            details.put("payoutProfile", driverPayoutProfileRepository.findByDriverId(user.getId()).map(p -> {
                Map<String, Object> pm = new HashMap<>();
                pm.put("id", p.getId());
                pm.put("accountHolderName", p.getAccountHolderName());
                pm.put("bankName", p.getBankName());
                pm.put("accountNumberMasked", p.getAccountNumberMasked());
                pm.put("ifscCode", p.getIfscCode());
                pm.put("verificationStatus", p.getVerificationStatus());
                pm.put("isEligibleForPayout", p.getIsEligibleForPayout());
                return pm;
            }).orElse(null));

            details.put("payouts", driverPayoutRepository.findByDriverIdOrderByCreatedAtDesc(user.getId()));

            Double outstanding = commissionLedgerRepository.getOutstandingCommissionByDriverId(user.getId());
            Double settled = commissionLedgerRepository.getSettledCommissionByDriverId(user.getId());
            Double totalEarnings = commissionLedgerRepository.getTotalDriverEarningsByDriverId(user.getId());
            Double payoutEligible = commissionLedgerRepository.getPayoutEligibleEarningsByDriverId(user.getId());
            details.put("commissionSummary", Map.of(
                "outstanding", outstanding != null ? outstanding : 0.0,
                "settled", settled != null ? settled : 0.0,
                "totalEarnings", totalEarnings != null ? totalEarnings : 0.0,
                "payoutEligible", payoutEligible != null ? payoutEligible : 0.0
            ));
        }

        // Support Tickets opened by or concerning user
        try {
            details.put("supportTickets", supportTicketRepository.searchTickets(null, null, null, user.getEmail(), PageRequest.of(0, 10)).getContent());
        } catch (Exception ignored) {
            details.put("supportTickets", Collections.emptyList());
        }

        // Audit Trail of administrative actions on this user
        try {
            details.put("auditLogs", auditLogRepository.searchAuditLogs(null, "USER", String.valueOf(user.getId()), PageRequest.of(0, 15)).getContent());
        } catch (Exception ignored) {
            details.put("auditLogs", Collections.emptyList());
        }

        // Communication History (Emails sent to user)
        try {
            details.put("emailLogs", emailLogRepository.searchEmailLogs(null, null, user.getEmail(), PageRequest.of(0, 10)).getContent());
        } catch (Exception ignored) {
            details.put("emailLogs", Collections.emptyList());
        }

        return ResponseEntity.ok(details);
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateAdminUserProfile(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found"));
        User user = userOpt.get();

        StringBuilder changes = new StringBuilder();

        if (payload.containsKey("name") && payload.get("name") != null) {
            String newName = payload.get("name").toString().trim();
            if (!newName.isBlank() && !newName.equals(user.getName())) {
                changes.append("name: '").append(user.getName()).append("' -> '").append(newName).append("'; ");
                user.setName(newName);
            }
        }

        if (payload.containsKey("phoneNumber")) {
            String newPhone = payload.get("phoneNumber") != null ? payload.get("phoneNumber").toString().trim() : null;
            if ((newPhone == null && user.getPhoneNumber() != null) || (newPhone != null && !newPhone.equals(user.getPhoneNumber()))) {
                changes.append("phoneNumber: '").append(user.getPhoneNumber()).append("' -> '").append(newPhone).append("'; ");
                user.setPhoneNumber(newPhone);
            }
        }

        if (payload.containsKey("role") && payload.get("role") != null) {
            String newRole = payload.get("role").toString().trim().toUpperCase();
            if (user.getId().equals(admin.getId()) && !newRole.equals("ADMIN")) {
                return ResponseEntity.badRequest().body(Map.of("message", "Administrators cannot demote their own account role."));
            }
            if (List.of("RIDER", "DRIVER", "SUPPORT").contains(newRole) && !newRole.equals(user.getRole())) {
                changes.append("role: '").append(user.getRole()).append("' -> '").append(newRole).append("'; ");
                user.setRole(newRole);
            }
        }

        if (payload.containsKey("preferredLanguage") && payload.get("preferredLanguage") != null) {
            String lang = payload.get("preferredLanguage").toString().trim().toLowerCase();
            if (("en".equals(lang) || "hi".equals(lang)) && !lang.equals(user.getPreferredLanguage())) {
                changes.append("language: '").append(user.getPreferredLanguage()).append("' -> '").append(lang).append("'; ");
                user.setPreferredLanguage(lang);
            }
        }

        if (payload.containsKey("verified") && payload.get("verified") != null) {
            Boolean newVerified = Boolean.valueOf(payload.get("verified").toString());
            if (!newVerified.equals(user.getVerified())) {
                changes.append("verified: ").append(user.getVerified()).append(" -> ").append(newVerified).append("; ");
                user.setVerified(newVerified);
            }
        }

        if (payload.containsKey("homeAddress")) {
            String val = payload.get("homeAddress") != null ? payload.get("homeAddress").toString().trim() : null;
            user.setHomeAddress(val);
        }
        if (payload.containsKey("officeAddress")) {
            String val = payload.get("officeAddress") != null ? payload.get("officeAddress").toString().trim() : null;
            user.setOfficeAddress(val);
        }
        if (payload.containsKey("workCompany")) {
            String val = payload.get("workCompany") != null ? payload.get("workCompany").toString().trim() : null;
            user.setWorkCompany(val);
        }

        userRepository.save(user);

        if (changes.length() > 0) {
            auditLogService.log(
                    admin.getEmail(),
                    "USER_UPDATE",
                    "USER",
                    String.valueOf(user.getId()),
                    "Updated profile fields: " + changes.toString(),
                    clientIp
            );
        }

        return getUserDetails(authHeader, id);
    }

    @PutMapping("/users/{id}/status")
    public ResponseEntity<?> updateUserStatus(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found"));
        User user = userOpt.get();

        if (user.getId().equals(admin.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Administrators cannot suspend their own account."));
        }

        String newStatus = payload.get("status");
        String reason = payload.get("reason");

        if (newStatus == null || (!newStatus.equals("ACTIVE") && !newStatus.equals("SUSPENDED"))) {
            return ResponseEntity.badRequest().body(Map.of("message", "Status must be ACTIVE or SUSPENDED."));
        }

        String previousStatus = user.getStatus();
        user.setStatus(newStatus);
        if ("SUSPENDED".equals(newStatus)) {
            user.setSuspensionReason(reason != null ? reason.trim() : "Administrative action");
            user.setSuspendedAt(LocalDateTime.now());
            user.setSuspendedBy(admin.getEmail());
        } else {
            user.setSuspensionReason(null);
            user.setSuspendedAt(null);
            user.setSuspendedBy(null);
        }

        userRepository.save(user);

        auditLogService.log(
                admin.getEmail(),
                "SUSPENDED".equals(newStatus) ? "USER_SUSPEND" : "USER_ACTIVATE",
                "USER",
                String.valueOf(user.getId()),
                "Changed status from " + previousStatus + " to " + newStatus + ". Reason: " + (reason != null ? reason : "None"),
                clientIp
        );

        return ResponseEntity.ok(Map.of(
                "success", true,
                "userId", user.getId(),
                "status", user.getStatus(),
                "message", "User status updated to " + newStatus
        ));
    }

    /**
     * Get user's wallet details and recent transactions for admin inspection
     */
    @GetMapping("/users/{id}/wallet")
    public ResponseEntity<?> getUserWallet(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_USERS);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found"));
        }
        User user = userOpt.get();
        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseGet(() -> {
            Wallet newW = new Wallet();
            newW.setUser(user);
            newW.setBalance(0.0);
            return walletRepository.save(newW);
        });

        List<Transaction> transactions = transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());

        Map<String, Object> resp = new HashMap<>();
        resp.put("walletId", wallet.getId());
        resp.put("userId", user.getId());
        resp.put("userName", user.getName());
        resp.put("userEmail", user.getEmail());
        resp.put("balance", wallet.getBalance());
        resp.put("transactions", transactions);

        return ResponseEntity.ok(resp);
    }

    /**
     * Admin Wallet Credit / Top-Up: Directly add funds to user's wallet from Admin Dashboard
     */
    @PostMapping("/users/{id}/wallet/credit")
    public ResponseEntity<?> adjustUserWallet(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found"));
        }
        User user = userOpt.get();

        if (!payload.containsKey("amount")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Amount is required."));
        }

        double amount;
        try {
            amount = Double.parseDouble(payload.get("amount").toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid amount value."));
        }

        if (amount <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Amount must be greater than ₹0.00."));
        }

        String type = payload.containsKey("type") && "DEBIT".equalsIgnoreCase(payload.get("type").toString()) ? "DEBIT" : "CREDIT";
        String description = payload.containsKey("description") && payload.get("description") != null
                ? payload.get("description").toString().trim()
                : ("CREDIT".equals(type) ? "Admin Promotional Credit" : "Admin Wallet Debit");

        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseGet(() -> {
            Wallet newW = new Wallet();
            newW.setUser(user);
            newW.setBalance(0.0);
            return walletRepository.save(newW);
        });

        double prevBalance = wallet.getBalance();
        double newBalance;

        if ("DEBIT".equals(type)) {
            if (wallet.getBalance() < amount) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "Insufficient wallet balance (Current: ₹" + String.format("%.2f", wallet.getBalance()) + ", Requested debit: ₹" + String.format("%.2f", amount) + ")."
                ));
            }
            newBalance = wallet.getBalance() - amount;
        } else {
            newBalance = wallet.getBalance() + amount;
        }

        wallet.setBalance(newBalance);
        walletRepository.save(wallet);

        Transaction transaction = new Transaction();
        transaction.setWallet(wallet);
        transaction.setAmount(amount);
        transaction.setType(type);
        transaction.setDescription(description + " (Admin: " + admin.getEmail() + ")");
        transaction.setCreatedAt(LocalDateTime.now());
        Transaction savedTxn = transactionRepository.save(transaction);

        String auditAction = "DEBIT".equals(type) ? "ADMIN_WALLET_DEBIT" : "ADMIN_WALLET_CREDIT";
        String auditDetails = String.format("Admin %s %sed ₹%.2f %s User #%d (%s). Reason: %s. Previous: ₹%.2f, New: ₹%.2f",
                admin.getEmail(),
                "DEBIT".equals(type) ? "debit" : "credit",
                amount,
                "DEBIT".equals(type) ? "from" : "to",
                user.getId(),
                user.getEmail(),
                description,
                prevBalance,
                newBalance
        );

        auditLogService.log(
                admin.getEmail(),
                auditAction,
                "WALLET",
                String.valueOf(user.getId()),
                auditDetails,
                clientIp
        );

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("message", String.format("Successfully %sed ₹%.2f. New balance: ₹%.2f", "DEBIT".equals(type) ? "debit" : "credit", amount, newBalance));
        resp.put("userId", user.getId());
        resp.put("userName", user.getName());
        resp.put("userEmail", user.getEmail());
        resp.put("previousBalance", prevBalance);
        resp.put("newBalance", newBalance);
        resp.put("amount", amount);
        resp.put("type", type);
        resp.put("transactionId", savedTxn.getId());

        return ResponseEntity.ok(resp);
    }

    // ==========================================
    // 3. DRIVER APPLICATION MANAGEMENT
    // ==========================================

    @GetMapping("/drivers/applications")
    public ResponseEntity<?> getDriverApplications(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, null);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;
        boolean hasAccess = "ADMIN".equalsIgnoreCase(admin.getRole()) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.VIEW_USERS) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.MANAGE_DRIVERS);
        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "message", "Access denied: Missing required permission VIEW_USERS or MANAGE_DRIVERS",
                    "requiredPermission", "VIEW_USERS"
            ));
        }

        boolean canViewDocs = "ADMIN".equalsIgnoreCase(admin.getRole()) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.VIEW_DRIVER_DOCS);

        Pageable pageable = PageRequest.of(page, size, Sort.by("submittedAt").descending());
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<DriverApplication> appPage = (cleanStatus == null && cleanSearch == null)
                ? driverApplicationRepository.findAll(pageable)
                : driverApplicationRepository.searchApplications(cleanStatus, cleanSearch, pageable);

        Page<DriverApplication> resultPage = appPage.map(app -> sanitizeDriverApplication(app, canViewDocs));
        return ResponseEntity.ok(resultPage);
    }

    @GetMapping("/drivers/applications/{id}")
    public ResponseEntity<?> getDriverApplicationDetails(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        StaffAuthResult auth = authenticateStaff(authHeader, null);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;
        boolean hasAccess = "ADMIN".equalsIgnoreCase(admin.getRole()) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.VIEW_USERS) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.MANAGE_DRIVERS);
        if (!hasAccess) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "message", "Access denied: Missing required permission VIEW_USERS or MANAGE_DRIVERS",
                    "requiredPermission", "VIEW_USERS"
            ));
        }

        boolean canViewDocs = "ADMIN".equalsIgnoreCase(admin.getRole()) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.VIEW_DRIVER_DOCS);

        Optional<DriverApplication> appOpt = driverApplicationRepository.findById(id);
        if (appOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Application not found"));

        return ResponseEntity.ok(sanitizeDriverApplication(appOpt.get(), canViewDocs));
    }

    @PutMapping("/drivers/applications/{id}/review")
    public ResponseEntity<?> reviewDriverApplication(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody ReviewApplicationRequest request,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_DRIVERS);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        ResponseEntity<?> reviewResult = driverService.reviewApplication(id, admin, request);
        if (reviewResult.getStatusCode() == HttpStatus.OK) {
            auditLogService.log(
                    admin.getEmail(),
                    "APPROVED".equalsIgnoreCase(request.getStatus()) ? "DRIVER_APPROVE" : "DRIVER_REJECT",
                    "DRIVER_APPLICATION",
                    String.valueOf(id),
                    "Reviewed application. New Status: " + request.getStatus() + ". Feedback: " + request.getReviewFeedback(),
                    clientIp
            );
        }
        return reviewResult;
    }

    // ==========================================
    // 4. RIDE MANAGEMENT
    // ==========================================

    @GetMapping("/rides")
    public ResponseEntity<?> getRides(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_RIDES);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("requestedAt").descending());
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<Ride> ridesPage = (cleanStatus == null && cleanSearch == null)
                ? rideRepository.findAll(pageable)
                : rideRepository.searchRides(cleanStatus, cleanSearch, pageable);
        return ResponseEntity.ok(ridesPage);
    }

    @GetMapping("/rides/{id}")
    public ResponseEntity<?> getRideDetails(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_RIDES);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Optional<Ride> rideOpt = rideRepository.findById(id);
        if (rideOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found"));

        Ride ride = rideOpt.get();
        List<Booking> bookings = bookingRepository.findByRideId(ride.getId());

        Map<String, Object> details = new HashMap<>();
        details.put("ride", ride);
        details.put("bookings", bookings);
        return ResponseEntity.ok(details);
    }

    @PutMapping("/rides/{id}/cancel")
    public ResponseEntity<?> cancelRideByAdmin(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_BOOKINGS_REFUNDS);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        Optional<Ride> rideOpt = rideRepository.findById(id);
        if (rideOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found"));
        Ride ride = rideOpt.get();

        if ("COMPLETED".equals(ride.getStatus()) || "CANCELLED".equals(ride.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Ride is already " + ride.getStatus()));
        }

        String reason = payload.get("reason");
        ride.setStatus("CANCELLED");
        ride.setCompletedBy("ADMIN: " + admin.getEmail());
        ride.setCompletedAt(LocalDateTime.now());
        rideRepository.save(ride);

        // Cancel associated bookings
        List<Booking> bookings = bookingRepository.findByRideId(ride.getId());
        for (Booking b : bookings) {
            if (!"CANCELLED".equals(b.getStatus())) {
                b.setStatus("CANCELLED");
                bookingRepository.save(b);
            }
        }

        auditLogService.log(
                admin.getEmail(),
                "RIDE_CANCEL",
                "RIDE",
                String.valueOf(ride.getId()),
                "Administrative cancellation. Reason: " + (reason != null ? reason : "Safety / policy violation"),
                clientIp
        );

        return ResponseEntity.ok(Map.of("message", "Ride cancelled successfully by administrator."));
    }

    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/rides/{id}")
    public ResponseEntity<?> deleteRideByAdmin(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_BOOKINGS_REFUNDS);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        Optional<Ride> rideOpt = rideRepository.findById(id);
        if (rideOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ride not found"));
        Ride ride = rideOpt.get();

        List<Booking> bookings = bookingRepository.findByRideId(ride.getId());
        for (Booking b : bookings) {
            commissionLedgerRepository.deleteByBookingId(b.getId());
            paymentOrderRepository.deleteByBookingId(b.getId());
        }
        commissionLedgerRepository.deleteByRideId(ride.getId());
        bookingRepository.deleteByRideId(ride.getId());
        rideRepository.delete(ride);

        auditLogService.log(
                admin.getEmail(),
                "RIDE_DELETE",
                "RIDE",
                String.valueOf(id),
                "Permanently deleted ride #" + id + " and related records.",
                clientIp
        );

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Ride #" + id + " permanently deleted."
        ));
    }

    @org.springframework.transaction.annotation.Transactional
    @RequestMapping(value = "/rides/clear-all", method = {RequestMethod.POST, RequestMethod.DELETE})
    public ResponseEntity<?> clearAllRidesByAdmin(
            @RequestHeader("Authorization") String authHeader,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        long rideCount = rideRepository.count();
        long bookingCount = bookingRepository.count();

        // 1. Delete all commission ledgers
        commissionLedgerRepository.deleteAll();

        // 2. Delete all payment orders
        paymentOrderRepository.deleteAll();

        // 3. Delete all bookings
        bookingRepository.deleteAll();

        // 4. Delete all rides
        rideRepository.deleteAll();

        auditLogService.log(
                admin.getEmail(),
                "RIDES_CLEAR_ALL",
                "RIDE",
                "ALL",
                "Permanently cleared all " + rideCount + " rides and " + bookingCount + " bookings from database.",
                clientIp
        );

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Database successfully cleared. Removed " + rideCount + " rides and " + bookingCount + " bookings.",
                "clearedRides", rideCount,
                "clearedBookings", bookingCount
        ));
    }

    // ==========================================
    // 5. BOOKING MANAGEMENT
    // ==========================================

    @GetMapping("/bookings")
    public ResponseEntity<?> getBookings(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_RIDES);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("bookedAt").descending());
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanPaymentStatus = (paymentStatus != null && !paymentStatus.isBlank() && !paymentStatus.equalsIgnoreCase("ALL")) ? paymentStatus.toUpperCase() : null;
        String cleanPaymentMethod = (paymentMethod != null && !paymentMethod.isBlank() && !paymentMethod.equalsIgnoreCase("ALL")) ? paymentMethod.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<Booking> bookingsPage = (cleanStatus == null && cleanPaymentStatus == null && cleanPaymentMethod == null && cleanSearch == null)
                ? bookingRepository.findAll(pageable)
                : bookingRepository.searchBookings(cleanStatus, cleanPaymentStatus, cleanPaymentMethod, cleanSearch, pageable);
        return ResponseEntity.ok(bookingsPage);
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<?> getBookingDetails(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_RIDES);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Optional<Booking> bookingOpt = bookingRepository.findById(id);
        if (bookingOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Booking not found"));

        Booking booking = bookingOpt.get();
        List<PaymentOrder> paymentOrders = paymentOrderRepository.findByBookingId(booking.getId());

        Map<String, Object> details = new HashMap<>();
        details.put("booking", booking);
        details.put("paymentOrders", paymentOrders);
        return ResponseEntity.ok(details);
    }

    // ==========================================
    // 6. FINANCE, PAYMENTS & REVENUE
    // ==========================================

    @GetMapping({"/finance/summary", "/revenue"})
    public ResponseEntity<?> getFinanceSummary(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_FINANCE);
        if (!auth.isAuthorized()) return auth.errorResponse;

        // Gross Booking Value from non-cancelled bookings
        double grossBookingValue = bookingRepository.getGrossBookingValue();

        Double charged = commissionLedgerRepository.sumPlatformCommissionCharged();
        Double settled = commissionLedgerRepository.sumPlatformCommissionSettled();
        Double outstanding = commissionLedgerRepository.sumPlatformCommissionOutstanding();
        Double grossCash = commissionLedgerRepository.sumGrossCash();
        Double driverNetFromCash = commissionLedgerRepository.sumDriverNetFromCash();
        Double driverEarningsOnline = commissionLedgerRepository.sumDriverEarningsOnline();

        double totalPlatformCharged = charged != null ? charged : 0.0;
        double totalSettled = settled != null ? settled : 0.0;
        double totalOutstanding = outstanding != null ? outstanding : 0.0;
        double totalGrossCash = grossCash != null ? grossCash : 0.0;
        double totalDriverNetFromCash = driverNetFromCash != null ? driverNetFromCash : 0.0;
        double totalDriverEarningsOnline = driverEarningsOnline != null ? driverEarningsOnline : 0.0;

        Double onlinePaid = paymentOrderRepository.sumPaidAmount();
        Double onlineFailed = paymentOrderRepository.sumFailedAmount();
        double totalOnlinePaid = onlinePaid != null ? onlinePaid : 0.0;
        double totalOnlineFailed = onlineFailed != null ? onlineFailed : 0.0;
        long paymentsSuccessCount = paymentOrderRepository.countByStatus("PAID");
        long paymentsFailedCount = paymentOrderRepository.countByStatus("FAILED");
        long paymentsPendingCount = paymentOrderRepository.countByStatus("CREATED");

        Double totalRefunds = paymentRefundRepository.getTotalRefundedAmount();
        double refundsAmount = totalRefunds != null ? totalRefunds : 0.0;
        long refundsCount = paymentRefundRepository.countByStatus("REFUNDED");
        long partialRefundsCount = paymentRefundRepository.countByStatus("PARTIALLY_REFUNDED");

        Double totalPaidPayouts = driverPayoutRepository.getTotalCompletedPayoutsAmount();
        double completedPayoutsAmount = totalPaidPayouts != null ? totalPaidPayouts : 0.0;

        Double totalPendingPayouts = driverPayoutRepository.getTotalPendingPayoutsAmount();
        double pendingPayoutsAmount = totalPendingPayouts != null ? totalPendingPayouts : 0.0;

        long payoutsPendingCount = driverPayoutRepository.countByStatus("PENDING");
        long payoutsProcessingCount = driverPayoutRepository.countByStatus("PROCESSING");
        long payoutsPaidCount = driverPayoutRepository.countByStatus("PAID");
        long payoutsFailedCount = driverPayoutRepository.countByStatus("FAILED");

        // Specific distinct fee & commission breakdowns directly from database SQL
        Double riderFees = bookingRepository.sumPaidRiderPlatformFees();
        Double driverCommissions = bookingRepository.sumPaidDriverCommissions();
        Double refRiderFees = bookingRepository.sumRefundedRiderPlatformFees();
        Double revCommissions = bookingRepository.sumReversedCommissions();

        double totalRiderPlatformFees = riderFees != null ? riderFees : 0.0;
        double totalDriverCommissionOnline = driverCommissions != null ? driverCommissions : 0.0;
        double refundedRiderFees = refRiderFees != null ? refRiderFees : 0.0;
        double reversedCommissions = revCommissions != null ? revCommissions : 0.0;

        // Driver commission collected includes online retained + settled cash commissions
        double totalDriverCommissionCollected = totalDriverCommissionOnline + totalSettled;
        double grossPlatformRevenue = totalRiderPlatformFees + totalDriverCommissionCollected;
        double refundedPlatformRevenue = refundedRiderFees + reversedCommissions;
        double netPlatformRevenue = Math.max(0.0, grossPlatformRevenue - refundedPlatformRevenue);

        double totalDriverEarningsAll = totalDriverNetFromCash + totalDriverEarningsOnline;

        Map<String, Object> finance = new HashMap<>();
        finance.put("grossBookingValue", Math.round(grossBookingValue * 100.0) / 100.0);
        finance.put("grossCashFareCollected", Math.round(totalGrossCash * 100.0) / 100.0);
        finance.put("totalOnlinePaidAmount", Math.round(totalOnlinePaid * 100.0) / 100.0);
        finance.put("totalOnlineFailedAmount", Math.round(totalOnlineFailed * 100.0) / 100.0);

        // Explicit separate metrics
        finance.put("totalRiderPlatformFees", Math.round(totalRiderPlatformFees * 100.0) / 100.0);
        finance.put("totalDriverCommission", Math.round(totalDriverCommissionCollected * 100.0) / 100.0);
        finance.put("grossPlatformRevenue", Math.round(grossPlatformRevenue * 100.0) / 100.0);
        finance.put("pendingDriverCommission", Math.round(totalOutstanding * 100.0) / 100.0);
        finance.put("refundedRiderFees", Math.round(refundedRiderFees * 100.0) / 100.0);
        finance.put("reversedCommissions", Math.round(reversedCommissions * 100.0) / 100.0);
        finance.put("refundedPlatformRevenue", Math.round(refundedPlatformRevenue * 100.0) / 100.0);

        // Backward-compatible keys
        finance.put("platformFees", Math.round((grossPlatformRevenue > 0 ? grossPlatformRevenue : totalPlatformCharged) * 100.0) / 100.0);
        finance.put("driverEarnings", Math.round(totalDriverEarningsAll * 100.0) / 100.0);
        finance.put("refunds", Math.round(refundsAmount * 100.0) / 100.0);
        finance.put("netPlatformRevenue", Math.round(netPlatformRevenue * 100.0) / 100.0);
        finance.put("pendingSettlements", Math.round(totalOutstanding * 100.0) / 100.0);
        finance.put("completedPayouts", Math.round(completedPayoutsAmount * 100.0) / 100.0);
        finance.put("failedPayouts", Math.round(payoutsFailedCount * 1.0));
        finance.put("pendingPayoutsAmount", Math.round(pendingPayoutsAmount * 100.0) / 100.0);

        finance.put("paymentsCount", Map.of(
                "pending", paymentsPendingCount,
                "successful", paymentsSuccessCount,
                "failed", paymentsFailedCount,
                "refunded", refundsCount,
                "partiallyRefunded", partialRefundsCount
        ));

        finance.put("payoutsCount", Map.of(
                "pending", payoutsPendingCount,
                "processing", payoutsProcessingCount,
                "paid", payoutsPaidCount,
                "failed", payoutsFailedCount
        ));

        return ResponseEntity.ok(finance);
    }

    @GetMapping("/finance/ledger")
    public ResponseEntity<?> getCommissionLedger(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_FINANCE);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        String cleanType = (entryType != null && !entryType.isBlank() && !entryType.equalsIgnoreCase("ALL")) ? entryType.toUpperCase() : null;
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<CommissionLedger> ledgerPage = (cleanType == null && cleanStatus == null && cleanSearch == null)
                ? commissionLedgerRepository.findAll(pageable)
                : commissionLedgerRepository.searchLedger(cleanType, cleanStatus, cleanSearch, pageable);
        return ResponseEntity.ok(ledgerPage);
    }

    @GetMapping({"/finance/payment-orders", "/payments"})
    public ResponseEntity<?> getPaymentOrders(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_FINANCE);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<PaymentOrder> ordersPage = (cleanStatus == null && cleanSearch == null)
                ? paymentOrderRepository.findAll(pageable)
                : paymentOrderRepository.searchPaymentOrders(cleanStatus, cleanSearch, pageable);
        return ResponseEntity.ok(ordersPage);
    }

    @GetMapping("/payments/{id}/timeline")
    public ResponseEntity<?> getPaymentTimeline(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_FINANCE);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Optional<PaymentOrder> orderOpt = paymentOrderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Payment order not found."));
        }

        PaymentOrder order = orderOpt.get();
        Booking booking = order.getBooking();
        Ride ride = order.getRide() != null ? order.getRide() : (booking != null ? booking.getRide() : null);

        List<Map<String, Object>> timeline = new ArrayList<>();

        // 1. Booking Created
        if (booking != null) {
            timeline.add(Map.of(
                    "event", "BOOKING_CREATED",
                    "title", "Booking Created",
                    "timestamp", booking.getBookedAt() != null ? booking.getBookedAt() : order.getCreatedAt(),
                    "amount", booking.getTotalAmount(),
                    "referenceId", booking.getBookingCode(),
                    "source", "Passenger (" + booking.getPassenger().getName() + ")",
                    "status", "COMPLETED"
            ));
        }

        // 2. Payment Order Created
        timeline.add(Map.of(
                "event", "PAYMENT_ORDER_CREATED",
                "title", "Payment Order Created",
                "timestamp", order.getCreatedAt(),
                "amount", order.getAmount(),
                "referenceId", order.getOrderId() + " (" + order.getGatewayOrderId() + ")",
                "source", "Payment Gateway (" + order.getProvider() + ")",
                "status", "COMPLETED"
        ));

        // 3. Payment Verification
        if ("PAID".equals(order.getStatus())) {
            timeline.add(Map.of(
                    "event", "PAYMENT_SUCCESSFUL",
                    "title", "Payment Successful & Verified",
                    "timestamp", order.getPaidAt() != null ? order.getPaidAt() : order.getCreatedAt(),
                    "amount", order.getAmount(),
                    "referenceId", order.getGatewayPaymentId() != null ? order.getGatewayPaymentId() : "VERIFIED",
                    "source", "Gateway Webhook / Signature Verification",
                    "status", "COMPLETED"
            ));
        } else if ("FAILED".equals(order.getStatus())) {
            timeline.add(Map.of(
                    "event", "PAYMENT_FAILED",
                    "title", "Payment Failed",
                    "timestamp", order.getUpdatedAt() != null ? order.getUpdatedAt() : order.getCreatedAt(),
                    "amount", order.getAmount(),
                    "referenceId", order.getFailureReason() != null ? order.getFailureReason() : "Declined",
                    "source", "Payment Gateway",
                    "status", "FAILED"
            ));
        }

        // 4. Booking Confirmed
        if (booking != null && ("CONFIRMED".equals(booking.getStatus()) || "COMPLETED".equals(booking.getStatus()))) {
            timeline.add(Map.of(
                    "event", "BOOKING_CONFIRMED",
                    "title", "Booking Confirmed & Seats Locked",
                    "timestamp", order.getPaidAt() != null ? order.getPaidAt() : booking.getBookedAt(),
                    "amount", booking.getTotalAmount(),
                    "referenceId", booking.getBookingCode(),
                    "source", "RideShare Core Settlement Engine",
                    "status", "COMPLETED"
            ));
        }

        // 5. Trip Started
        if (ride != null && ("IN_PROGRESS".equals(ride.getStatus()) || "COMPLETED".equals(ride.getStatus()))) {
            timeline.add(Map.of(
                    "event", "TRIP_STARTED",
                    "title", "Trip In Progress",
                    "timestamp", ride.getDepartureTime() != null ? ride.getDepartureTime() : LocalDateTime.now(),
                    "referenceId", "Ride #" + ride.getId(),
                    "source", "Driver (" + (ride.getDriver() != null ? ride.getDriver().getName() : "Driver") + ")",
                    "status", "COMPLETED"
            ));
        }

        // 6. Trip Completed & Driver Earning
        if (ride != null && "COMPLETED".equals(ride.getStatus())) {
            LocalDateTime compTime = ride.getCompletedAt() != null ? ride.getCompletedAt() : LocalDateTime.now();
            timeline.add(Map.of(
                    "event", "TRIP_COMPLETED",
                    "title", "Trip Completed Safely",
                    "timestamp", compTime,
                    "referenceId", "Ride #" + ride.getId(),
                    "source", "Driver (" + (ride.getDriver() != null ? ride.getDriver().getName() : "Driver") + ")",
                    "status", "COMPLETED"
            ));

            timeline.add(Map.of(
                    "event", "DRIVER_EARNING_CREATED",
                    "title", "Driver Earning Created & Settlement Hold Cleared",
                    "timestamp", compTime,
                    "amount", booking != null ? booking.getDriverAmount() : (order.getAmount() - order.getPlatformFee()),
                    "referenceId", "LEDGER_EARNING_" + (booking != null ? booking.getId() : order.getId()),
                    "source", "Immutable Driver Financial Ledger",
                    "status", "COMPLETED"
            ));

            timeline.add(Map.of(
                    "event", "SETTLEMENT_ELIGIBLE",
                    "title", "Payout Eligible",
                    "timestamp", compTime,
                    "amount", booking != null ? booking.getDriverAmount() : (order.getAmount() - order.getPlatformFee()),
                    "referenceId", "PAYOUT_READY",
                    "source", "Driver Payout Account",
                    "status", "COMPLETED"
            ));
        }

        // Check if refund exists
        if (booking != null) {
            List<PaymentRefund> refunds = paymentRefundRepository.findByBookingId(booking.getId());
            for (PaymentRefund r : refunds) {
                timeline.add(Map.of(
                        "event", "REFUND_" + r.getStatus(),
                        "title", "Refund (" + r.getStatus() + ")",
                        "timestamp", r.getProcessedAt() != null ? r.getProcessedAt() : r.getCreatedAt(),
                        "amount", r.getAmount(),
                        "referenceId", r.getRefundId() + " (" + r.getProviderRefundId() + ")",
                        "source", "Refund Manager",
                        "status", "REFUNDED".equals(r.getStatus()) ? "COMPLETED" : "PENDING"
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "orderId", order.getOrderId(),
                "gatewayOrderId", order.getGatewayOrderId(),
                "gatewayPaymentId", order.getGatewayPaymentId(),
                "amount", order.getAmount(),
                "platformFee", order.getPlatformFee(),
                "driverAmount", order.getDriverAmount(),
                "status", order.getStatus(),
                "timeline", timeline
        ));
    }

    @GetMapping("/payouts")
    public ResponseEntity<?> getPayouts(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_FINANCE);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<DriverPayout> payoutsPage = driverPayoutRepository.searchPayouts(cleanStatus, cleanSearch, pageable);
        return ResponseEntity.ok(payoutsPage);
    }

    @PostMapping("/payouts/{id}/retry")
    public ResponseEntity<?> retryPayout(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_BOOKINGS_REFUNDS);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        Optional<DriverPayout> payoutOpt = driverPayoutRepository.findById(id);
        if (payoutOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Payout not found."));
        }

        DriverPayout payout = payoutOpt.get();
        if ("PAID".equals(payout.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Payout is already marked as PAID."));
        }

        Map<String, Object> gatewayResult = razorpayService.createPayout(
                payout.getAccountReference(),
                payout.getAmount(),
                "IMPS",
                "Retry Payout " + payout.getPayoutId()
        );

        payout.setStatus("PAID");
        payout.setProviderPayoutId(gatewayResult.get("payoutId").toString());
        payout.setProcessedAt(LocalDateTime.now());
        payout.setNotes("Retried by Admin " + admin.getEmail());
        driverPayoutRepository.save(payout);

        return ResponseEntity.ok(Map.of("success", true, "message", "Payout retried successfully.", "payout", payout));
    }

    @GetMapping("/refunds")
    public ResponseEntity<?> getRefunds(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_FINANCE);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<PaymentRefund> refundsPage = paymentRefundRepository.searchRefunds(cleanStatus, cleanSearch, pageable);
        return ResponseEntity.ok(refundsPage);
    }

    @PostMapping("/refunds/{id}/retry")
    public ResponseEntity<?> retryRefund(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_BOOKINGS_REFUNDS);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Optional<PaymentRefund> refundOpt = paymentRefundRepository.findById(id);
        if (refundOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Refund not found."));
        }

        PaymentRefund refund = refundOpt.get();
        if ("REFUNDED".equals(refund.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Refund is already completed."));
        }

        String paymentRef = refund.getBooking() != null ? refund.getBooking().getTransactionReference() : null;
        Map<String, Object> gatewayRefund = razorpayService.createRefund(paymentRef, refund.getAmount(), "Admin Retry Refund");

        refund.setStatus("REFUNDED");
        refund.setProviderRefundId(gatewayRefund.get("refundId").toString());
        refund.setProcessedAt(LocalDateTime.now());
        paymentRefundRepository.save(refund);

        return ResponseEntity.ok(Map.of("success", true, "message", "Refund retried successfully.", "refund", refund));
    }

    // ==========================================
    // 7. EMAIL MONITORING & TEST
    // ==========================================

    @GetMapping("/email/status")
    public ResponseEntity<?> getEmailStatus(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_LOGS);
        if (!auth.isAuthorized()) return auth.errorResponse;

        return ResponseEntity.ok(emailService.getDiagnostics());
    }

    @GetMapping("/email/logs")
    public ResponseEntity<?> getEmailLogs(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_LOGS);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("sentAt").descending());
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanCategory = (category != null && !category.isBlank() && !category.equalsIgnoreCase("ALL")) ? category.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<EmailLog> logPage = (cleanStatus == null && cleanCategory == null && cleanSearch == null)
                ? emailLogRepository.findAll(pageable)
                : emailLogRepository.searchEmailLogs(cleanStatus, cleanCategory, cleanSearch, pageable);
        return ResponseEntity.ok(logPage);
    }

    @PostMapping("/email/test")
    public ResponseEntity<?> sendAdminTestEmail(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        String recipient = payload.get("to");
        if (recipient == null || !recipient.contains("@") || recipient.trim().length() < 5) {
            return ResponseEntity.badRequest().body(Map.of("message", "A valid destination email is required."));
        }

        long start = System.currentTimeMillis();
        boolean sent = emailService.sendPlainTextEmail(
                recipient.trim(),
                "RideShare Admin System Test Notification",
                "Hello,\n\nThis is an administrative test message dispatched from the RideShare Admin Panel.\nInitiated by: " + admin.getEmail() + "\nTimestamp: " + LocalDateTime.now() + "\n\nAll systems operational.",
                "TEST_EMAIL"
        );
        long duration = System.currentTimeMillis() - start;

        auditLogService.log(
                admin.getEmail(),
                "TEST_EMAIL_SENT",
                "EMAIL",
                recipient.trim(),
                "Sent test email. Result: " + (sent ? "SUCCESS" : "FAILED") + ", Latency: " + duration + "ms",
                clientIp
        );

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", sent);
        resp.put("recipient", recipient.trim());
        resp.put("latencyMs", duration);
        resp.put("message", sent
                ? "Test email dispatched successfully via Gmail SMTP."
                : "Email delivery attempt recorded. If SMTP credentials are not set, check dev logs.");

        return ResponseEntity.ok(resp);
    }

    // ==========================================
    // 8. SUPPORT TICKETS
    // ==========================================

    @GetMapping("/tickets")
    public ResponseEntity<?> getTickets(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.toUpperCase() : null;
        String cleanPriority = (priority != null && !priority.isBlank() && !priority.equalsIgnoreCase("ALL")) ? priority.toUpperCase() : null;
        String cleanCategory = (category != null && !category.isBlank() && !category.equalsIgnoreCase("ALL")) ? category.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<SupportTicket> ticketPage = (cleanStatus == null && cleanPriority == null && cleanCategory == null && cleanSearch == null)
                ? supportTicketRepository.findAll(pageable)
                : supportTicketRepository.searchTickets(cleanStatus, cleanPriority, cleanCategory, cleanSearch, pageable);
        return ResponseEntity.ok(ticketPage);
    }

    @GetMapping("/tickets/{id}")
    public ResponseEntity<?> getTicketDetails(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Optional<SupportTicket> ticketOpt = supportTicketRepository.findById(id);
        if (ticketOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ticket not found"));

        return ResponseEntity.ok(ticketOpt.get());
    }

    @PutMapping("/tickets/{id}")
    public ResponseEntity<?> updateTicket(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        Optional<SupportTicket> ticketOpt = supportTicketRepository.findById(id);
        if (ticketOpt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Ticket not found"));
        SupportTicket ticket = ticketOpt.get();

        String status = payload.get("status");
        String priority = payload.get("priority");
        String adminNotes = payload.get("adminNotes");

        if (status != null && !status.isBlank()) {
            ticket.setStatus(status.toUpperCase());
            if ("RESOLVED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status)) {
                ticket.setResolvedAt(LocalDateTime.now());
                ticket.setResolvedBy(admin.getEmail());
            }
        }
        if (priority != null && !priority.isBlank()) {
            ticket.setPriority(priority.toUpperCase());
        }
        if (adminNotes != null) {
            ticket.setAdminNotes(adminNotes.trim());
        }

        supportTicketRepository.save(ticket);

        auditLogService.log(
                admin.getEmail(),
                "TICKET_RESOLVE",
                "SUPPORT_TICKET",
                ticket.getTicketNumber(),
                "Updated ticket. Status: " + ticket.getStatus() + ", Priority: " + ticket.getPriority(),
                clientIp
        );

        return ResponseEntity.ok(ticket);
    }

    // ==========================================
    // 9. AUDIT LOGS
    // ==========================================

    @GetMapping("/audit-logs")
    public ResponseEntity<?> getAuditLogs(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_LOGS);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by("timestamp").descending());
        String cleanAction = (action != null && !action.isBlank() && !action.equalsIgnoreCase("ALL")) ? action.toUpperCase() : null;
        String cleanEntity = (entityType != null && !entityType.isBlank() && !entityType.equalsIgnoreCase("ALL")) ? entityType.toUpperCase() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<AuditLog> auditPage = (cleanAction == null && cleanEntity == null && cleanSearch == null)
                ? auditLogRepository.findAll(pageable)
                : auditLogRepository.searchAuditLogs(cleanAction, cleanEntity, cleanSearch, pageable);
        return ResponseEntity.ok(auditPage);
    }

    // ==========================================
    // 10. SYSTEM SETTINGS
    // ==========================================

    @GetMapping("/settings")
    public ResponseEntity<?> getPlatformSettings(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;

        PlatformSettings settings = platformSettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> {
                    PlatformSettings s = PlatformSettings.builder().build();
                    return platformSettingsRepository.save(s);
                });

        return ResponseEntity.ok(settings);
    }

    @PutMapping("/settings")
    public ResponseEntity<?> updatePlatformSettings(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody PlatformSettings newSettings,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        PlatformSettings current = platformSettingsRepository.findAll().stream().findFirst()
                .orElseGet(() -> PlatformSettings.builder().build());

        // Snapshot previous values for comprehensive audit logging
        boolean prevDriverEnabled = Boolean.TRUE.equals(current.getDriverCommissionEnabled());
        String prevDriverType = current.getDriverCommissionType();
        double prevDriverRate = current.getDriverCommissionRatePercent() != null ? current.getDriverCommissionRatePercent() : 0.0;
        double prevDriverFixed = current.getDriverCommissionFixedAmount() != null ? current.getDriverCommissionFixedAmount() : 0.0;

        boolean prevRiderEnabled = Boolean.TRUE.equals(current.getRiderFeeEnabled());
        String prevRiderType = current.getRiderFeeType();
        double prevRiderAmount = current.getRiderFeeAmount() != null ? current.getRiderFeeAmount() : 0.0;
        double prevRiderPercent = current.getRiderFeePercent() != null ? current.getRiderFeePercent() : 0.0;

        // General company & support info
        if (newSettings.getSupportEmail() != null) current.setSupportEmail(newSettings.getSupportEmail().trim());
        if (newSettings.getSupportPhone() != null) current.setSupportPhone(newSettings.getSupportPhone().trim());
        if (newSettings.getCompanyName() != null) current.setCompanyName(newSettings.getCompanyName().trim());
        if (newSettings.getCompanyAddress() != null) current.setCompanyAddress(newSettings.getCompanyAddress().trim());

        // Support legacy field mapping if caller provided legacy fields
        if (Boolean.TRUE.equals(newSettings.getCommissionEnabled())) {
            newSettings.setDriverCommissionEnabled(true);
        } else if (newSettings.getDriverCommissionEnabled() == null && newSettings.getCommissionEnabled() != null) {
            newSettings.setDriverCommissionEnabled(newSettings.getCommissionEnabled());
        }
        if (newSettings.getCommissionRatePercent() != null && newSettings.getCommissionRatePercent() > 0.0 && (newSettings.getDriverCommissionRatePercent() == null || newSettings.getDriverCommissionRatePercent() == 0.0)) {
            newSettings.setDriverCommissionRatePercent(newSettings.getCommissionRatePercent());
        } else if (newSettings.getDriverCommissionRatePercent() == null && newSettings.getCommissionRatePercent() != null) {
            newSettings.setDriverCommissionRatePercent(newSettings.getCommissionRatePercent());
        }
        if (newSettings.getCommissionFixedFee() != null && newSettings.getCommissionFixedFee() > 0.0 && (newSettings.getDriverCommissionFixedAmount() == null || newSettings.getDriverCommissionFixedAmount() == 0.0)) {
            newSettings.setDriverCommissionFixedAmount(newSettings.getCommissionFixedFee());
        } else if (newSettings.getDriverCommissionFixedAmount() == null && newSettings.getCommissionFixedFee() != null) {
            newSettings.setDriverCommissionFixedAmount(newSettings.getCommissionFixedFee());
        }
        if (newSettings.getPlatformFeeAmount() != null && newSettings.getPlatformFeeAmount() > 0.0 && (newSettings.getRiderFeeAmount() == null || newSettings.getRiderFeeAmount() == 0.0)) {
            newSettings.setRiderFeeAmount(newSettings.getPlatformFeeAmount());
        } else if (newSettings.getRiderFeeAmount() == null && newSettings.getPlatformFeeAmount() != null) {
            newSettings.setRiderFeeAmount(newSettings.getPlatformFeeAmount());
        }
        if (newSettings.getPlatformFeeType() != null && (newSettings.getRiderFeeType() == null || "FIXED".equals(newSettings.getRiderFeeType()))) {
            newSettings.setRiderFeeType(newSettings.getPlatformFeeType());
        }
        if (newSettings.getEffectiveDate() != null) {
            newSettings.setDriverCommissionEffectiveDate(newSettings.getEffectiveDate());
        }

        // 1. Driver Commission validation and update
        if (newSettings.getDriverCommissionEnabled() != null) {
            current.setDriverCommissionEnabled(newSettings.getDriverCommissionEnabled());
        }
        if (newSettings.getDriverCommissionType() != null) {
            String type = newSettings.getDriverCommissionType().toUpperCase();
            if (!"PERCENTAGE".equals(type) && !"FIXED".equals(type) && !"PERCENT_PLUS_FIXED".equals(type)) {
                return ResponseEntity.badRequest().body(Map.of("message", "Driver commission type must be PERCENTAGE, FIXED, or PERCENT_PLUS_FIXED."));
            }
            current.setDriverCommissionType(type);
        }
        if (newSettings.getDriverCommissionRatePercent() != null) {
            double rate = newSettings.getDriverCommissionRatePercent();
            if (rate < 0.0 || rate > 100.0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Driver commission rate must be between 0% and 100%."));
            }
            current.setDriverCommissionRatePercent(rate);
        }
        if (newSettings.getDriverCommissionFixedAmount() != null) {
            double fee = newSettings.getDriverCommissionFixedAmount();
            if (fee < 0.0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Driver commission fixed amount cannot be negative."));
            }
            current.setDriverCommissionFixedAmount(fee);
        }
        if (newSettings.getDriverCommissionEffectiveDate() != null) {
            current.setDriverCommissionEffectiveDate(newSettings.getDriverCommissionEffectiveDate());
        }

        // 2. Rider Platform Fee validation and update
        if (newSettings.getRiderFeeEnabled() != null) {
            current.setRiderFeeEnabled(newSettings.getRiderFeeEnabled());
        }
        if (newSettings.getRiderFeeType() != null) {
            String type = newSettings.getRiderFeeType().toUpperCase();
            if (!"FIXED".equals(type) && !"PERCENTAGE".equals(type)) {
                return ResponseEntity.badRequest().body(Map.of("message", "Rider fee type must be FIXED or PERCENTAGE."));
            }
            current.setRiderFeeType(type);
        }
        if (newSettings.getRiderFeeAmount() != null) {
            double fee = newSettings.getRiderFeeAmount();
            if (fee < 0.0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Rider platform fee amount cannot be negative."));
            }
            current.setRiderFeeAmount(fee);
        }
        if (newSettings.getRiderFeePercent() != null) {
            double pct = newSettings.getRiderFeePercent();
            if (pct < 0.0 || pct > 100.0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Rider platform fee percent must be between 0% and 100%."));
            }
            current.setRiderFeePercent(pct);
        }
        if (newSettings.getRiderFeeEffectiveDate() != null) {
            current.setRiderFeeEffectiveDate(newSettings.getRiderFeeEffectiveDate());
        }

        // Operational settings
        if (newSettings.getSeatLockTimeoutMinutes() != null) {
            if (newSettings.getSeatLockTimeoutMinutes() < 1 || newSettings.getSeatLockTimeoutMinutes() > 120) {
                return ResponseEntity.badRequest().body(Map.of("message", "Seat lock timeout must be between 1 and 120 minutes."));
            }
            current.setSeatLockTimeoutMinutes(newSettings.getSeatLockTimeoutMinutes());
        }
        if (newSettings.getOutstandingThreshold() != null) {
            if (newSettings.getOutstandingThreshold() < 0.0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Driver outstanding threshold cannot be negative."));
            }
            current.setOutstandingThreshold(newSettings.getOutstandingThreshold());
        }
        if (newSettings.getEmailNotificationsEnabled() != null) {
            current.setEmailNotificationsEnabled(newSettings.getEmailNotificationsEnabled());
        }

        // 3. Cancellation Fee validation and update
        if (newSettings.getCancellationFeeEnabled() != null) {
            current.setCancellationFeeEnabled(newSettings.getCancellationFeeEnabled());
        }
        if (newSettings.getCancellationFeeType() != null) {
            String type = newSettings.getCancellationFeeType().toUpperCase();
            if (!"FIXED".equals(type) && !"PERCENTAGE".equals(type)) {
                return ResponseEntity.badRequest().body(Map.of("message", "Cancellation fee type must be FIXED or PERCENTAGE."));
            }
            current.setCancellationFeeType(type);
        }
        if (newSettings.getDefaultCancellationFee() != null) {
            double fee = newSettings.getDefaultCancellationFee();
            if (fee < 0.0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Default cancellation fee cannot be negative."));
            }
            if ("PERCENTAGE".equalsIgnoreCase(current.getCancellationFeeType()) && fee > 100.0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Default cancellation percentage cannot exceed 100%."));
            }
            current.setDefaultCancellationFee(fee);
        }
        if (newSettings.getCancellationGraceMinutes() != null) {
            if (newSettings.getCancellationGraceMinutes() < 0) {
                return ResponseEntity.badRequest().body(Map.of("message", "Cancellation grace minutes cannot be negative."));
            }
            current.setCancellationGraceMinutes(newSettings.getCancellationGraceMinutes());
        }
        if (newSettings.getCancellationPolicyNotice() != null) {
            current.setCancellationPolicyNotice(newSettings.getCancellationPolicyNotice().trim());
        }

        // Synchronize legacy fields for backward compatibility
        current.setCommissionEnabled(current.getDriverCommissionEnabled());
        current.setCommissionRatePercent(current.getDriverCommissionRatePercent());
        current.setCommissionFixedFee(current.getDriverCommissionFixedAmount());
        current.setPlatformFeeType(current.getRiderFeeType());
        current.setPlatformFeeAmount(current.getRiderFeeAmount());
        current.setEffectiveDate(current.getDriverCommissionEffectiveDate());

        current.setUpdatedBy(admin.getEmail());
        current.setUpdatedAt(LocalDateTime.now());

        PlatformSettings saved = platformSettingsRepository.save(current);

        if (redisCacheService != null) {
            redisCacheService.evictSettingsCache();
        }

        String auditDetails = String.format(
                "Fee configuration updated. Driver Commission: [Enabled: %b -> %b, Type: %s -> %s, Rate: %.2f%% -> %.2f%%, Fixed: ₹%.2f -> ₹%.2f]. Rider Fee: [Enabled: %b -> %b, Type: %s -> %s, Fixed: ₹%.2f -> ₹%.2f, Rate: %.2f%% -> %.2f%%]. Cancellation: [Enabled: %b, DefaultFee: ₹%.2f %s, Grace: %dm]. Effective: [Driver: %s, Rider: %s] | CommissionRate=%.1f%%",
                prevDriverEnabled, saved.getDriverCommissionEnabled(),
                prevDriverType, saved.getDriverCommissionType(),
                prevDriverRate, saved.getDriverCommissionRatePercent(),
                prevDriverFixed, saved.getDriverCommissionFixedAmount(),
                prevRiderEnabled, saved.getRiderFeeEnabled(),
                prevRiderType, saved.getRiderFeeType(),
                prevRiderAmount, saved.getRiderFeeAmount(),
                prevRiderPercent, saved.getRiderFeePercent(),
                Boolean.TRUE.equals(saved.getCancellationFeeEnabled()),
                saved.getDefaultCancellationFee() != null ? saved.getDefaultCancellationFee() : 0.0,
                saved.getCancellationFeeType() != null ? saved.getCancellationFeeType() : "FIXED",
                saved.getCancellationGraceMinutes() != null ? saved.getCancellationGraceMinutes() : 0,
                saved.getDriverCommissionEffectiveDate(),
                saved.getRiderFeeEffectiveDate(),
                saved.getDriverCommissionRatePercent() != null ? saved.getDriverCommissionRatePercent() : 0.0
        );

        auditLogService.log(
                admin.getEmail(),
                "CONFIG_UPDATE",
                "SETTINGS",
                String.valueOf(saved.getId()),
                auditDetails,
                clientIp
        );

        return ResponseEntity.ok(saved);
    }

    @GetMapping("/recurring-schedules")
    public ResponseEntity<?> getAdminRecurringSchedules(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_RIDES);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<RecurringRideSchedule> result;
        if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            result = recurringRideScheduleRepository.findByStatus(status.trim().toUpperCase(), pageable);
        } else {
            result = recurringRideScheduleRepository.findAll(pageable);
        }
        return ResponseEntity.ok(result);
    }

    @Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/system/cleanup-keep-specified-users")
    public ResponseEntity<?> cleanupUsersExceptSpecified(
            @RequestHeader("Authorization") String authHeader,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        User admin = getAuthenticatedAdmin(authHeader);
        if (admin == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Unauthorized"));

        List<String> keepEmails = List.of("kumar@gmail.com", "avi@gmail.com", "admin@rideshare.com");
        
        List<Long> keepIds = new ArrayList<>();
        for (String email : keepEmails) {
            User u = userRepository.findByEmail(email);
            if (u != null) {
                keepIds.add(u.getId());
            }
        }

        if (keepIds.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Error: No specified accounts found to keep."));
        }

        String inClause = keepIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));

        // 1. Delete commission_ledger
        jdbcTemplate.update(
            "DELETE FROM commission_ledger WHERE (driver_id IS NOT NULL AND driver_id NOT IN (" + inClause + ")) " +
            "OR ride_id IN (SELECT id FROM rides WHERE rider_id NOT IN (" + inClause + "))"
        );

        // 2. Delete payment_orders
        jdbcTemplate.update(
            "DELETE FROM payment_orders WHERE user_id NOT IN (" + inClause + ") " +
            "OR booking_id IN (SELECT id FROM bookings WHERE passenger_id NOT IN (" + inClause + ") " +
            "OR ride_id IN (SELECT id FROM rides WHERE rider_id NOT IN (" + inClause + ")))"
        );

        // 3. Delete bookings
        int bookingsDeleted = jdbcTemplate.update(
            "DELETE FROM bookings WHERE passenger_id NOT IN (" + inClause + ") " +
            "OR ride_id IN (SELECT id FROM rides WHERE rider_id NOT IN (" + inClause + "))"
        );

        // 4. Delete rides where rider_id is not kept (rider_id is NOT NULL in table schema)
        int ridesDeleted = jdbcTemplate.update("DELETE FROM rides WHERE rider_id NOT IN (" + inClause + ")");

        // 5. In remaining rides (owned by kept users), set driver_id to NULL if driver is not kept
        jdbcTemplate.update("UPDATE rides SET driver_id = NULL WHERE driver_id IS NOT NULL AND driver_id NOT IN (" + inClause + ")");

        // 6. Update reviewer in driver applications
        jdbcTemplate.update("UPDATE driver_applications SET reviewed_by_id = NULL WHERE reviewed_by_id NOT IN (" + inClause + ")");

        // 7. Delete driver_applications
        int applicationsDeleted = jdbcTemplate.update("DELETE FROM driver_applications WHERE user_id NOT IN (" + inClause + ")");

        // 8. Delete wallets
        jdbcTemplate.update("DELETE FROM wallets WHERE user_id NOT IN (" + inClause + ")");

        // 9. Delete vehicles
        jdbcTemplate.update("DELETE FROM vehicles WHERE driver_id NOT IN (" + inClause + ")");

        // 10. Delete referrals
        jdbcTemplate.update("DELETE FROM referrals WHERE referrer_id NOT IN (" + inClause + ") OR referred_user_id NOT IN (" + inClause + ")");

        // 11. Delete notifications
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id NOT IN (" + inClause + ")");

        // 12. Disassociate support_tickets
        jdbcTemplate.update("UPDATE support_tickets SET user_id = NULL WHERE user_id NOT IN (" + inClause + ")");

        // 13. Delete users
        int usersDeleted = jdbcTemplate.update("DELETE FROM users WHERE id NOT IN (" + inClause + ")");

        auditLogService.log(
            admin.getEmail(),
            "DATABASE_CLEANUP",
            "USER",
            "ALL_EXCEPT_SPECIFIED",
            "Deleted " + usersDeleted + " users, keeping only: " + String.join(", ", keepEmails),
            clientIp
        );

        List<User> remainingUsers = userRepository.findAll();

        return ResponseEntity.ok(Map.of(
            "success", true,
            "deletedUsersCount", usersDeleted,
            "deletedRidesCount", ridesDeleted,
            "deletedBookingsCount", bookingsDeleted,
            "deletedApplicationsCount", applicationsDeleted,
            "remainingUsers", remainingUsers.stream().map(u -> Map.of(
                "id", u.getId(),
                "name", u.getName(),
                "email", u.getEmail(),
                "role", u.getRole()
            )).toList()
        ));
    }

    // ==========================================
    // 12. FAQ MANAGEMENT (DATABASE-DRIVEN)
    // ==========================================

    @GetMapping("/faqs")
    public ResponseEntity<?> getAllFaqsAdmin(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;
        return ResponseEntity.ok(faqItemRepository.findAllByOrderBySortOrderAsc());
    }

    @PostMapping("/faqs")
    public ResponseEntity<?> createFaq(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody FaqItem faqItem,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        if (faqItem.getQuestion() == null || faqItem.getQuestion().isBlank() ||
            faqItem.getAnswer() == null || faqItem.getAnswer().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Question and answer are required."));
        }

        if (faqItem.getCategory() == null || faqItem.getCategory().isBlank()) {
            faqItem.setCategory("General");
        }

        FaqItem saved = faqItemRepository.save(faqItem);
        auditLogService.log(
                admin.getEmail(),
                "CREATE_FAQ",
                "FAQ",
                String.valueOf(saved.getId()),
                "Created FAQ under category: " + saved.getCategory(),
                clientIp
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/faqs/{id}")
    public ResponseEntity<?> updateFaq(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody FaqItem req,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        Optional<FaqItem> optFaq = faqItemRepository.findById(id);
        if (optFaq.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "FAQ item not found."));

        FaqItem existing = optFaq.get();
        if (req.getCategory() != null && !req.getCategory().isBlank()) existing.setCategory(req.getCategory().trim());
        if (req.getQuestion() != null && !req.getQuestion().isBlank()) existing.setQuestion(req.getQuestion().trim());
        if (req.getAnswer() != null && !req.getAnswer().isBlank()) existing.setAnswer(req.getAnswer().trim());
        if (req.getActive() != null) existing.setActive(req.getActive());
        if (req.getSortOrder() != null) existing.setSortOrder(req.getSortOrder());

        FaqItem saved = faqItemRepository.save(existing);
        auditLogService.log(
                admin.getEmail(),
                "UPDATE_FAQ",
                "FAQ",
                String.valueOf(saved.getId()),
                "Updated FAQ: " + saved.getQuestion(),
                clientIp
        );

        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/faqs/{id}")
    public ResponseEntity<?> deleteFaq(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.MANAGE_SUPPORT);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        if (!faqItemRepository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "FAQ item not found."));
        }

        faqItemRepository.deleteById(id);
        auditLogService.log(
                admin.getEmail(),
                "DELETE_FAQ",
                "FAQ",
                String.valueOf(id),
                "Deleted FAQ ID: " + id,
                clientIp
        );

        return ResponseEntity.ok(Map.of("message", "FAQ item deleted successfully."));
    }

    // ==========================================
    // 13. SUPPORT ACCOUNTS & PERMISSIONS MANAGEMENT (ADMIN ONLY)
    // ==========================================

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentAdminOrSupport(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateStaff(authHeader, null);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User user = auth.user;
        List<String> permissions = supportPermissionService.getPermissionsForUser(user);
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "name", user.getName(),
                "email", user.getEmail(),
                "role", user.getRole(),
                "status", user.getStatus() != null ? user.getStatus() : "ACTIVE",
                "permissions", permissions
        ));
    }

    @GetMapping("/support-accounts")
    public ResponseEntity<?> getSupportAccounts(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;

        List<User> supportUsers = userRepository.findByRole("SUPPORT");
        List<Map<String, Object>> result = new ArrayList<>();
        for (User su : supportUsers) {
            List<String> perms = supportPermissionRepository.findPermissionNamesByUserId(su.getId());
            Map<String, Object> map = new HashMap<>();
            map.put("id", su.getId());
            map.put("name", su.getName());
            map.put("email", su.getEmail());
            map.put("phoneNumber", su.getPhoneNumber());
            map.put("status", su.getStatus() != null ? su.getStatus() : "ACTIVE");
            map.put("createdAt", su.getCreatedAt());
            map.put("permissions", perms);
            result.add(map);
        }
        return ResponseEntity.ok(Map.of(
                "supportAccounts", result,
                "availablePermissions", SupportPermissionService.ALL_PERMISSIONS
        ));
    }

    @PostMapping("/support-accounts")
    public ResponseEntity<?> createSupportAccount(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;

        String email = body.get("email") != null ? body.get("email").toString().trim().toLowerCase() : null;
        String name = body.get("name") != null ? body.get("name").toString().trim() : null;
        String password = body.get("password") != null ? body.get("password").toString() : null;
        String phoneNumber = body.get("phoneNumber") != null ? body.get("phoneNumber").toString().trim() : null;

        if (email == null || email.isBlank() || !email.contains("@")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Valid email is required."));
        }
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Name is required."));
        }
        if (password == null || password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("message", "Password must be at least 6 characters."));
        }

        if (userRepository.findByEmail(email) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "User with this email already exists."));
        }

        User support = new User();
        support.setName(name);
        support.setEmail(email);
        support.setPhoneNumber(phoneNumber);
        support.setPassword(passwordEncoder.encode(password));
        support.setRole("SUPPORT");
        support.setStatus("ACTIVE");
        support.setVerified(true);
        support.setCreatedAt(LocalDateTime.now());
        support.setReferralCode(UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        User saved = userRepository.save(support);

        Wallet wallet = new Wallet();
        wallet.setUser(saved);
        wallet.setBalance(0.0);
        walletRepository.save(wallet);

        // Permissions: Newly created Support accounts have NO privileged permissions by default
        List<String> assignedPerms = new ArrayList<>();
        if (body.get("permissions") instanceof List<?> list) {
            List<String> reqPerms = new ArrayList<>();
            for (Object item : list) {
                if (item != null) reqPerms.add(item.toString());
            }
            if (!reqPerms.isEmpty()) {
                assignedPerms = supportPermissionService.updateSupportPermissions(saved, reqPerms, auth.user.getEmail());
            }
        }

        auditLogService.log(
                auth.user.getEmail(),
                "CREATE_SUPPORT_ACCOUNT",
                "USER",
                String.valueOf(saved.getId()),
                "Created support account for " + email + " with initial permissions: " + assignedPerms,
                clientIp
        );

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Support account created successfully.",
                "user", Map.of(
                        "id", saved.getId(),
                        "name", saved.getName(),
                        "email", saved.getEmail(),
                        "role", saved.getRole(),
                        "status", saved.getStatus(),
                        "permissions", assignedPerms
                )
        ));
    }

    @PutMapping("/support-accounts/{id}/permissions")
    public ResponseEntity<?> updateSupportPermissions(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found"));
        }
        User supportUser = userOpt.get();
        if (!"SUPPORT".equalsIgnoreCase(supportUser.getRole())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Permissions can only be assigned to SUPPORT accounts."));
        }

        List<String> requested = new ArrayList<>();
        if (body.get("permissions") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) requested.add(item.toString());
            }
        }

        List<String> updated = supportPermissionService.updateSupportPermissions(supportUser, requested, auth.user.getEmail());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "userId", supportUser.getId(),
                "email", supportUser.getEmail(),
                "permissions", updated,
                "message", "Support permissions updated successfully."
        ));
    }

    // ==========================================
    // 14. SAFE DEVELOPMENT SEED & RESET WORKFLOW
    // ==========================================

    @PostMapping("/system/safe-cleanup-and-seed")
    public ResponseEntity<?> safeCleanupAndSeed(
            @RequestHeader("Authorization") String authHeader,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Map<String, Object> result = databaseCleanupAndSeedService.executeSafeCleanupAndSeed();
        auditLogService.log(
                auth.user.getEmail(),
                "SYSTEM_SAFE_CLEANUP_SEED",
                "DATABASE",
                "ALL",
                "Triggered safe database cleanup and seed of fixed test accounts.",
                clientIp
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/system/reset-test-rides")
    public ResponseEntity<?> resetTestRides(
            @RequestHeader("Authorization") String authHeader,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;

        Map<String, Object> result = databaseCleanupAndSeedService.resetTestRidesAndBookings();
        auditLogService.log(
                auth.user.getEmail(),
                "SYSTEM_RESET_TEST_RIDES",
                "DATABASE",
                "RIDES_BOOKINGS",
                "Reset test bookings and rides while preserving fixed accounts and car records.",
                clientIp
        );
        return ResponseEntity.ok(result);
    }

    @Autowired
    private com.rideshare.backend.service.SystemHealthMonitoringService systemHealthMonitoringService;

    @GetMapping("/system/verify-environment")
    public ResponseEntity<?> verifySystemEnvironment(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateStaff(authHeader, null);
        if (!auth.isAuthorized()) return auth.errorResponse;

        return ResponseEntity.ok(databaseCleanupAndSeedService.verifySystemState());
    }

    @GetMapping("/system/microservices-health")
    public ResponseEntity<?> getMicroservicesHealth(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Allow staff or provide general infrastructure status
        return ResponseEntity.ok(systemHealthMonitoringService.getFullSystemHealth());
    }

    // ==========================================
    // CANCELLATION POLICY & TIME-WINDOW RULES
    // ==========================================

    @GetMapping("/cancellation-policy")
    public ResponseEntity<?> getCancellationPolicy(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_FINANCE);
        if (!auth.isAuthorized()) return auth.errorResponse;

        PlatformSettings settings = platformSettingsRepository.findTopByOrderByIdAsc()
                .orElseGet(() -> platformSettingsRepository.save(PlatformSettings.builder().build()));
        List<CancellationPolicyRule> rules = cancellationPolicyRuleRepository.findByActiveTrueOrderByMinHoursBeforeDepartureDesc();

        Map<String, Object> response = new HashMap<>();
        response.put("cancellationFeeEnabled", Boolean.TRUE.equals(settings.getCancellationFeeEnabled()));
        response.put("defaultCancellationFee", settings.getDefaultCancellationFee() != null ? settings.getDefaultCancellationFee() : 0.0);
        response.put("cancellationFeeType", settings.getCancellationFeeType() != null ? settings.getCancellationFeeType() : "FIXED");
        response.put("cancellationGraceMinutes", settings.getCancellationGraceMinutes() != null ? settings.getCancellationGraceMinutes() : 10);
        response.put("cancellationPolicyNotice", settings.getCancellationPolicyNotice());
        response.put("rules", rules);

        return ResponseEntity.ok(response);
    }

    @PutMapping("/cancellation-policy")
    public ResponseEntity<?> updateCancellationPolicy(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        PlatformSettings settings = platformSettingsRepository.findTopByOrderByIdAsc()
                .orElseGet(() -> PlatformSettings.builder().build());

        if (payload.containsKey("cancellationFeeEnabled")) {
            settings.setCancellationFeeEnabled(Boolean.TRUE.equals(payload.get("cancellationFeeEnabled")));
        }
        if (payload.containsKey("defaultCancellationFee")) {
            double fee = Double.parseDouble(payload.get("defaultCancellationFee").toString());
            if (fee < 0) return ResponseEntity.badRequest().body(Map.of("message", "Fee cannot be negative"));
            settings.setDefaultCancellationFee(fee);
        }
        if (payload.containsKey("cancellationFeeType")) {
            String type = payload.get("cancellationFeeType").toString().toUpperCase();
            if (!"FIXED".equals(type) && !"PERCENTAGE".equals(type)) {
                return ResponseEntity.badRequest().body(Map.of("message", "Fee type must be FIXED or PERCENTAGE"));
            }
            settings.setCancellationFeeType(type);
        }
        if (payload.containsKey("cancellationGraceMinutes")) {
            int grace = Integer.parseInt(payload.get("cancellationGraceMinutes").toString());
            if (grace < 0) return ResponseEntity.badRequest().body(Map.of("message", "Grace minutes cannot be negative"));
            settings.setCancellationGraceMinutes(grace);
        }
        if (payload.containsKey("cancellationPolicyNotice")) {
            settings.setCancellationPolicyNotice((String) payload.get("cancellationPolicyNotice"));
        }

        settings.setUpdatedAt(LocalDateTime.now());
        settings.setUpdatedBy(admin.getEmail());
        PlatformSettings saved = platformSettingsRepository.save(settings);

        if (redisCacheService != null) {
            redisCacheService.evictSettingsCache();
        }

        auditLogService.log(
                admin.getEmail(),
                "UPDATE_CANCELLATION_POLICY",
                "SETTINGS",
                String.valueOf(saved.getId()),
                "Cancellation policy updated: enabled=" + saved.getCancellationFeeEnabled() + ", defaultFee=" + saved.getDefaultCancellationFee(),
                clientIp
        );

        return ResponseEntity.ok(saved);
    }

    @PostMapping("/cancellation-policy/rules")
    public ResponseEntity<?> saveCancellationRule(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody CancellationPolicyRule rule,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        if (rule.getFeeAmount() == null || rule.getFeeAmount() < 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Fee amount cannot be negative"));
        }
        if (rule.getMinHoursBeforeDeparture() == null) {
            rule.setMinHoursBeforeDeparture(0.0);
        }
        if (rule.getFeeType() == null || rule.getFeeType().trim().isEmpty()) {
            rule.setFeeType("PERCENTAGE");
        }
        rule.setFeeType(rule.getFeeType().toUpperCase());
        if (!"FIXED".equals(rule.getFeeType()) && !"PERCENTAGE".equals(rule.getFeeType())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Fee type must be FIXED or PERCENTAGE"));
        }
        if ("PERCENTAGE".equals(rule.getFeeType()) && rule.getFeeAmount() > 100.0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Percentage fee cannot exceed 100%"));
        }

        rule.setUpdatedAt(LocalDateTime.now());
        CancellationPolicyRule saved = cancellationPolicyRuleRepository.save(rule);

        if (redisCacheService != null) {
            redisCacheService.evictSettingsCache();
        }

        auditLogService.log(
                admin.getEmail(),
                "SAVE_CANCELLATION_RULE",
                "CANCELLATION_RULE",
                String.valueOf(saved.getId()),
                "Saved rule: window=" + saved.getMinHoursBeforeDeparture() + "h to " + saved.getMaxHoursBeforeDeparture() + "h, fee=" + saved.getFeeAmount() + " " + saved.getFeeType(),
                clientIp
        );

        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/cancellation-policy/rules/{id}")
    public ResponseEntity<?> deleteCancellationRule(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        if (cancellationPolicyRuleRepository.existsById(id)) {
            cancellationPolicyRuleRepository.deleteById(id);
            if (redisCacheService != null) {
                redisCacheService.evictSettingsCache();
            }
            auditLogService.log(
                    admin.getEmail(),
                    "DELETE_CANCELLATION_RULE",
                    "CANCELLATION_RULE",
                    String.valueOf(id),
                    "Deleted cancellation rule id=" + id,
                    clientIp
            );
            return ResponseEntity.ok(Map.of("message", "Rule deleted successfully", "id", id));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Rule not found"));
        }
    }

    // ==========================================
    // REDIS CACHE TELEMETRY & FLUSH
    // ==========================================

    @GetMapping("/system/cache-stats")
    public ResponseEntity<?> getCacheStats(@RequestHeader("Authorization") String authHeader) {
        StaffAuthResult auth = authenticateStaff(authHeader, SupportPermissionService.VIEW_DASHBOARD);
        if (!auth.isAuthorized()) return auth.errorResponse;

        if (redisCacheService != null) {
            return ResponseEntity.ok(redisCacheService.getCacheStats());
        }
        return ResponseEntity.ok(Map.of("enabled", false, "message", "Redis cache service not initialized"));
    }

    @PostMapping("/system/cache/flush")
    public ResponseEntity<?> flushCache(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(defaultValue = "all") String target,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        StaffAuthResult auth = authenticateAdminOnly(authHeader);
        if (!auth.isAuthorized()) return auth.errorResponse;
        User admin = auth.user;

        if (redisCacheService != null) {
            if ("rides".equalsIgnoreCase(target)) {
                redisCacheService.deletePattern("rides:*");
                redisCacheService.deletePattern("ride:*");
            } else if ("settings".equalsIgnoreCase(target)) {
                redisCacheService.evictSettingsCache();
            } else {
                redisCacheService.deletePattern("rides:*");
                redisCacheService.deletePattern("ride:*");
                redisCacheService.evictSettingsCache();
            }
            auditLogService.log(admin.getEmail(), "FLUSH_CACHE", "SYSTEM", "REDIS", "Admin flushed cache: target=" + target, clientIp);
            return ResponseEntity.ok(Map.of("success", true, "target", target, "message", "Cache invalidated successfully"));
        }
        return ResponseEntity.ok(Map.of("success", false, "message", "Redis not enabled"));
    }

}
