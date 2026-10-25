package com.rideshare.backend.controller;

import com.rideshare.backend.model.EmailAudienceContact;
import com.rideshare.backend.model.EmailCampaign;
import com.rideshare.backend.model.EmailWorkflow;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.EmailAudienceContactRepository;
import com.rideshare.backend.repository.EmailCampaignRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.AuditLogService;
import com.rideshare.backend.service.EmailEngagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/engagement")
public class AdminEmailEngagementController {

    @Autowired
    private EmailEngagementService engagementService;

    @Autowired
    private EmailAudienceContactRepository contactRepository;

    @Autowired
    private EmailCampaignRepository campaignRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuditLogService auditLogService;

    private User getAdminUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try {
            String token = authHeader.substring(7);
            String email = jwtUtil.extractEmail(token);
            User user = userRepository.findByEmail(email);
            if (user != null && ("ADMIN".equalsIgnoreCase(user.getRole()) || "SUPPORT".equalsIgnoreCase(user.getRole()))) {
                return user;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==========================================
    // 1. AUDIENCES & CONTACTS
    // ==========================================

    @GetMapping("/contacts")
    public ResponseEntity<?> getContacts(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String audienceType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        Page<EmailAudienceContact> contacts = engagementService.getContacts(audienceType, status, search, page, size);
        return ResponseEntity.ok(contacts);
    }

    @PostMapping("/contacts")
    public ResponseEntity<?> addContact(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, Object> payload
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        String email = (String) payload.get("email");
        String fullName = (String) payload.get("fullName");
        String phoneNumber = (String) payload.get("phoneNumber");
        String city = (String) payload.get("city");
        String audienceType = (String) payload.getOrDefault("audienceType", "PASSENGER");
        Boolean consentGiven = payload.containsKey("consentGiven") ? (Boolean) payload.get("consentGiven") : true;

        try {
            EmailAudienceContact contact = engagementService.addOrUpdateContact(
                    email, fullName, phoneNumber, city, audienceType, "ADMIN_MANUAL", consentGiven
            );
            return ResponseEntity.ok(contact);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/contacts/import")
    public ResponseEntity<?> importContacts(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> payload
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        String csvContent = payload.get("csv");
        String defaultAudienceType = payload.getOrDefault("defaultAudienceType", "PASSENGER");

        try {
            Map<String, Object> result = engagementService.importCsvContacts(csvContent, defaultAudienceType);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/contacts/{id}")
    public ResponseEntity<?> deleteContact(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        if (!contactRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        contactRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Contact removed successfully."));
    }

    @GetMapping({"/audiences/stats", "/audience/stats"})
    public ResponseEntity<?> getAudienceStats(@RequestHeader("Authorization") String authHeader) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        return ResponseEntity.ok(engagementService.getAudienceStats());
    }

    // ==========================================
    // 2. TEMPLATES
    // ==========================================

    @GetMapping("/templates")
    public ResponseEntity<?> getPresetTemplates(@RequestHeader("Authorization") String authHeader) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        return ResponseEntity.ok(engagementService.getPresetTemplates());
    }

    // ==========================================
    // 3. CAMPAIGNS
    // ==========================================

    @GetMapping("/campaigns")
    public ResponseEntity<?> getCampaigns(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String campaignType,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        return ResponseEntity.ok(engagementService.getCampaigns(status, campaignType, search, page, size));
    }

    @GetMapping("/campaigns/{id}")
    public ResponseEntity<?> getCampaign(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        Optional<EmailCampaign> opt = engagementService.getCampaign(id);
        return opt.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/campaigns")
    public ResponseEntity<?> saveCampaign(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, Object> payload
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        Long id = payload.containsKey("id") && payload.get("id") != null ? Long.valueOf(payload.get("id").toString()) : null;
        String name = (String) payload.get("name");
        String subject = (String) payload.get("subject");
        String previewText = (String) payload.get("previewText");
        String campaignType = (String) payload.get("campaignType");
        String targetAudience = (String) payload.get("targetAudience");
        String templateDataJson = (String) payload.get("templateDataJson");

        if (name == null || name.isBlank() || subject == null || subject.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Campaign name and email subject are required."));
        }

        try {
            EmailCampaign saved = engagementService.saveCampaign(
                    id, name, subject, previewText, campaignType, targetAudience, templateDataJson, user.getEmail()
            );
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/campaigns/{id}")
    public ResponseEntity<?> deleteCampaign(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        Optional<EmailCampaign> opt = campaignRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        EmailCampaign campaign = opt.get();
        if ("SENT".equalsIgnoreCase(campaign.getStatus()) || "SENDING".equalsIgnoreCase(campaign.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Cannot delete a campaign that has already been dispatched."));
        }

        campaignRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true, "message", "Campaign deleted."));
    }

    @PostMapping({"/campaigns/test-send", "/campaigns/direct-send"})
    public ResponseEntity<?> sendDirectEmails(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, String> payload
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        String recipientInput = payload.getOrDefault("to", payload.get("recipients"));
        String subject = payload.get("subject");
        String previewText = payload.get("previewText");
        String templateDataJson = payload.get("templateDataJson");

        if (recipientInput == null || recipientInput.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "At least one recipient email address is required."));
        }

        String[] rawEmails = recipientInput.split("[,;\\s\\n\\r]+");
        List<String> validEmails = new ArrayList<>();
        for (String r : rawEmails) {
            String trimmed = r.trim().toLowerCase();
            if (!trimmed.isEmpty() && trimmed.contains("@")) {
                validEmails.add(trimmed);
            }
        }

        if (validEmails.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No valid recipient email address found in input."));
        }

        try {
            Map<String, Object> result = engagementService.sendDirectEmails(validEmails, subject, previewText, templateDataJson);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/campaigns/{id}/send")
    public ResponseEntity<?> sendCampaign(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        try {
            Map<String, Object> result = engagementService.sendCampaign(id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ==========================================
    // 4. AUTOMATED WORKFLOWS
    // ==========================================

    @GetMapping("/workflows")
    public ResponseEntity<?> getWorkflows(@RequestHeader("Authorization") String authHeader) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        List<EmailWorkflow> workflows = engagementService.getAllWorkflows();
        return ResponseEntity.ok(workflows);
    }

    @PutMapping("/workflows/{id}")
    public ResponseEntity<?> updateWorkflow(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        String name = (String) payload.get("name");
        String subject = (String) payload.get("subject");
        String previewText = (String) payload.get("previewText");
        String templateDataJson = (String) payload.get("templateDataJson");
        Integer delayHours = payload.containsKey("delayHours") ? Integer.valueOf(payload.get("delayHours").toString()) : null;
        Boolean enabled = (Boolean) payload.get("enabled");

        try {
            EmailWorkflow updated = engagementService.updateWorkflow(
                    id, name, subject, previewText, templateDataJson, delayHours, enabled
            );
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PutMapping("/workflows/{id}/toggle")
    public ResponseEntity<?> toggleWorkflow(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> payload
    ) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        boolean enabled = payload.getOrDefault("enabled", false);
        try {
            EmailWorkflow updated = engagementService.toggleWorkflow(id, enabled);
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ==========================================
    // 5. ENGAGEMENT ANALYTICS
    // ==========================================

    @GetMapping("/analytics")
    public ResponseEntity<?> getAnalytics(@RequestHeader("Authorization") String authHeader) {
        User user = getAdminUser(authHeader);
        if (user == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));

        return ResponseEntity.ok(engagementService.getAnalyticsDashboard());
    }
}
