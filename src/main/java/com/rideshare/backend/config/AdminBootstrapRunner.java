package com.rideshare.backend.config;

import com.rideshare.backend.model.PlatformSettings;
import com.rideshare.backend.model.User;
import com.rideshare.backend.model.Wallet;
import com.rideshare.backend.repository.PlatformSettingsRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Order(2)
public class AdminBootstrapRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private PlatformSettingsRepository platformSettingsRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${ADMIN_EMAIL:admin@rideshare.com}")
    private String adminEmail;

    @Value("${ADMIN_PASSWORD:}")
    private String adminPassword;

    @Value("${ADMIN_NAME:System Administrator}")
    private String adminName;

    @Override
    public void run(String... args) {
        bootstrapAdminUser();
        bootstrapPlatformSettings();
    }

    private void bootstrapAdminUser() {
        try {
            long adminCount = userRepository.countByRole("ADMIN");
            User existingAdmin = userRepository.findByEmail(adminEmail.trim().toLowerCase());

            if (existingAdmin == null && adminCount == 0) {
                if (adminPassword == null || adminPassword.length() < 12) {
                    logger.warn(">>> [BOOTSTRAP] Skipped initial admin creation: set a strong ADMIN_PASSWORD (at least 12 characters).");
                    return;
                }
                User admin = new User();
                admin.setName(adminName);
                admin.setEmail(adminEmail.trim().toLowerCase());
                admin.setPassword(passwordEncoder.encode(adminPassword));
                admin.setRole("ADMIN");
                admin.setStatus("ACTIVE");
                admin.setVerified(true);
                admin.setPhoneNumber("+919000000001");
                admin.setReferralCode("ADMIN001");
                admin.setCreatedAt(LocalDateTime.now());
                User saved = userRepository.save(admin);

                Wallet wallet = new Wallet();
                wallet.setUser(saved);
                wallet.setBalance(10000.0);
                walletRepository.save(wallet);

                logger.info(">>> [BOOTSTRAP] Initial Administrator successfully bootstrapped: {} (Role: ADMIN)", adminEmail);
            } else if (existingAdmin != null && !"ADMIN".equals(existingAdmin.getRole())) {
                existingAdmin.setRole("ADMIN");
                existingAdmin.setStatus("ACTIVE");
                userRepository.save(existingAdmin);
                logger.info(">>> [BOOTSTRAP] Upgraded existing user {} to role ADMIN", adminEmail);
            } else {
                logger.info(">>> [BOOTSTRAP] Administrator account already configured (count: {})", adminCount);
            }
        } catch (Exception e) {
            logger.warn(">>> [BOOTSTRAP] Admin bootstrap encountered non-fatal notice: {}", e.getMessage());
        }
    }

    private void bootstrapPlatformSettings() {
        try {
            if (platformSettingsRepository.count() == 0) {
                PlatformSettings settings = PlatformSettings.builder()
                        .supportEmail("support@rideshare.com")
                        .supportPhone("+91 98765 43210")
                        .companyName("RideShare Mobility Technologies Inc.")
                        .companyAddress("100 Tech Park, MG Road, Bengaluru, KA 560001, India")
                        .commissionRatePercent(15.0)
                        .commissionFixedFee(20.0)
                        .outstandingThreshold(500.0)
                        .emailNotificationsEnabled(true)
                        .updatedAt(LocalDateTime.now())
                        .updatedBy("SYSTEM")
                        .build();
                platformSettingsRepository.save(settings);
                logger.info(">>> [BOOTSTRAP] Default platform operational settings initialized.");
            }
        } catch (Exception e) {
            logger.warn(">>> [BOOTSTRAP] Platform settings initialization notice: {}", e.getMessage());
        }
    }
}
