# RideShare Backend — Architecture & Codebase Audit Report

**Date of Audit:** October 24, 2026  
**Auditor:** Senior Java Spring Boot Architect  
**Target Repository:** `rideShare-backend` (`com.rideshare:backend:0.0.1-SNAPSHOT`)  
**Assessment Framework:** Layered Architecture, Concurrency & Security, Domain Completeness, Production Readiness  

---

## 1. Project Overview

| Attribute | Inspected Value / Configuration |
| :--- | :--- |
| **Project Name & Artifact** | `rideshare` / `com.rideshare:backend:0.0.1-SNAPSHOT` |
| **Java Version** | **Java 17** (`<java.version>17</java.version>`) |
| **Spring Boot Version** | **4.1.1** (`spring-boot-starter-parent:4.1.1`) *(Experimental preview generation)* |
| **Build Tool** | **Apache Maven** (Wrapper included: `mvnw`, `mvnw.cmd`, `pom.xml`) |
| **Main Application Entry** | [RideshareApplication.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/RideshareApplication.java) (Annotated with `@SpringBootApplication`, `@EnableAsync`, `@EnableScheduling`, `@EnableJpaRepositories`) |
| **Primary Database & Driver** | **PostgreSQL** (`org.postgresql.Driver`, `postgresql:runtime`) running with **HikariCP** pool |
| **In-Memory / Test DB** | **H2 Database** (`com.h2database:h2:runtime`) used for integration test harness |
| **Caching & In-Memory Store** | **Redis Cloud / Lettuce** (`spring-boot-starter-data-redis` with custom [RedisConfig.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/RedisConfig.java) and [RedisCacheService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/RedisCacheService.java)) |
| **Authentication & AuthZ** | **Stateless JWT** (`jjwt-api:0.11.5`, `jjwt-impl`, `jjwt-jackson`), Spring Security `SecurityFilterChain`, BCrypt hashing, custom role extraction (`ROLE_RIDER`, `ROLE_DRIVER`, `ROLE_ADMIN`, `ROLE_SUPPORT`) |
| **External Payment Gateway** | **Razorpay Test Gateway** ([RazorpayService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/RazorpayService.java)), HMAC-SHA256 signature verification, Orders API, Webhook receiver, mock test mode fallback |
| **External Email Gateways** | **Brevo Transactional HTTP API** ([BrevoEmailService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/BrevoEmailService.java)) as primary; **Gmail SMTP** (`spring-boot-starter-mail`) as secondary fallback |
| **External Document Storage** | **Google Drive API v3** ([GoogleDriveStorageService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/GoogleDriveStorageService.java)) with local filesystem vault fallback ([DocumentStorageService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/DocumentStorageService.java)) |
| **External Mapping / Ola Maps** | **Client-Provided Distance & Verified Toll Data**: The backend does *not* call Ola Maps directly over HTTP. Instead, [CarpoolPricingController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/CarpoolPricingController.java) and [CarpoolPricingService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/CarpoolPricingService.java) accept verified Ola Maps route distance and toll figures submitted by the mobile client/frontend. |
| **Real-Time Communication** | **Raw Spring WebSockets** (`@EnableWebSocket`, `WebSocketConfigurer`) exposing `/ws/location` and `/ws/admin/monitoring` |
| **Environment Variable Names** | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SPRING_JPA_HIBERNATE_DDL_AUTO`, `BREVO_API_KEY`, `BREVO_SENDER_EMAIL`, `BREVO_SENDER_NAME`, `BREVO_API_URL`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_APP_PASSWORD`, `MAIL_FROM`, `MAIL_FROM_NAME`, `MAIL_ENABLED`, `OTP_EXPIRATION_MINUTES`, `OTP_RESEND_COOLDOWN_SECONDS`, `GOOGLE_DRIVE_PARENT_FOLDER_ID`, `GOOGLE_APPLICATION_CREDENTIALS`, `RAZORPAY_MODE`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `JWT_SECRET`, `JWT_EXPIRATION_MS`, `CORS_ALLOWED_ORIGINS`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_ENABLED` |

*(All secrets, tokens, passwords, and private key references have been strictly redacted).*

---

## 2. Complete Folder Structure

```
rideShare-backend/
├── pom.xml
├── Dockerfile
├── HELP.md
├── README.md
├── BREVO_OTP_SETUP.md
├── query.sql
├── start.cmd
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/
│   │   │       └── rideshare/
│   │   │           └── backend/
│   │   │               ├── RideshareApplication.java
│   │   │               │
│   │   │               ├── component/
│   │   │               │   └── DataInitializer.java
│   │   │               │
│   │   │               ├── config/
│   │   │               │   ├── AdminBootstrapRunner.java
│   │   │               │   ├── AdminDriverMonitoringWebSocketHandler.java
│   │   │               │   ├── CommissionProperties.java
│   │   │               │   ├── DatabaseConstraintInitializer.java
│   │   │               │   ├── DriverTrackingProperties.java
│   │   │               │   ├── DummyDataSeeder.java
│   │   │               │   ├── LocationHandler.java
│   │   │               │   ├── RedisConfig.java
│   │   │               │   ├── SecurityConfig.java
│   │   │               │   └── WebSocketConfig.java
│   │   │               │
│   │   │               ├── controller/
│   │   │               │   ├── AdminController.java
│   │   │               │   ├── AdminCouponController.java
│   │   │               │   ├── AdminDriverMonitoringController.java
│   │   │               │   ├── AdminEmailEngagementController.java
│   │   │               │   ├── AdminTranslationController.java
│   │   │               │   ├── AuthController.java
│   │   │               │   ├── BookingController.java
│   │   │               │   ├── CarpoolPricingController.java
│   │   │               │   ├── CommissionController.java
│   │   │               │   ├── CouponController.java
│   │   │               │   ├── DriverController.java
│   │   │               │   ├── DriverPresenceController.java
│   │   │               │   ├── EmailController.java
│   │   │               │   ├── GstAdminController.java
│   │   │               │   ├── InvoiceController.java
│   │   │               │   ├── LocationController.java
│   │   │               │   ├── NotificationController.java
│   │   │               │   ├── PaymentController.java
│   │   │               │   ├── PublicController.java
│   │   │               │   ├── RecurringRideController.java
│   │   │               │   ├── ReferralController.java
│   │   │               │   ├── RideController.java
│   │   │               │   ├── TranslationController.java
│   │   │               │   ├── UserController.java
│   │   │               │   ├── UserDocumentController.java
│   │   │               │   ├── VehicleController.java
│   │   │               │   └── WalletController.java
│   │   │               │
│   │   │               ├── dto/
│   │   │               │   ├── AuthResponse.java
│   │   │               │   ├── CarpoolPriceRecommendRequest.java
│   │   │               │   ├── CarpoolPriceRecommendResponse.java
│   │   │               │   ├── CouponAnalyticsDto.java
│   │   │               │   ├── CouponDto.java
│   │   │               │   ├── CouponOfferDto.java
│   │   │               │   ├── CouponValidationRequest.java
│   │   │               │   ├── CouponValidationResponse.java
│   │   │               │   ├── CreateBookingRequest.java
│   │   │               │   ├── CreatePaymentOrderRequest.java
│   │   │               │   ├── CreateSettlementOrderRequest.java
│   │   │               │   ├── DriverApplicationRequest.java
│   │   │               │   ├── DriverHeartbeatRequest.java
│   │   │               │   ├── DriverMonitoringItemDto.java
│   │   │               │   ├── DriverMonitoringMetricsDto.java
│   │   │               │   ├── ForgotPasswordRequest.java
│   │   │               │   ├── LoginRequest.java
│   │   │               │   ├── RecurringRideScheduleRequest.java
│   │   │               │   ├── RegisterRequest.java
│   │   │               │   ├── ResendOtpRequest.java
│   │   │               │   ├── ResetPasswordRequest.java
│   │   │               │   ├── ReviewApplicationRequest.java
│   │   │               │   ├── VerifyOtpRequest.java
│   │   │               │   ├── VerifyPaymentRequest.java
│   │   │               │   └── VerifySettlementRequest.java
│   │   │               │
│   │   │               ├── model/
│   │   │               │   ├── AuditLog.java
│   │   │               │   ├── Booking.java
│   │   │               │   ├── CancellationPolicyRule.java
│   │   │               │   ├── CarMake.java
│   │   │               │   ├── CarModel.java
│   │   │               │   ├── CommissionLedger.java
│   │   │               │   ├── Coupon.java
│   │   │               │   ├── CouponRedemption.java
│   │   │               │   ├── DriverApplication.java
│   │   │               │   ├── DriverLocation.java
│   │   │               │   ├── DriverPayout.java
│   │   │               │   ├── DriverPayoutProfile.java
│   │   │               │   ├── DriverPresence.java
│   │   │               │   ├── EmailAudienceContact.java
│   │   │               │   ├── EmailCampaign.java
│   │   │               │   ├── EmailLog.java
│   │   │               │   ├── EmailWorkflow.java
│   │   │               │   ├── EmailWorkflowExecution.java
│   │   │               │   ├── FaqItem.java
│   │   │               │   ├── GstSettings.java
│   │   │               │   ├── GstSettingsHistory.java
│   │   │               │   ├── Invoice.java
│   │   │               │   ├── Notification.java
│   │   │               │   ├── OtpVerification.java
│   │   │               │   ├── PaymentOrder.java
│   │   │               │   ├── PaymentRefund.java
│   │   │               │   ├── PlatformSettings.java
│   │   │               │   ├── RecurringRideSchedule.java
│   │   │               │   ├── Referral.java
│   │   │               │   ├── Ride.java
│   │   │               │   ├── SupportPermission.java
│   │   │               │   ├── SupportTicket.java
│   │   │               │   ├── Transaction.java
│   │   │               │   ├── Translation.java
│   │   │               │   ├── User.java
│   │   │               │   ├── UserDocument.java
│   │   │               │   ├── Vehicle.java
│   │   │               │   └── Wallet.java
│   │   │               │
│   │   │               ├── repository/
│   │   │               │   ├── AuditLogRepository.java
│   │   │               │   ├── BookingRepository.java
│   │   │               │   ├── CancellationPolicyRuleRepository.java
│   │   │               │   ├── CarMakeRepository.java
│   │   │               │   ├── CarModelRepository.java
│   │   │               │   ├── CommissionLedgerRepository.java
│   │   │               │   ├── CouponRedemptionRepository.java
│   │   │               │   ├── CouponRepository.java
│   │   │               │   ├── DriverApplicationRepository.java
│   │   │               │   ├── DriverLocationRepository.java
│   │   │               │   ├── DriverPayoutProfileRepository.java
│   │   │               │   ├── DriverPayoutRepository.java
│   │   │               │   ├── DriverPresenceRepository.java
│   │   │               │   ├── EmailAudienceContactRepository.java
│   │   │               │   ├── EmailCampaignRepository.java
│   │   │               │   ├── EmailLogRepository.java
│   │   │               │   ├── EmailWorkflowExecutionRepository.java
│   │   │               │   ├── EmailWorkflowRepository.java
│   │   │               │   ├── FaqItemRepository.java
│   │   │               │   ├── GstSettingsHistoryRepository.java
│   │   │               │   ├── GstSettingsRepository.java
│   │   │               │   ├── InvoiceRepository.java
│   │   │               │   ├── NotificationRepository.java
│   │   │               │   ├── OtpVerificationRepository.java
│   │   │               │   ├── PaymentOrderRepository.java
│   │   │               │   ├── PaymentRefundRepository.java
│   │   │               │   ├── PlatformSettingsRepository.java
│   │   │               │   ├── RecurringRideScheduleRepository.java
│   │   │               │   ├── ReferralRepository.java
│   │   │               │   ├── RideRepository.java
│   │   │               │   ├── SupportPermissionRepository.java
│   │   │               │   ├── SupportTicketRepository.java
│   │   │               │   ├── TransactionRepository.java
│   │   │               │   ├── TranslationRepository.java
│   │   │               │   ├── UserDocumentRepository.java
│   │   │               │   ├── UserRepository.java
│   │   │               │   ├── VehicleRepository.java
│   │   │               │   └── WalletRepository.java
│   │   │               │
│   │   │               ├── security/
│   │   │               │   ├── JwtFilter.java
│   │   │               │   └── JwtUtil.java
│   │   │               │
│   │   │               └── service/
│   │   │                   ├── AuditLogService.java
│   │   │                   ├── AuthService.java
│   │   │                   ├── BookingService.java
│   │   │                   ├── BrevoEmailService.java
│   │   │                   ├── CancellationFeeCalculationService.java
│   │   │                   ├── CarpoolPricingService.java
│   │   │                   ├── CommissionService.java
│   │   │                   ├── CouponService.java
│   │   │                   ├── DatabaseCleanupAndSeedService.java
│   │   │                   ├── DocumentStorageService.java
│   │   │                   ├── DriverService.java
│   │   │                   ├── DriverTrackingService.java
│   │   │                   ├── EmailEngagementService.java
│   │   │                   ├── EmailService.java
│   │   │                   ├── EmailTemplateService.java
│   │   │                   ├── FeeCalculationService.java
│   │   │                   ├── GoogleDriveStorageService.java
│   │   │                   ├── GstCalculationService.java
│   │   │                   ├── GstSettingsService.java
│   │   │                   ├── InvoiceService.java
│   │   │                   ├── NotificationService.java
│   │   │                   ├── OtpDeliveryService.java
│   │   │                   ├── PaymentService.java
│   │   │                   ├── PayoutService.java
│   │   │                   ├── RazorpayService.java
│   │   │                   ├── RecurringRideService.java
│   │   │                   ├── RedisCacheService.java
│   │   │                   ├── RideService.java
│   │   │                   ├── SupportPermissionService.java
│   │   │                   ├── SystemHealthMonitoringService.java
│   │   │                   ├── TranslationService.java
│   │   │                   └── UserDocumentService.java
│   │   │
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── static/
│   │       │   ├── index.html
│   │       │   └── images/
│   │       │       ├── city-and-car.png
│   │       │       ├── city-and-car-banner.jpg
│   │       │       ├── city-and-car-banner.png
│   │       │       ├── city-and-car-hero-overlay.jpg
│   │       │       ├── city-and-car-hero-overlay.png
│   │       │       └── logo.png
│   │       └── templates/
│   │
│   └── test/
│       ├── java/
│       │   └── com/
│       │       └── rideshare/
│       │           └── backend/
│       │               ├── AdminAndPublicSystemIntegrationTest.java
│       │               ├── AuthIntegrationTest.java
│       │               ├── CarpoolPricingServiceTest.java
│       │               ├── DatabaseAuditTest.java
│       │               ├── DatabaseBackupExportTest.java
│       │               ├── DatabaseCleanupAndSeedTest.java
│       │               ├── DriverMonitoringIntegrationTest.java
│       │               ├── FeeAndCommissionSystemTest.java
│       │               ├── OfficeCarpoolingIntegrationTest.java
│       │               ├── PaymentAndSettlementIntegrationTest.java
│       │               ├── PostgresIntegrationTest.java
│       │               ├── RideLifecycleIntegrationTest.java
│       │               ├── RideshareApplicationTests.java
│       │               ├── RoleBasedSystemIntegrationTest.java
│       │               ├── SupportPermissionsAndAuthIntegrationTest.java
│       │               └── service/
│       │                   ├── CancellationFeeCalculationServiceTest.java
│       │                   ├── CouponServiceTest.java
│       │                   ├── EmailEngagementServiceTest.java
│       │                   ├── EmailServiceTest.java
│       │                   └── RedisCacheServiceTest.java
│       └── resources/
│           └── application.properties
```

---

## 3. Architecture Analysis

### Architectural Pattern: Layered Architecture with Leaks

The application adheres in principle to the classic **Controller → Service → Repository → Database** model, but exhibits severe layer violations and structural anti-patterns:

```
[ HTTP Clients / Mobile App / Admin Web ]
               │
               ▼
┌────────────────────────────────────────┐
│  Security Layer (JwtFilter / SecConfig) │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│           Controller Layer             │ ◄── ⚠️ Direct DB queries, @Transactional,
└──────────────────┬─────────────────────┘      direct logic inside AdminController &
                   │                            WalletController
                   ▼
┌────────────────────────────────────────┐
│            Service Layer               │ ◄── Mixed responsibility (e.g. EmailEngagement
└──────────────────┬─────────────────────┘      renders HTML, manages contacts, executes queue)
                   │
                   ▼
┌────────────────────────────────────────┐
│           Repository Layer             │
└──────────────────┬─────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────┐
│  Database (PostgreSQL / Supabase / H2) │
└────────────────────────────────────────┘
```

### Layer Analysis & Identified Violations:

1. **God Controller Anti-Pattern ([AdminController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/AdminController.java)):**
   - Contains **2,634 lines of code**.
   - Directly autowires **10 repositories** (`UserRepository`, `DriverApplicationRepository`, `RideRepository`, `BookingRepository`, `CommissionLedgerRepository`, `PaymentOrderRepository`, `DriverPayoutRepository`, `PaymentRefundRepository`, etc.).
   - Implements substantial transactional business logic directly in HTTP endpoint handler methods (e.g., driver approval, booking cancellation, manual settlements, refunds, user suspensions).
   - Bypasses domain services for numerous actions, breaking encapsulation.

2. **Controller-Level Transaction Execution ([WalletController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/WalletController.java#L129)):**
   - `@Transactional(isolation = Isolation.SERIALIZABLE)` is declared on `verifyWalletTopup()` inside the controller class.
   - The controller executes repository operations (`userRepository.findByEmail`, `transactionRepository.findByWalletIdOrderByCreatedAtDesc`, `walletRepository.save`, `transactionRepository.save`) directly.
   - Database operations and Razorpay signature verification logic must reside in a dedicated `WalletService`.

3. **Missing Global Exception Handling (`@ControllerAdvice`):**
   - There is **no `@ControllerAdvice` or `@RestControllerAdvice`** in the entire codebase.
   - No standardized API response envelope for exceptions (`ApiErrorResponse`).
   - Unhandled exceptions bubble up to Spring Boot's default error page/dispatcher (`/error`), risking internal stack trace disclosure.
   - Individual controllers and services resort to inconsistent manual `try-catch` blocks and `Map.of("message", ...)` responses.

4. **DTO vs Entity Leakage:**
   - Entities (`Ride`, `Booking`, `User`, `UserDocument`) are repeatedly accepted directly in `@RequestBody` parameters (e.g., [RideController.java:51](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/RideController.java#L51)) and returned directly as HTTP response bodies.
   - JPA entity fields such as `User.password` rely on Jackson annotations (`@JsonProperty(access = Access.WRITE_ONLY)`) rather than clean separation via DTO request/response models.

5. **Schema Evolution via Java Runner ([DatabaseConstraintInitializer.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/DatabaseConstraintInitializer.java)):**
   - DDL statements (`ALTER TABLE`, `CREATE INDEX IF NOT EXISTS`) and data migrations are run inside a Spring `CommandLineRunner` using raw SQL on startup.
   - No versioned database migration framework (Flyway or Liquibase) is installed.

---

## 4. RideShare Feature Inventory

| # | Feature | Status | Primary Implementation File(s) | Architectural Evidence & Notes |
| :--- | :--- | :--- | :--- | :--- |
| 1 | **User Registration & Login** | **Implemented** | [AuthController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/AuthController.java), [AuthService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/AuthService.java) | Email/password registration, BCrypt password hashing, JWT issuance, OTP generation and verification via Brevo/SMTP. |
| 2 | **User Profiles** | **Implemented** | [UserController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/UserController.java), [UserDocumentController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/UserDocumentController.java) | Profile view/update, home/office commuting coordinates, company name, identity document upload (Aadhaar/Passport/DL). |
| 3 | **Driver Profiles & Vehicle Verification** | **Implemented** | [DriverController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/DriverController.java), [DriverService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/DriverService.java), [UserDocumentService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/UserDocumentService.java) | Driver application onboarding, document uploads (DL, RC, Insurance), admin review lifecycle (`PENDING_REVIEW`, `APPROVED`, `REJECTED`). Automatic vehicle association. |
| 4 | **Ride Publishing & Route Selection** | **Implemented** | [RideController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/RideController.java), [RideService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/RideService.java#L213-L363) | Publishes rides with departure time, total seats, available seats, pickup/dropoff coordinates. Enforces driver approval and vehicle registration before publishing. |
| 5 | **Stopovers / Multi-point Routing** | **Missing** | [Ride.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/model/Ride.java) | Entity only contains single `pickupLocation` and `dropoffLocation` with coordinate pairs. No `RideStopover` or waypoint entity exists. |
| 6 | **Seat Availability & Concurrency Booking** | **Implemented** | [BookingService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/BookingService.java#L64-L232), [RideRepository.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/repository/RideRepository.java) | Pessimistic locking (`findByIdForUpdate()`) ensures zero overbooking race conditions. Dynamically decrements `availableSeats` and updates ride status to `FULL` when capacity reaches 0. |
| 7 | **Booking Confirmation & Cancellation** | **Implemented** | [BookingService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/BookingService.java), [CancellationFeeCalculationService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/CancellationFeeCalculationService.java) | Auto-confirmation enforced (manual driver acceptance removed). Cancellation window calculation, dynamic cancellation penalty rules, seat restoration, automated refund triggering. |
| 8 | **Fare & Carpool Price Calculation** | **Implemented** | [CarpoolPricingService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/CarpoolPricingService.java), [FeeCalculationService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/FeeCalculationService.java) | Statutory cost-sharing engine: derives per-seat pricing ceiling based on fuel type (Petrol, Diesel, CNG, EV), maintenance per km, verified tolls, and jurisdiction max-cost recovery ratios. Decimal-safe BigDecimal math. |
| 9 | **Payments, Refunds & Payouts** | **Implemented** | [PaymentService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/PaymentService.java), [RazorpayService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/RazorpayService.java), [PayoutService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/PayoutService.java) | Razorpay test order creation, cryptographic HMAC verification, refund execution, driver payout profiles (UPI/Bank), automated commission settlement ledger. |
| 10 | **Ride Search & Commute Matching** | **Implemented** | [RideService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/RideService.java#L103-L188), [RideRepository.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/repository/RideRepository.java) | Search by origin city, destination city, required seats, departure date, and departure time. Ranks results by office commute schedule match and time proximity. Cached via Redis. |
| 11 | **Notifications** | **Implemented** | [NotificationService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/NotificationService.java), [EmailService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/EmailService.java) | In-app notification persistence (`Notification` table) and dual transactional HTML email delivery (Brevo API + SMTP fallback). |
| 12 | **Live Location Tracking** | **Partially Implemented** | [DriverTrackingService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/DriverTrackingService.java), [WebSocketConfig.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/WebSocketConfig.java), [LocationHandler.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/LocationHandler.java) | WebSocket endpoints `/ws/location` and `/ws/admin/monitoring` exist, along with driver heartbeat polling. However, `/ws/location` uses an unauthenticated in-memory broadcast demo handler that does not target specific passenger-ride pairs. |
| 13 | **Admin Panel APIs** | **Implemented** | [AdminController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/AdminController.java), [GstAdminController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/GstAdminController.java) | Comprehensive management of users, drivers, document audits, ride overrides, commission controls, GST rates, email campaigns, audit logs, and platform health. |
| 14 | **Reviews, Ratings & Feedback** | **Missing** | N/A (Inspected entire model & controller package) | No `Review`, `Rating`, or `Feedback` JPA entity exists in the codebase. Drivers and passengers cannot rate trips or submit star reviews. |
| 15 | **Database Migrations & Schema Management** | **Partially Implemented** | [DatabaseConstraintInitializer.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/DatabaseConstraintInitializer.java) | Dynamic initialization runs via raw JDBC in a Spring `CommandLineRunner`. Missing declarative tools such as Flyway or Liquibase. |

---

## 5. Database & Scalability Review

### Entity & Index Inspection
- **38 JPA Entities** detected in `com.rideshare.backend.model`.
- **Primary Indexes:** Composite indexes exist on `Ride` (`idx_rides_status_dept`, `idx_rides_driver_id`), `User` (`idx_users_email`, `idx_users_role`, `idx_users_status`), and `Booking` (`idx_bookings_booking_code`).
- **N+1 Query Hazards:**
  - Relationships such as `Ride.rider` and `Ride.driver` are mapped with default `@ManyToOne` (FetchType.EAGER in JPA defaults unless explicitly specified as `LAZY`).
  - [RideRepository.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/repository/RideRepository.java) uses `LEFT JOIN FETCH` on `findAllActiveRidesWithDetails()`, but ad-hoc queries in `BookingService` and `AdminController` fetch entities individually, generating N+1 SELECT cascades during list operations.

### Concurrency & Locking
- **Booking Overbooking Protection:** [BookingService.java:77](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/BookingService.java#L77) explicitly invokes `rideRepository.findByIdForUpdate(request.getRideId())` (`SELECT ... FOR UPDATE`), ensuring robust pessimistic write concurrency.
- **Payment & Wallet Idempotency:**
  - `PaymentService` and `WalletController` use `Isolation.SERIALIZABLE` on critical state mutations.
  - Razorpay order ID and payment ID verification are verified for duplicate credit before mutating balances.
  - Partial unique index `idx_driver_active_ride` protects against duplicate ride publishing.

### Connection Pool & Caching
- **HikariCP Configuration:**
  - Max pool size: 20
  - Min idle: 5
  - Max lifetime: 20 minutes (1,200,000 ms)
  - Connection timeout: 20 seconds
  - Leak detection threshold: 60 seconds
- **Redis Cache-Aside Layer:**
  - [RedisCacheService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/RedisCacheService.java) implements cache-aside for published rides search (3m TTL), ride details (10m TTL), platform settings (30m TTL), and coupons (15m TTL).
  - Graceful fallback: If Redis is unavailable or times out, the service silently catches exceptions and falls back to PostgreSQL.

---

## 6. Security Review

| Dimension | Implementation Details | Verdict |
| :--- | :--- | :--- |
| **Password Storage** | BCrypt via `PasswordEncoder` (`BCryptPasswordEncoder`) in [SecurityConfig.java:87](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/SecurityConfig.java#L87). | **Secure** |
| **JWT Token Validation** | HMAC-SHA256 with runtime length verification (>= 32 chars). Tokens contain email, role, and userId claims. Validated on every non-public request in [JwtFilter.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/security/JwtFilter.java). | **Secure** |
| **User Suspension Check** | Suspended users are immediately intercepted inside `JwtFilter.java:53` with HTTP 403 Forbidden even if their JWT has not expired. | **Secure** |
| **Role-Based Access Control (RBAC)** | Role validation via Spring Security (`ROLE_ADMIN`, `ROLE_SUPPORT`, `ROLE_DRIVER`, `ROLE_RIDER`). Granular permissions enforced by [SupportPermissionService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/SupportPermissionService.java). | **Secure** |
| **CORS Policy** | [SecurityConfig.java:73](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/SecurityConfig.java#L73) sets `config.setAllowedOriginPatterns(List.of("*"))` with `allowCredentials(true)`. Permitting wildcard origin patterns while allowing credentials constitutes an over-permissive CORS security risk for production deployments. | **High Risk** |
| **Payment Signature Verification** | Cryptographic verification (`HmacSHA256`) enforced on Razorpay webhook callbacks and client top-up completions before state changes occur. | **Secure** |
| **Public Endpoint Rate Limiting** | [PublicController.java:41](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/PublicController.java#L41) uses an in-memory `HashMap` (`rateLimitMap`) for IP throttling. This leaks memory over time, is lost on restarts, and fails across distributed horizontal replicas. | **Medium Risk** |
| **WebSocket Authentication** | Admin monitoring (`/ws/admin/monitoring`) validates JWT tokens in connection query parameters. However, `/ws/location` allows unauthenticated connections and broadcasts arbitrary location strings to all connected sessions. | **High Risk** |

---

## 7. Testing & Deployment Readiness

### Test Suite Inventory
- **20 test classes** (15 integration test suites, 5 unit test suites):
  - [RideLifecycleIntegrationTest.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/test/java/com/rideshare/backend/RideLifecycleIntegrationTest.java) (44 KB)
  - [PaymentAndSettlementIntegrationTest.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/test/java/com/rideshare/backend/PaymentAndSettlementIntegrationTest.java) (22 KB)
  - [FeeAndCommissionSystemTest.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/test/java/com/rideshare/backend/FeeAndCommissionSystemTest.java) (23 KB)
  - [DriverMonitoringIntegrationTest.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/test/java/com/rideshare/backend/DriverMonitoringIntegrationTest.java) (17 KB)
  - [OfficeCarpoolingIntegrationTest.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/test/java/com/rideshare/backend/OfficeCarpoolingIntegrationTest.java) (29 KB)
  - Testcontainers PostgreSQL support configured in `pom.xml`.

### Deployment & Ops Readiness
- **Docker Packaging:** Multi-stage build present in [Dockerfile](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/Dockerfile) (`eclipse-temurin:17-jdk-alpine` builder → `eclipse-temurin:17-jre-alpine` runtime).
- **Docker Compose:** Missing from the repository root.
- **Spring Boot Actuator:** Missing from `pom.xml`. The project relies on custom-written health checks in [SystemHealthMonitoringService.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/service/SystemHealthMonitoringService.java).
- **CI/CD Pipelines:** No `.github/workflows` or equivalent pipeline definitions exist.

---

## 8. Prioritized Issues & Improvement Roadmap

### A. Architecture & Code Quality Issues

| Severity | Location / Component | Evidence & Finding | Recommended Solution | Deployment Timing |
| :--- | :--- | :--- | :--- | :--- |
| **High** | [AdminController.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/AdminController.java) | **Monolithic God Controller**: 2,634 lines, directly autowiring 10 repositories and executing core business transactions. | Refactor into domain-aligned services (`AdminUserService`, `AdminRideService`, `AdminFinanceService`). | Post-MVP / Fast-Follow |
| **High** | Entire Project | **Missing Global Exception Handler**: Zero `@ControllerAdvice` classes. Exceptions cause unstandardized responses and possible internal error leakage. | Create `GlobalExceptionHandler` with `@ExceptionHandler` for `EntityNotFoundException`, `AccessDeniedException`, and validation errors. | **Must Fix Before Prod** |
| **Medium** | [WalletController.java:129](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/WalletController.java#L129) | **Transactional Boundary in Controller**: `@Transactional(isolation = SERIALIZABLE)` and raw entity mutation inside controller layer. | Extract `WalletService.verifyTopup()` and manage transactions inside the service layer. | Pre-Release |
| **Medium** | [DatabaseConstraintInitializer.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/DatabaseConstraintInitializer.java) | **Schema Mutation in CommandLineRunner**: DDL statements executed on application startup rather than through versioned migration tools. | Integrate **Flyway** (`db/migration/V1__*.sql`) and remove runtime DDL execution. | Pre-Release |

### B. Security & Concurrency Risks

| Severity | Location / Component | Evidence & Finding | Recommended Solution | Deployment Timing |
| :--- | :--- | :--- | :--- | :--- |
| **High** | [SecurityConfig.java:73](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/SecurityConfig.java#L73) | **Overly Permissive CORS**: `setAllowedOriginPatterns(List.of("*"))` paired with `setAllowCredentials(true)` leaves authenticated sessions exposed to arbitrary origins. | Restrict origins to production domain list loaded from environment variables (`cors.allowed-origins`). | **Must Fix Before Prod** |
| **High** | [LocationHandler.java:27](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/config/LocationHandler.java#L27) | **Unauthenticated Location Broadcast**: `/ws/location` broadcasts location text to all open sessions without token verification or ride-level scoping. | Require JWT handshake and route telemetry strictly to passengers who belong to that specific active booking. | **Must Fix Before Prod** |
| **Medium** | [PublicController.java:41](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/controller/PublicController.java#L41) | **In-Memory Rate Limiting Tracker**: Uses `Map<String, List<Long>> rateLimitMap` inside a singleton controller; susceptible to memory leakage and ineffective under horizontal scaling. | Transition rate limiting to Redis (`redisTemplate` with sliding window or Token Bucket algorithm). | Post-MVP |

### C. Scalability & Operational Risks

| Severity | Location / Component | Evidence & Finding | Recommended Solution | Deployment Timing |
| :--- | :--- | :--- | :--- | :--- |
| **High** | `pom.xml` | **Missing Production Actuator & Metrics**: No `spring-boot-starter-actuator` or Micrometer Prometheus dependency. Container orchestrators (Kubernetes/ECS) cannot perform native `/actuator/health` or `/actuator/prometheus` probes. | Add `spring-boot-starter-actuator` and configure readiness/liveness endpoints. | Pre-Release |
| **Medium** | [Ride.java](file:///d:/CHIRANJEEVI%20PROJECT/new-project/rideShare-backend/src/main/java/com/rideshare/backend/model/Ride.java) | **Default EAGER Fetching**: Foreign key relationships (`User rider`, `User driver`) risk triggering silent N+1 queries under high-volume list endpoints. | Explicitly annotate relations with `fetch = FetchType.LAZY` and use repository JPQL entity graphs or `JOIN FETCH`. | Pre-Release |
| **Low** | Root directory | **Missing CI/CD Workflow**: No automated Maven test execution or container build pipeline configured in repository. | Add GitHub Actions workflow (`.github/workflows/backend-ci.yml`) to run test suites on PR/commit. | Post-MVP |

---

## 9. Implementation Roadmap & Milestones

```
Phase 1: Security & Stability (Immediate Pre-Deployment)
  ├── 1. Restrict CORS allowed origins to production domains.
  ├── 2. Implement @RestControllerAdvice for uniform error handling.
  ├── 3. Secure /ws/location WebSocket endpoint with JWT and room isolation.
  └── 4. Add Spring Boot Actuator for container liveness/readiness health probes.

Phase 2: Architectural Decoupling & Migration (Post-Launch)
  ├── 1. Replace DatabaseConstraintInitializer with Flyway SQL migrations.
  ├── 2. Refactor AdminController into specialized domain service classes.
  ├── 3. Extract WalletController transactional logic into WalletService.
  └── 4. Migrate PublicController in-memory rate limiter to Redis.

Phase 3: Domain Completeness (Next Functional Milestone)
  ├── 1. Implement Driver/Passenger Review & Rating entity and APIs.
  └── 2. Implement multi-point route stopovers and waypoint matching.
```
