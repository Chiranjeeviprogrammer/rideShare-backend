package com.rideshare.backend.controller;

import com.rideshare.backend.service.TranslationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/translations")
public class AdminTranslationController {

    @Autowired
    private TranslationService translationService;

    /**
     * Get all translation keys and their bilingual values for Admin panel.
     */
    @GetMapping
    public ResponseEntity<?> getAdminTranslations(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(translationService.getAdminTranslations(category, status, search));
    }

    /**
     * Create or update translation for a key (supports saving as DRAFT or PUBLISHED).
     */
    @PostMapping
    public ResponseEntity<?> saveKeyTranslation(@RequestBody Map<String, Object> body) {
        String key = (String) body.get("key");
        String category = (String) body.get("category");
        String enValue = (String) body.get("enValue");
        String hiValue = (String) body.get("hiValue");
        Boolean publishImmediately = body.containsKey("publishImmediately")
                ? Boolean.valueOf(String.valueOf(body.get("publishImmediately")))
                : false;

        if (key == null || key.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Translation key is required"));
        }

        Map<String, Object> saved = translationService.saveKeyTranslations(
                key.trim(),
                category != null ? category.trim() : "COMMON",
                enValue != null ? enValue : "",
                hiValue != null ? hiValue : "",
                publishImmediately
        );

        return ResponseEntity.ok(saved);
    }

    /**
     * Publish all pending draft translations.
     */
    @PostMapping("/publish")
    public ResponseEntity<?> publishDrafts() {
        int count = translationService.publishAllDrafts();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "publishedCount", count,
                "message", "Successfully published " + count + " draft translations to live!"
        ));
    }

    /**
     * Export all translations to JSON.
     */
    @GetMapping("/export")
    public ResponseEntity<?> exportTranslations() {
        return ResponseEntity.ok(translationService.exportTranslations());
    }

    /**
     * Import translations from JSON payload.
     */
    @PostMapping("/import")
    public ResponseEntity<?> importTranslations(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> translations = (Map<String, Map<String, String>>) payload.get("translations");
        Boolean autoPublish = payload.containsKey("autoPublish")
                ? Boolean.valueOf(String.valueOf(payload.get("autoPublish")))
                : true;

        if (translations == null || translations.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Payload must contain 'translations' object with 'en' and/or 'hi' maps."));
        }

        int importedCount = translationService.importTranslations(translations, autoPublish);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "importedCount", importedCount,
                "message", "Successfully imported " + importedCount + " translations!"
        ));
    }
}
