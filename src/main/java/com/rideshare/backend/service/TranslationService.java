package com.rideshare.backend.service;

import com.rideshare.backend.model.Translation;
import com.rideshare.backend.repository.TranslationRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class TranslationService {

    @Autowired
    private TranslationRepository translationRepository;

    /**
     * Get published translations as key-value map for a locale.
     * If locale is 'hi', it falls back to 'en' for any missing key.
     */
    public Map<String, String> getPublishedBundle(String locale) {
        String targetLocale = (locale == null || locale.trim().isEmpty()) ? "en" : locale.trim().toLowerCase();

        // Load English fallback bundle first
        Map<String, String> bundle = new HashMap<>();
        List<Translation> enList = translationRepository.findByLocaleAndStatus("en", "PUBLISHED");
        for (Translation t : enList) {
            bundle.put(t.getTranslationKey(), t.getValue());
        }

        // If target locale is not 'en', overlay target locale translations
        if (!"en".equals(targetLocale)) {
            List<Translation> targetList = translationRepository.findByLocaleAndStatus(targetLocale, "PUBLISHED");
            for (Translation t : targetList) {
                if (t.getValue() != null && !t.getValue().isBlank()) {
                    bundle.put(t.getTranslationKey(), t.getValue());
                }
            }
        }

        return bundle;
    }

    /**
     * Get bundle containing both 'en' and 'hi' published maps.
     * Note: hi map only contains genuine published Hindi translations,
     * so client apps can cleanly distinguish between translated keys and fallbacks.
     */
    public Map<String, Object> getAllPublishedBundles() {
        Map<String, String> enBundle = getPublishedBundle("en");
        Map<String, String> hiBundle = new HashMap<>();
        List<Translation> hiList = translationRepository.findByLocaleAndStatus("hi", "PUBLISHED");
        for (Translation t : hiList) {
            if (t.getValue() != null && !t.getValue().isBlank()) {
                hiBundle.put(t.getTranslationKey(), t.getValue());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("en", enBundle);
        response.put("hi", hiBundle);
        response.put("supportedLocales", List.of("en", "hi"));
        response.put("totalKeys", enBundle.size());
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }

    /**
     * Admin view: get list of translation items grouped by key with EN, HI,
     * category, and status.
     */
    public Map<String, Object> getAdminTranslations(String category, String status, String search) {
        List<Translation> allTranslations = translationRepository.findAll();

        Map<String, TranslationAdminItem> itemsByKey = new LinkedHashMap<>();

        for (Translation t : allTranslations) {
            String key = t.getTranslationKey();
            TranslationAdminItem item = itemsByKey.computeIfAbsent(key, k -> {
                TranslationAdminItem newItem = new TranslationAdminItem();
                newItem.setKey(k);
                newItem.setCategory(t.getCategory() != null ? t.getCategory() : "COMMON");
                newItem.setVersion(t.getVersion());
                newItem.setUpdatedAt(t.getUpdatedAt());
                return newItem;
            });

            if ("en".equalsIgnoreCase(t.getLocale())) {
                item.setEnValue(t.getValue());
                item.setEnStatus(t.getStatus());
                item.setEnId(t.getId());
            } else if ("hi".equalsIgnoreCase(t.getLocale())) {
                item.setHiValue(t.getValue());
                item.setHiStatus(t.getStatus());
                item.setHiId(t.getId());
            }
        }

        // Compute overall status for each item:
        // - DRAFT if either EN or HI has status DRAFT
        // - MISSING if HI value is null or empty
        // - COMPLETE / PUBLISHED if both exist and are PUBLISHED
        List<TranslationAdminItem> list = new ArrayList<>();
        int draftCount = 0;
        int missingCount = 0;
        int publishedCount = 0;

        for (TranslationAdminItem item : itemsByKey.values()) {
            boolean hasDraft = "DRAFT".equalsIgnoreCase(item.getEnStatus())
                    || "DRAFT".equalsIgnoreCase(item.getHiStatus());
            boolean hasMissingHi = item.getHiValue() == null || item.getHiValue().isBlank();

            if (hasDraft) {
                item.setOverallStatus("DRAFT");
                draftCount++;
            } else if (hasMissingHi) {
                item.setOverallStatus("MISSING");
                missingCount++;
            } else {
                item.setOverallStatus("PUBLISHED");
                publishedCount++;
            }

            // Filtering
            boolean matchesCategory = (category == null || category.isBlank() || "ALL".equalsIgnoreCase(category)
                    || item.getCategory().equalsIgnoreCase(category));
            boolean matchesStatus = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                    || item.getOverallStatus().equalsIgnoreCase(status));
            boolean matchesSearch = true;
            if (search != null && !search.isBlank()) {
                String q = search.toLowerCase().trim();
                matchesSearch = item.getKey().toLowerCase().contains(q)
                        || (item.getEnValue() != null && item.getEnValue().toLowerCase().contains(q))
                        || (item.getHiValue() != null && item.getHiValue().toLowerCase().contains(q));
            }

            if (matchesCategory && matchesStatus && matchesSearch) {
                list.add(item);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("items", list);
        result.put("totalKeys", itemsByKey.size());
        result.put("publishedKeys", publishedCount);
        result.put("draftKeys", draftCount);
        result.put("missingKeys", missingCount);
        return result;
    }

    /**
     * Create or update translation for a key and locale.
     */
    @Transactional
    public Translation saveTranslation(String key, String locale, String value, String category, String status) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Translation key cannot be empty");
        }
        String cleanLocale = (locale == null || locale.isBlank()) ? "en" : locale.trim().toLowerCase();
        if (!"en".equals(cleanLocale) && !"hi".equals(cleanLocale)) {
            throw new IllegalArgumentException("Unsupported locale: " + locale);
        }

        String cleanStatus = (status == null || status.isBlank()) ? "DRAFT" : status.trim().toUpperCase();
        if (!"DRAFT".equals(cleanStatus) && !"PUBLISHED".equals(cleanStatus)) {
            cleanStatus = "DRAFT";
        }

        String cleanCategory = (category == null || category.isBlank()) ? "COMMON" : category.trim().toUpperCase();

        Optional<Translation> existing = translationRepository.findByTranslationKeyAndLocale(key.trim(), cleanLocale);
        Translation translation;
        if (existing.isPresent()) {
            translation = existing.get();
            translation.setValue(value != null ? value.trim() : "");
            translation.setCategory(cleanCategory);
            translation.setStatus(cleanStatus);
            translation.setUpdatedAt(LocalDateTime.now());
        } else {
            translation = new Translation();
            translation.setTranslationKey(key.trim());
            translation.setLocale(cleanLocale);
            translation.setValue(value != null ? value.trim() : "");
            translation.setCategory(cleanCategory);
            translation.setStatus(cleanStatus);
            translation.setVersion(1);
            translation.setCreatedAt(LocalDateTime.now());
            translation.setUpdatedAt(LocalDateTime.now());
        }

        return translationRepository.save(translation);
    }

    /**
     * Save both EN and HI translations for a key at once.
     */
    @Transactional
    public Map<String, Object> saveKeyTranslations(String key, String category, String enValue, String hiValue,
            boolean publishImmediately) {
        String status = publishImmediately ? "PUBLISHED" : "DRAFT";
        Translation en = saveTranslation(key, "en", enValue, category, status);
        Translation hi = saveTranslation(key, "hi", hiValue, category, status);

        Map<String, Object> res = new HashMap<>();
        res.put("key", key);
        res.put("category", category);
        res.put("status", status);
        res.put("en", en);
        res.put("hi", hi);
        return res;
    }

    /**
     * Publish all drafts to live.
     */
    @Transactional
    public int publishAllDrafts() {
        List<Translation> drafts = translationRepository.findByStatus("DRAFT");
        for (Translation t : drafts) {
            t.setStatus("PUBLISHED");
            t.setUpdatedAt(LocalDateTime.now());
        }
        translationRepository.saveAll(drafts);
        return drafts.size();
    }

    /**
     * Export all translations as a JSON-friendly map.
     */
    public Map<String, Object> exportTranslations() {
        Map<String, Map<String, String>> exportMap = new HashMap<>();
        exportMap.put("en", new TreeMap<>());
        exportMap.put("hi", new TreeMap<>());

        List<Translation> all = translationRepository.findAll();
        for (Translation t : all) {
            String loc = t.getLocale();
            if (exportMap.containsKey(loc)) {
                exportMap.get(loc).put(t.getTranslationKey(), t.getValue());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("version", "1.0");
        result.put("exportedAt", LocalDateTime.now().toString());
        result.put("translations", exportMap);
        return result;
    }

    /**
     * Import translations from map.
     */
    @Transactional
    public int importTranslations(Map<String, Map<String, String>> translationsData, boolean autoPublish) {
        int count = 0;
        String status = autoPublish ? "PUBLISHED" : "DRAFT";

        for (Map.Entry<String, Map<String, String>> localeEntry : translationsData.entrySet()) {
            String loc = localeEntry.getKey().toLowerCase();
            if (!"en".equals(loc) && !"hi".equals(loc))
                continue;

            for (Map.Entry<String, String> keyEntry : localeEntry.getValue().entrySet()) {
                String key = keyEntry.getKey();
                String val = keyEntry.getValue();
                String category = inferCategoryFromKey(key);
                saveTranslation(key, loc, val, category, status);
                count++;
            }
        }
        return count;
    }

    private String inferCategoryFromKey(String key) {
        if (key == null)
            return "COMMON";
        String lower = key.toLowerCase();
        if (lower.startsWith("auth."))
            return "AUTH";
        if (lower.startsWith("dashboard.") || lower.startsWith("home."))
            return "DASHBOARD";
        if (lower.startsWith("booking.") || lower.startsWith("ride."))
            return "BOOKING";
        if (lower.startsWith("publish."))
            return "PUBLISH";
        if (lower.startsWith("wallet.") || lower.startsWith("payment."))
            return "WALLET";
        if (lower.startsWith("driver."))
            return "DRIVER";
        if (lower.startsWith("safety."))
            return "SAFETY";
        if (lower.startsWith("support.") || lower.startsWith("help."))
            return "SUPPORT";
        if (lower.startsWith("profile."))
            return "PROFILE";
        return "COMMON";
    }

    /**
     * Seed initial translations if DB is empty.
     */
    @PostConstruct
    public void seedInitialTranslations() {
        long count = translationRepository.count();
        if (count > 0) {
            return;
        }

        Map<String, SeedEntry> initialData = getInitialSeedData();
        List<Translation> list = new ArrayList<>();

        for (Map.Entry<String, SeedEntry> entry : initialData.entrySet()) {
            String key = entry.getKey();
            SeedEntry seed = entry.getValue();

            // English
            Translation en = new Translation();
            en.setTranslationKey(key);
            en.setLocale("en");
            en.setValue(seed.en);
            en.setCategory(seed.category);
            en.setStatus("PUBLISHED");
            en.setVersion(1);
            en.setCreatedAt(LocalDateTime.now());
            en.setUpdatedAt(LocalDateTime.now());
            list.add(en);

            // Hindi
            Translation hi = new Translation();
            hi.setTranslationKey(key);
            hi.setLocale("hi");
            hi.setValue(seed.hi);
            hi.setCategory(seed.category);
            hi.setStatus("PUBLISHED");
            hi.setVersion(1);
            hi.setCreatedAt(LocalDateTime.now());
            hi.setUpdatedAt(LocalDateTime.now());
            list.add(hi);
        }

        translationRepository.saveAll(list);
    }

    private static class SeedEntry {
        String category;
        String en;
        String hi;

        SeedEntry(String category, String en, String hi) {
            this.category = category;
            this.en = en;
            this.hi = hi;
        }
    }

    private Map<String, SeedEntry> getInitialSeedData() {
        Map<String, SeedEntry> map = new LinkedHashMap<>();

        // COMMON
        map.put("common.appName", new SeedEntry("COMMON", "RideShare", "राइडशेयर"));
        map.put("common.save", new SeedEntry("COMMON", "Save", "सहेजें"));
        map.put("common.cancel", new SeedEntry("COMMON", "Cancel", "रद्द करें"));
        map.put("common.confirm", new SeedEntry("COMMON", "Confirm", "पुष्टि करें"));
        map.put("common.continue", new SeedEntry("COMMON", "Continue", "जारी रखें"));
        map.put("common.back", new SeedEntry("COMMON", "Back", "वापस"));
        map.put("common.search", new SeedEntry("COMMON", "Search", "खोजें"));
        map.put("common.loading", new SeedEntry("COMMON", "Loading...", "लोड हो रहा है..."));
        map.put("common.error", new SeedEntry("COMMON", "Something went wrong", "कुछ गलत हो गया"));
        map.put("common.success", new SeedEntry("COMMON", "Success", "सफलता"));
        map.put("common.retry", new SeedEntry("COMMON", "Try Again", "पुनः प्रयास करें"));
        map.put("common.close", new SeedEntry("COMMON", "Close", "बंद करें"));
        map.put("common.done", new SeedEntry("COMMON", "Done", "पूर्ण"));
        map.put("common.edit", new SeedEntry("COMMON", "Edit", "संपादित करें"));
        map.put("common.delete", new SeedEntry("COMMON", "Delete", "हटाएं"));
        map.put("common.viewAll", new SeedEntry("COMMON", "View All", "सभी देखें"));

        // AUTH
        map.put("auth.welcomeTitle", new SeedEntry("AUTH", "Welcome to RideShare", "राइडशेयर में आपका स्वागत है"));
        map.put("auth.welcomeSubtitle", new SeedEntry("AUTH", "Safe, affordable, and trusted city rides",
                "सुरक्षित, किफायती और विश्वसनीय शहर की सवारी"));
        map.put("auth.login", new SeedEntry("AUTH", "Log In", "लॉग इन करें"));
        map.put("auth.register", new SeedEntry("AUTH", "Sign Up", "साइन अप करें"));
        map.put("auth.email", new SeedEntry("AUTH", "Email Address", "ईमेल पता"));
        map.put("auth.password", new SeedEntry("AUTH", "Password", "पासवर्ड"));
        map.put("auth.phone", new SeedEntry("AUTH", "Phone Number", "फ़ोन नंबर"));
        map.put("auth.fullName", new SeedEntry("AUTH", "Full Name", "पूरा नाम"));
        map.put("auth.forgotPassword", new SeedEntry("AUTH", "Forgot Password?", "पासवर्ड भूल गए?"));
        map.put("auth.resetPassword", new SeedEntry("AUTH", "Reset Password", "पासवर्ड रीसेट करें"));
        map.put("auth.enterOtp", new SeedEntry("AUTH", "Enter 6-Digit OTP", "6-अंकों का OTP दर्ज करें"));
        map.put("auth.resendOtp", new SeedEntry("AUTH", "Resend OTP", "OTP पुनः भेजें"));
        map.put("auth.verifyOtp", new SeedEntry("AUTH", "Verify & Proceed", "सत्यापित करें और आगे बढ़ें"));
        map.put("auth.logout", new SeedEntry("AUTH", "Log Out", "लॉग आउट करें"));
        map.put("auth.logoutConfirm",
                new SeedEntry("AUTH", "Are you sure you want to log out?", "क्या आप वाकई लॉग आउट करना चाहते हैं?"));

        // DASHBOARD
        map.put("dashboard.findRide", new SeedEntry("DASHBOARD", "Find a Ride", "सवारी खोजें"));
        map.put("dashboard.offerRide", new SeedEntry("DASHBOARD", "Offer a Ride", "सवारी की पेशकश करें"));
        map.put("dashboard.whereTo", new SeedEntry("DASHBOARD", "Where are you going?", "आप कहाँ जा रहे हैं?"));
        map.put("dashboard.departureCity",
                new SeedEntry("DASHBOARD", "Departure City / Area", "प्रस्थान शहर / क्षेत्र"));
        map.put("dashboard.destinationCity",
                new SeedEntry("DASHBOARD", "Destination City / Area", "गंतव्य शहर / क्षेत्र"));
        map.put("dashboard.searchRides", new SeedEntry("DASHBOARD", "Search Available Rides", "उपलब्ध सवारियां खोजें"));
        map.put("dashboard.recentTrips", new SeedEntry("DASHBOARD", "Recent Trips", "हाल की यात्राएं"));
        map.put("dashboard.popularRoutes", new SeedEntry("DASHBOARD", "Popular Routes", "लोकप्रिय मार्ग"));
        map.put("dashboard.safetyShieldActive",
                new SeedEntry("DASHBOARD", "RideShare Shield Active", "राइडशेयर शील्ड सक्रिय है"));

        // BOOKING
        map.put("booking.bookSeat", new SeedEntry("BOOKING", "Book Seat", "सीट बुक करें"));
        map.put("booking.selectSeats", new SeedEntry("BOOKING", "Select Number of Seats", "सीटों की संख्या चुनें"));
        map.put("booking.seatsAvailable", new SeedEntry("BOOKING", "Seats Available", "सीटें उपलब्ध"));
        map.put("booking.pricePerSeat", new SeedEntry("BOOKING", "Price per Seat", "प्रति सीट किराया"));
        map.put("booking.totalFare", new SeedEntry("BOOKING", "Total Amount", "कुल राशि"));
        map.put("booking.confirmBooking", new SeedEntry("BOOKING", "Confirm & Pay", "पुष्टि करें और भुगतान करें"));
        map.put("booking.bookingConfirmed", new SeedEntry("BOOKING", "Booking Confirmed!", "बुकिंग की पुष्टि हो गई!"));
        map.put("booking.cancelRide", new SeedEntry("BOOKING", "Cancel Ride", "सवारी रद्द करें"));
        map.put("booking.tripStatus", new SeedEntry("BOOKING", "Trip Status", "यात्रा की स्थिति"));
        map.put("booking.driverArriving", new SeedEntry("BOOKING", "Driver is on the way", "ड्राइवर रास्ते में है"));
        map.put("booking.startPin", new SeedEntry("BOOKING", "Ride Start PIN", "राइड स्टार्ट पिन"));
        map.put("booking.shareWithDriver",
                new SeedEntry("BOOKING", "Share with driver upon pickup", "पिकअप के समय ड्राइवर के साथ साझा करें"));

        // PUBLISH
        map.put("publish.title", new SeedEntry("PUBLISH", "Publish a Journey", "यात्रा प्रकाशित करें"));
        map.put("publish.origin", new SeedEntry("PUBLISH", "Starting Location", "प्रारंभिक स्थान"));
        map.put("publish.destination", new SeedEntry("PUBLISH", "Drop Location", "गंतव्य स्थान"));
        map.put("publish.dateAndTime", new SeedEntry("PUBLISH", "Date & Time", "तारीख और समय"));
        map.put("publish.seatsToOffer", new SeedEntry("PUBLISH", "Available Seats", "उपलब्ध सीटें"));
        map.put("publish.price", new SeedEntry("PUBLISH", "Price per Passenger", "प्रति यात्री किराया"));
        map.put("publish.selectVehicle", new SeedEntry("PUBLISH", "Select Vehicle", "वाहन चुनें"));
        map.put("publish.submit", new SeedEntry("PUBLISH", "Publish Ride Now", "अब सवारी प्रकाशित करें"));

        // WALLET
        map.put("wallet.title", new SeedEntry("WALLET", "Wallet & Payments", "वॉलेट और भुगतान"));
        map.put("wallet.balance", new SeedEntry("WALLET", "alance", "उपलब्ध बैलेंस"));
        map.put("wallet.topUp", new SeedEntry("WALLET", "Add Money", "पैसे जोड़ें"));
        map.put("wallet.recentTransactions", new SeedEntry("WALLET", "Transaction History", "लेन-देन का इतिहास"));
        map.put("wallet.enterAmount", new SeedEntry("WALLET", "Enter Amount (₹)", "राशि दर्ज करें (₹)"));
        map.put("wallet.paySecurely",
                new SeedEntry("WALLET", "Pay Securely via Razorpay", "रेज़रपे से सुरक्षित भुगतान करें"));
        map.put("wallet.refundStatus", new SeedEntry("WALLET", "Refund Status", "रिफंड स्थिति"));
        map.put("wallet.allTransactions", new SeedEntry("WALLET", "All Transactions", "सभी लेन-देन"));

        // DRIVER
        map.put("driver.dashboardTitle", new SeedEntry("DRIVER", "Driver Dashboard", "ड्राइवर डैशबोर्ड"));
        map.put("driver.modeActive", new SeedEntry("DRIVER", "Driver Mode Active", "ड्राइवर मोड सक्रिय"));
        map.put("driver.todayEarnings", new SeedEntry("DRIVER", "Today's Earnings", "आज की कमाई"));
        map.put("driver.totalTrips", new SeedEntry("DRIVER", "Total Completed Trips", "कुल पूर्ण यात्राएं"));
        map.put("driver.requestPayout", new SeedEntry("DRIVER", "Request Payout", "पेआउट का अनुरोध करें"));
        map.put("driver.becomeDriver", new SeedEntry("DRIVER", "Become a Verified Driver", "सत्यापित ड्राइवर बनें"));
        map.put("driver.uploadDocs",
                new SeedEntry("DRIVER", "Upload Driving Licence & RC", "ड्राइविंग लाइसेंस और आरसी अपलोड करें"));
        map.put("driver.digilockerVerify",
                new SeedEntry("DRIVER", "Instant Verification via DigiLocker", "डिजिलॉकर से त्वरित सत्यापन"));

        // SAFETY
        map.put("safety.title", new SeedEntry("SAFETY", "Safety Center & SOS", "सुरक्षा केंद्र और एसओएस"));
        map.put("safety.sosTitle", new SeedEntry("SAFETY", "Emergency Assistance (SOS)", "आपातकालीन सहायता (एसओएस)"));
        map.put("safety.sosSub",
                new SeedEntry("SAFETY", "Tap below in case of any medical, security, or road emergency during a ride.",
                        "सवारी के दौरान किसी भी चिकित्सा, सुरक्षा या सड़क आपात स्थिति के लिए नीचे टैप करें।"));
        map.put("safety.dial112", new SeedEntry("SAFETY", "DIAL EMERGENCY (112)", "आपातकालीन नंबर डायल करें (112)"));
        map.put("safety.shareTrip", new SeedEntry("SAFETY", "Share Live Trip", "लाइव यात्रा साझा करें"));
        map.put("safety.trustedContacts",
                new SeedEntry("SAFETY", "Trusted Emergency Contacts", "भरोसेमंद आपातकालीन संपर्क"));
        map.put("safety.addContact", new SeedEntry("SAFETY", "Add Contact", "संपर्क जोड़ें"));
        map.put("safety.reportConcern",
                new SeedEntry("SAFETY", "Report a Safety Concern", "सुरक्षा संबंधी चिंता की रिपोर्ट करें"));

        // SUPPORT
        map.put("support.title", new SeedEntry("SUPPORT", "Help Center & Support", "सहायता केंद्र और सपोर्ट"));
        map.put("support.faqs", new SeedEntry("SUPPORT", "Frequently Asked Questions", "अक्सर पूछे जाने वाले प्रश्न"));
        map.put("support.raiseTicket", new SeedEntry("SUPPORT", "Raise Support Ticket", "सपोर्ट टिकट बनाएं"));
        map.put("support.callHelpline", new SeedEntry("SUPPORT", "Call 24/7 Helpline", "24/7 हेल्पलाइन पर कॉल करें"));
        map.put("support.subject", new SeedEntry("SUPPORT", "Issue Subject", "समस्या का विषय"));
        map.put("support.description", new SeedEntry("SUPPORT", "Detailed Description", "विस्तृत विवरण"));

        // PROFILE
        map.put("profile.title", new SeedEntry("PROFILE", "My Profile", "मेरी प्रोफ़ाइल"));
        map.put("profile.editProfile", new SeedEntry("PROFILE", "Edit Profile", "प्रोफ़ाइल संपादित करें"));
        map.put("profile.language", new SeedEntry("PROFILE", "Language / भाषा", "भाषा / Language"));
        map.put("profile.selectLanguage", new SeedEntry("PROFILE", "Select Language", "भाषा चुनें"));
        map.put("profile.settings", new SeedEntry("PROFILE", "Account Settings", "खाता सेटिंग्स"));
        map.put("profile.kycStatus", new SeedEntry("PROFILE", "Identity Verification", "पहचान सत्यापन"));
        map.put("profile.helpSupport", new SeedEntry("PROFILE", "Help & Customer Support", "सहायता और ग्राहक सपोर्ट"));
        map.put("profile.safetyCenter",
                new SeedEntry("PROFILE", "Safety Center & Emergency", "सुरक्षा केंद्र और आपातकाल"));

        return map;
    }

    public static class TranslationAdminItem {
        private String key;
        private String category;
        private String enValue;
        private String hiValue;
        private String enStatus;
        private String hiStatus;
        private String overallStatus; // PUBLISHED, DRAFT, MISSING
        private Long enId;
        private Long hiId;
        private Integer version;
        private LocalDateTime updatedAt;

        // Getters and Setters
        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getEnValue() {
            return enValue;
        }

        public void setEnValue(String enValue) {
            this.enValue = enValue;
        }

        public String getHiValue() {
            return hiValue;
        }

        public void setHiValue(String hiValue) {
            this.hiValue = hiValue;
        }

        public String getEnStatus() {
            return enStatus;
        }

        public void setEnStatus(String enStatus) {
            this.enStatus = enStatus;
        }

        public String getHiStatus() {
            return hiStatus;
        }

        public void setHiStatus(String hiStatus) {
            this.hiStatus = hiStatus;
        }

        public String getOverallStatus() {
            return overallStatus;
        }

        public void setOverallStatus(String overallStatus) {
            this.overallStatus = overallStatus;
        }

        public Long getEnId() {
            return enId;
        }

        public void setEnId(Long enId) {
            this.enId = enId;
        }

        public Long getHiId() {
            return hiId;
        }

        public void setHiId(Long hiId) {
            this.hiId = hiId;
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}
