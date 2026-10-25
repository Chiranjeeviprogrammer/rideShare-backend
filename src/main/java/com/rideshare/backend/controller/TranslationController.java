package com.rideshare.backend.controller;

import com.rideshare.backend.service.TranslationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/translations")
public class TranslationController {

    @Autowired
    private TranslationService translationService;

    /**
     * Public API for mobile app and web clients to fetch published translations.
     */
    @GetMapping
    public ResponseEntity<?> getAllTranslations() {
        return ResponseEntity.ok(translationService.getAllPublishedBundles());
    }

    /**
     * Public API to fetch published translations for a specific locale (e.g., 'en' or 'hi').
     */
    @GetMapping("/{locale}")
    public ResponseEntity<?> getTranslationsByLocale(@PathVariable String locale) {
        if (!"en".equalsIgnoreCase(locale) && !"hi".equalsIgnoreCase(locale)) {
            return ResponseEntity.badRequest().body(Map.of("message", "Unsupported locale: " + locale + ". Supported: 'en', 'hi'."));
        }
        return ResponseEntity.ok(translationService.getPublishedBundle(locale));
    }
}
