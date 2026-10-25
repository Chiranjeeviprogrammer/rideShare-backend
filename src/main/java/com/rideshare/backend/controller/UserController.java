package com.rideshare.backend.controller;

import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @GetMapping
    public org.springframework.http.ResponseEntity<?> getAllUsers(@RequestHeader(value = "Authorization", required = false) String token) {
        if (token == null || !token.startsWith("Bearer ")) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).body(java.util.Map.of("message", "Authentication required"));
        }
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN).body(java.util.Map.of("message", "Administrative access required"));
        }
        return org.springframework.http.ResponseEntity.ok(userRepository.findAll());
    }

    @Autowired
    private com.rideshare.backend.repository.ReferralRepository referralRepository;
    
    @Autowired
    private com.rideshare.backend.repository.WalletRepository walletRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @PostMapping
    public User createUser(@RequestBody User user) {
        // Enforce: Creating ADMIN via user creation API is strictly prohibited
        if (user.getRole() != null && "ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "Creating additional Admin accounts via API is strictly prohibited. Exactly one primary Admin exists."
            );
        }
        if (user.getRole() == null || user.getRole().isBlank()) {
            user.setRole("RIDER");
        }
        // Hash password if not already hashed
        if (user.getPassword() != null && !user.getPassword().startsWith("$2a$")) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        // Generate a random referral code
        user.setReferralCode(java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        user.setVerified(true);
        
        User savedUser = userRepository.save(user);
        
        // Create wallet
        com.rideshare.backend.model.Wallet wallet = new com.rideshare.backend.model.Wallet();
        wallet.setUser(savedUser);
        wallet.setBalance(0.0);
        walletRepository.save(wallet);

        
        // Process referral if provided
        if (user.getReferredBy() != null && !user.getReferredBy().isEmpty()) {
            User referrer = userRepository.findByReferralCode(user.getReferredBy());
            if (referrer != null && !referrer.getId().equals(savedUser.getId())) {
                com.rideshare.backend.model.Referral referral = new com.rideshare.backend.model.Referral();
                referral.setReferrer(referrer);
                referral.setReferredUser(savedUser);
                referral.setStatus("PENDING");
                referral.setRewardAmount(100.0); // Example fixed reward amount
                referralRepository.save(referral);
            }
        }
        return savedUser;
    }
    
    @GetMapping("/me")
    public User getCurrentUser(@RequestHeader("Authorization") String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email);
    }

    @PutMapping("/me")
    public org.springframework.http.ResponseEntity<?> updateMyProfile(
            @RequestHeader("Authorization") String token,
            @RequestBody java.util.Map<String, Object> body) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return org.springframework.http.ResponseEntity.status(401).body(java.util.Map.of("message", "User not found"));
        }
        if (body.containsKey("name") && body.get("name") != null && !body.get("name").toString().isBlank()) {
            user.setName(body.get("name").toString().trim());
        }
        if (body.containsKey("phoneNumber") && body.get("phoneNumber") != null) {
            user.setPhoneNumber(body.get("phoneNumber").toString().trim());
        }
        if (body.containsKey("avatarUrl") && body.get("avatarUrl") != null) {
            user.setAvatarUrl(body.get("avatarUrl").toString().trim());
        }
        if (body.containsKey("preferredLanguage") && body.get("preferredLanguage") != null) {
            String lang = body.get("preferredLanguage").toString().trim().toLowerCase();
            if ("en".equals(lang) || "hi".equals(lang)) {
                user.setPreferredLanguage(lang);
            }
        }
        if (body.containsKey("homeAddress") && body.get("homeAddress") != null) {
            user.setHomeAddress(body.get("homeAddress").toString().trim());
        }
        if (body.containsKey("officeAddress") && body.get("officeAddress") != null) {
            user.setOfficeAddress(body.get("officeAddress").toString().trim());
        }
        if (body.containsKey("workCompany") && body.get("workCompany") != null) {
            user.setWorkCompany(body.get("workCompany").toString().trim());
        }
        if (body.containsKey("homeLatitude") && body.get("homeLatitude") != null) {
            try { user.setHomeLatitude(Double.parseDouble(body.get("homeLatitude").toString())); } catch (Exception ignored) {}
        }
        if (body.containsKey("homeLongitude") && body.get("homeLongitude") != null) {
            try { user.setHomeLongitude(Double.parseDouble(body.get("homeLongitude").toString())); } catch (Exception ignored) {}
        }
        if (body.containsKey("officeLatitude") && body.get("officeLatitude") != null) {
            try { user.setOfficeLatitude(Double.parseDouble(body.get("officeLatitude").toString())); } catch (Exception ignored) {}
        }
        if (body.containsKey("officeLongitude") && body.get("officeLongitude") != null) {
            try { user.setOfficeLongitude(Double.parseDouble(body.get("officeLongitude").toString())); } catch (Exception ignored) {}
        }
        User saved = userRepository.save(user);
        return org.springframework.http.ResponseEntity.ok(saved);
    }

    @PutMapping("/me/language")
    public org.springframework.http.ResponseEntity<?> updateMyLanguage(
            @RequestHeader("Authorization") String token,
            @RequestParam(required = false) String language,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        if (user == null) {
            return org.springframework.http.ResponseEntity.status(401).body(java.util.Map.of("message", "User not found"));
        }
        String targetLang = language;
        if (targetLang == null && body != null && body.containsKey("language")) {
            targetLang = body.get("language");
        } else if (targetLang == null && body != null && body.containsKey("preferredLanguage")) {
            targetLang = body.get("preferredLanguage");
        }
        if (targetLang == null || (!"en".equalsIgnoreCase(targetLang.trim()) && !"hi".equalsIgnoreCase(targetLang.trim()))) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(java.util.Map.of("message", "Invalid language. Supported locales are 'en' and 'hi'."));
        }
        user.setPreferredLanguage(targetLang.trim().toLowerCase());
        User saved = userRepository.save(user);
        return org.springframework.http.ResponseEntity.ok(java.util.Map.of(
                "success", true,
                "preferredLanguage", saved.getPreferredLanguage(),
                "message", "Language preference saved successfully"
        ));
    }
    
    @PutMapping("/role")
    public org.springframework.http.ResponseEntity<?> updateRole(@RequestHeader("Authorization") String token, @RequestParam String role) {
        if (token == null || !token.startsWith("Bearer ")) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED)
                    .body(java.util.Map.of("message", "Authentication required."));
        }
        String email = jwtUtil.extractEmail(token.substring(7));
        User caller = userRepository.findByEmail(email);
        if (caller == null || !"ADMIN".equalsIgnoreCase(caller.getRole())) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("message", "Administrative access required to update user roles directly."));
        }
        if ("ADMIN".equalsIgnoreCase(role)) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("message", "Role escalation to ADMIN is strictly prohibited. Exactly one primary Admin exists."));
        }
        caller.setRole(role.toUpperCase());
        return org.springframework.http.ResponseEntity.ok(userRepository.save(caller));
    }
}

