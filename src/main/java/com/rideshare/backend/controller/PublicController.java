package com.rideshare.backend.controller;

import com.rideshare.backend.model.FaqItem;
import com.rideshare.backend.model.PlatformSettings;
import com.rideshare.backend.model.SupportTicket;
import com.rideshare.backend.repository.*;
import com.rideshare.backend.service.EmailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/public")
public class PublicController {

    @Autowired
    private SupportTicketRepository supportTicketRepository;

    @Autowired
    private PlatformSettingsRepository platformSettingsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EmailService emailService;

    @Autowired(required = false)
    private com.rideshare.backend.service.RedisCacheService redisCacheService;

    // In-memory rate limiting tracker: ip -> list of submission timestamps
    private final Map<String, List<Long>> rateLimitMap = new HashMap<>();

    /**
     * Public Contact Form submission with validation and spam protection
     */
    @PostMapping("/contact")
    public ResponseEntity<?> submitContactForm(@RequestBody Map<String, String> payload,
                                               @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {
        String name = payload.get("name");
        String email = payload.get("email");
        String phone = payload.get("phone");
        String category = payload.get("category");
        String subject = payload.get("subject");
        String message = payload.get("message");

        // Input validation
        if (name == null || name.trim().length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("message", "Please provide a valid full name."));
        }
        if (email == null || !email.contains("@") || email.trim().length() < 5) {
            return ResponseEntity.badRequest().body(Map.of("message", "A valid email address is required."));
        }
        if (subject == null || subject.trim().length() < 3) {
            return ResponseEntity.badRequest().body(Map.of("message", "Please specify a subject for your message."));
        }
        if (message == null || message.trim().length() < 10) {
            return ResponseEntity.badRequest().body(Map.of("message", "Message must be at least 10 characters long."));
        }

        // Simple IP rate limit (maximum 5 submissions in 10 minutes) with periodic cleanup
        String ipKey = clientIp != null ? clientIp.split(",")[0].trim() : "unknown-ip";
        long now = System.currentTimeMillis();
        synchronized (rateLimitMap) {
            // Periodic purge of all expired IP buckets when map grows large
            if (rateLimitMap.size() > 500) {
                rateLimitMap.entrySet().removeIf(entry -> {
                    entry.getValue().removeIf(t -> now - t > 600000);
                    return entry.getValue().isEmpty();
                });
            }

            List<Long> timestamps = rateLimitMap.computeIfAbsent(ipKey, k -> new ArrayList<>());
            timestamps.removeIf(t -> now - t > 600000); // 10 minutes window
            if (timestamps.size() >= 5) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("message", "Too many messages sent from your network. Please wait a few minutes before trying again."));
            }
            timestamps.add(now);
        }

        // Create support ticket
        String ticketNumber = "TCK-" + (100000 + (long)(Math.random() * 900000));
        SupportTicket ticket = SupportTicket.builder()
                .ticketNumber(ticketNumber)
                .name(name.trim())
                .email(email.trim().toLowerCase())
                .phoneNumber(phone != null ? phone.trim() : null)
                .category(category != null && !category.isBlank() ? category.trim().toUpperCase() : "GENERAL")
                .subject(subject.trim())
                .message(message.trim())
                .status("OPEN")
                .priority("MEDIUM")
                .createdAt(java.time.LocalDateTime.now())
                .build();

        supportTicketRepository.save(ticket);

        // Send confirmation email asynchronously if mail is configured
        if (emailService.isConfigured()) {
            emailService.sendPlainTextEmail(
                    email.trim(),
                    "RideShare Support Ticket Confirmation: " + ticketNumber,
                    "Hello " + name + ",\n\nThank you for reaching out to RideShare support. We have received your message regarding: \"" + subject.trim() + "\".\n\nTicket Reference: " + ticketNumber + "\nStatus: OPEN\n\nOur customer care team will review your inquiry and respond shortly.\n\nBest regards,\nRideShare Support Team"
            );
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "success", true,
                "ticketNumber", ticketNumber,
                "message", "Your message has been received! Our support team will respond shortly."
        ));
    }

    @Autowired
    private FaqItemRepository faqItemRepository;

    @jakarta.annotation.PostConstruct
    public void seedInitialFaqsIfEmpty() {
        if (faqItemRepository.count() == 0) {
            List<FaqItem> defaultFaqs = List.of(
                    FaqItem.builder()
                            .category("Riding")
                            .question("How do I find and book a ride?")
                            .answer("Enter your pickup and destination in the app, browse published routes by verified drivers, select your seats, and confirm your booking instantly.")
                            .sortOrder(1)
                            .active(true)
                            .build(),
                    FaqItem.builder()
                            .category("Riding")
                            .question("What payment methods are supported?")
                            .answer("RideShare supports Online Card payments, UPI / QR payments, In-app Wallet, and direct Cash to driver upon arrival.")
                            .sortOrder(2)
                            .active(true)
                            .build(),
                    FaqItem.builder()
                            .category("Driving")
                            .question("What are the requirements to become a RideShare driver?")
                            .answer("You must be at least 21 years old, have a valid government-issued Driving Licence, a registered and insured vehicle, and pass our administrative verification.")
                            .sortOrder(3)
                            .active(true)
                            .build(),
                    FaqItem.builder()
                            .category("Driving")
                            .question("How much commission does RideShare charge?")
                            .answer("RideShare charges a fair and transparent 15% platform commission plus a ₹20 fixed technology fee per completed cash seat. You keep 100% of driver tips.")
                            .sortOrder(4)
                            .active(true)
                            .build(),
                    FaqItem.builder()
                            .category("Safety")
                            .question("How does RideShare verify drivers?")
                            .answer("Every driver must submit their driving licence front/back, vehicle Registration Certificate (RC), and active insurance policy for manual administrative review before publishing trips.")
                            .sortOrder(5)
                            .active(true)
                            .build(),
                    FaqItem.builder()
                            .category("Cancellations")
                            .question("Can I cancel a booking?")
                            .answer("Yes, passengers can cancel confirmed bookings before trip departure. Automatic refund processing applies to prepaid online transactions.")
                            .sortOrder(6)
                            .active(true)
                            .build()
            );
            faqItemRepository.saveAll(defaultFaqs);
        }
    }

    /**
     * Public platform configuration and contact information
     */
    @GetMapping("/settings")
    public ResponseEntity<?> getPublicSettings() {
        PlatformSettings settings = null;
        if (redisCacheService != null) {
            java.util.Optional<PlatformSettings> cached = redisCacheService.get(
                    com.rideshare.backend.service.RedisCacheService.PREFIX_PLATFORM_SETTINGS, PlatformSettings.class);
            if (cached.isPresent()) {
                settings = cached.get();
            }
        }
        if (settings == null) {
            settings = platformSettingsRepository.findAll().stream().findFirst().orElse(null);
            if (settings != null && redisCacheService != null) {
                redisCacheService.set(com.rideshare.backend.service.RedisCacheService.PREFIX_PLATFORM_SETTINGS,
                        settings, com.rideshare.backend.service.RedisCacheService.TTL_SETTINGS);
            }
        }
        Map<String, Object> resp = new HashMap<>();
        if (settings != null) {
            resp.put("companyName", settings.getCompanyName());
            resp.put("supportEmail", settings.getSupportEmail());
            resp.put("supportPhone", settings.getSupportPhone());
            resp.put("companyAddress", settings.getCompanyAddress());
            resp.put("commissionRatePercent", settings.getCommissionRatePercent());
            resp.put("commissionFixedFee", settings.getCommissionFixedFee());
            resp.put("outstandingThreshold", settings.getOutstandingThreshold());
            resp.put("emailNotificationsEnabled", settings.getEmailNotificationsEnabled());
        } else {
            resp.put("companyName", "RideShare Mobility Technologies");
            resp.put("supportEmail", "");
            resp.put("supportPhone", "");
            resp.put("companyAddress", "");
            resp.put("commissionRatePercent", 15.0);
            resp.put("commissionFixedFee", 20.0);
            resp.put("outstandingThreshold", 500.0);
            resp.put("emailNotificationsEnabled", true);
        }
        resp.put("mobileAppDownloadUrlIos", "https://apps.apple.com/app/rideshare");
        resp.put("mobileAppDownloadUrlAndroid", "https://play.google.com/store/apps/details?id=com.rideshare.app");
        resp.put("availableCities", List.of("Bengaluru", "New Delhi", "Mumbai", "Hyderabad", "Pune", "Gurgaon", "Noida", "Chennai"));

        // Both flat map and nested "settings" key for consumer compatibility
        resp.put("settings", new HashMap<>(resp));

        return ResponseEntity.ok(resp);
    }

    /**
     * Public aggregate platform statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getPublicStats() {
        long totalUsers = userRepository.count();
        long totalDrivers = userRepository.countByRole("DRIVER");
        long completedRides = rideRepository.countByStatus("COMPLETED");
        long totalBookings = bookingRepository.count();
        long publishedRides = rideRepository.count();
        double carbonOffset = completedRides * 4.2;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalUsers", totalUsers);
        stats.put("registeredUsers", totalUsers);
        stats.put("totalRidersAndDrivers", totalUsers);
        stats.put("verifiedDrivers", totalDrivers);
        stats.put("completedTrips", completedRides);
        stats.put("ridesPublished", publishedRides);
        stats.put("totalBookings", totalBookings);
        stats.put("carbonOffsetKg", Math.round(carbonOffset * 10.0) / 10.0);
        stats.put("citiesActive", 8);
        stats.put("safetyScore", 99.4);

        Map<String, Object> resp = new HashMap<>(stats);
        resp.put("stats", stats);

        return ResponseEntity.ok(resp);
    }

    /**
     * Public FAQs - loaded directly from database faq_items table
     */
    @GetMapping("/faqs")
    public ResponseEntity<?> getPublicFaqs() {
        List<FaqItem> faqs = faqItemRepository.findByActiveTrueOrderBySortOrderAsc();
        return ResponseEntity.ok(faqs);
    }

    @Autowired
    private com.rideshare.backend.service.EmailEngagementService emailEngagementService;

    /**
     * Public Pre-Launch Waitlist Signup
     */
    @PostMapping("/waitlist")
    public ResponseEntity<?> joinWaitlist(@RequestBody Map<String, Object> payload) {
        String email = (String) payload.get("email");
        String name = (String) payload.get("name");
        String phone = (String) payload.get("phone");
        String city = (String) payload.get("city");
        String role = (String) payload.getOrDefault("role", "PASSENGER");
        Boolean consent = payload.containsKey("consent") ? (Boolean) payload.get("consent") : true;

        if (email == null || !email.contains("@") || email.trim().length() < 5) {
            return ResponseEntity.badRequest().body(Map.of("message", "A valid email address is required."));
        }

        try {
            com.rideshare.backend.model.EmailAudienceContact contact = emailEngagementService.addOrUpdateContact(
                    email, name, phone, city, role, "WAITLIST_FORM", consent
            );
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Thank you for joining the RideShare waitlist! We have reserved your priority access.",
                    "audienceType", contact.getAudienceType(),
                    "email", contact.getEmail()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Public One-Click Unsubscribe Handler
     */
    @PostMapping("/unsubscribe")
    public ResponseEntity<?> processUnsubscribe(@RequestBody Map<String, String> payload) {
        String token = payload.get("token");
        String reason = payload.get("reason");

        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Unsubscribe token is required."));
        }

        boolean success = emailEngagementService.unsubscribeContactByToken(token, reason);
        if (success) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "You have been successfully unsubscribed from RideShare marketing and engagement emails."
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Invalid or expired unsubscribe token."
            ));
        }
    }

    @Autowired
    private com.rideshare.backend.service.SystemHealthMonitoringService systemHealthMonitoringService;

    /**
     * Public Health & Readiness check for Load Balancers (Nginx, AWS ALB, Docker)
     */
    @GetMapping("/health")
    public ResponseEntity<?> getHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "RideShare Backend API");
        health.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(health);
    }

    @GetMapping("/health/microservices")
    public ResponseEntity<?> getPublicMicroservicesHealth() {
        return ResponseEntity.ok(systemHealthMonitoringService.getFullSystemHealth());
    }
}

