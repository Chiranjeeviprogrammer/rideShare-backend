package com.rideshare.backend.service;

import com.rideshare.backend.model.DriverApplication;
import com.rideshare.backend.model.User;
import com.rideshare.backend.model.UserDocument;
import com.rideshare.backend.repository.DriverApplicationRepository;
import com.rideshare.backend.repository.UserDocumentRepository;
import com.rideshare.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class UserDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(UserDocumentService.class);

    @Autowired
    private UserDocumentRepository userDocumentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private DocumentStorageService documentStorageService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private SupportPermissionService supportPermissionService;

    public static String getCategoryForType(String type) {
        if (type == null) return "OTHER";
        return switch (type.toUpperCase()) {
            case "AVATAR" -> "PROFILE_PHOTO";
            case "ID_CARD_FRONT", "ID_CARD_BACK" -> "IDENTITY_PROOF";
            case "LICENSE_FRONT", "LICENSE_BACK" -> "DRIVER_LICENSE";
            case "RC" -> "VEHICLE_REGISTRATION";
            case "INSURANCE" -> "VEHICLE_INSURANCE";
            case "PERMIT", "FITNESS" -> "PERMIT_FITNESS";
            case "ADDRESS_PROOF" -> "ADDRESS_PROOF";
            default -> "OTHER_VERIFICATION";
        };
    }

    public static String getDocumentNameForType(String type) {
        if (type == null) return "Document";
        return switch (type.toUpperCase()) {
            case "AVATAR" -> "Profile Photo";
            case "ID_CARD_FRONT" -> "Government ID (Front Side)";
            case "ID_CARD_BACK" -> "Government ID (Back Side)";
            case "LICENSE_FRONT" -> "Driving Licence (Front Side)";
            case "LICENSE_BACK" -> "Driving Licence (Back Side)";
            case "RC" -> "Vehicle Registration Certificate (RC)";
            case "INSURANCE" -> "Vehicle Insurance Certificate";
            case "PERMIT" -> "Vehicle Commercial Permit";
            case "FITNESS" -> "Vehicle Fitness Certificate";
            case "ADDRESS_PROOF" -> "Proof of Address";
            default -> "Document (" + type + ")";
        };
    }

    /**
     * Retrieves all documents for a user, automatically synchronizing legacy records if necessary.
     */
    @Transactional
    public List<UserDocument> getUserDocuments(Long userId, User adminUser) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return Collections.emptyList();
        }
        User user = userOpt.get();

        // Sync legacy or seeded documents from DriverApplication and User if not already in user_documents
        syncLegacyDocumentsIfMissing(user);

        List<UserDocument> docs = userDocumentRepository.findByUserIdOrderByUploadedAtDesc(userId);

        // Check for expired documents and update status if past expiry date
        LocalDate today = LocalDate.now();
        for (UserDocument doc : docs) {
            if (doc.getExpiryDate() != null && doc.getExpiryDate().isBefore(today) && "APPROVED".equalsIgnoreCase(doc.getVerificationStatus())) {
                doc.setVerificationStatus("EXPIRED");
                userDocumentRepository.save(doc);
            }
        }

        return docs;
    }

    /**
     * Uploads and registers a new document for a user.
     */
    @Transactional
    public UserDocument uploadDocument(
            User user,
            String documentType,
            MultipartFile file,
            String documentNumber,
            LocalDate expiryDate) throws IOException {

        String normalizedType = documentType.trim().toUpperCase();
        DocumentStorageService.StoredDocumentResult stored = documentStorageService.storeDocument(user, normalizedType, file);

        // Find existing current version and deactivate
        Optional<UserDocument> existingOpt = userDocumentRepository.findByUserIdAndDocumentTypeAndIsCurrentTrue(user.getId(), normalizedType);
        int version = 1;
        if (existingOpt.isPresent()) {
            UserDocument existing = existingOpt.get();
            existing.setIsCurrent(false);
            userDocumentRepository.save(existing);
            version = (existing.getVersion() != null ? existing.getVersion() : 1) + 1;
        }

        UserDocument doc = new UserDocument();
        doc.setUser(user);
        doc.setDocumentType(normalizedType);
        doc.setDocumentName(getDocumentNameForType(normalizedType));
        doc.setCategory(getCategoryForType(normalizedType));
        doc.setStoragePath(stored.storagePath);
        doc.setStorageProvider(stored.storageProvider);
        doc.setMimeType(stored.mimeType);
        doc.setFileSizeBytes(stored.fileSizeBytes);
        doc.setDocumentNumber(documentNumber);
        doc.setExpiryDate(expiryDate);
        doc.setVerificationStatus("PENDING");
        doc.setUploadedAt(LocalDateTime.now());
        doc.setVersion(version);
        doc.setIsCurrent(true);

        UserDocument saved = userDocumentRepository.save(doc);

        // Compute secure stream URL
        String streamUrl = "/api/admin/documents/" + saved.getId() + "/stream";
        saved.setFileUrl(streamUrl);
        saved = userDocumentRepository.save(saved);

        // Synchronize with parent entities
        synchronizeWithParentModels(user, normalizedType, saved);

        notificationService.sendNotification(
                user,
                "Document Uploaded",
                "Your " + saved.getDocumentName() + " has been received and is queued for verification."
        );

        return saved;
    }

    /**
     * Verifies, rejects, or requests replacement for an individual document.
     */
    @Transactional
    public UserDocument verifyDocument(
            Long documentId,
            User reviewer,
            String newStatus,
            String rejectionReason,
            String ipAddress) {

        Optional<UserDocument> docOpt = userDocumentRepository.findById(documentId);
        if (docOpt.isEmpty()) {
            throw new IllegalArgumentException("Document not found with ID: " + documentId);
        }

        UserDocument doc = docOpt.get();
        String upperStatus = newStatus != null ? newStatus.trim().toUpperCase() : "APPROVED";
        if (!List.of("APPROVED", "REJECTED", "REPLACEMENT_REQUESTED", "PENDING").contains(upperStatus)) {
            throw new IllegalArgumentException("Invalid document verification status: " + newStatus);
        }

        if (("REJECTED".equals(upperStatus) || "REPLACEMENT_REQUESTED".equals(upperStatus))
                && (rejectionReason == null || rejectionReason.trim().isEmpty())) {
            throw new IllegalArgumentException("Rejection or replacement request reason is mandatory.");
        }

        doc.setVerificationStatus(upperStatus);
        doc.setReviewedAt(LocalDateTime.now());
        doc.setReviewedBy(reviewer);
        doc.setRejectionReason(rejectionReason);

        UserDocument updated = userDocumentRepository.save(doc);
        User user = doc.getUser();

        // Audit log action
        auditLogService.log(
                reviewer.getEmail(),
                "DOCUMENT_" + upperStatus,
                "USER_DOCUMENT",
                String.valueOf(documentId),
                "Document '" + doc.getDocumentName() + "' (User #" + user.getId() + " - " + user.getEmail() + ") set to " + upperStatus +
                        (rejectionReason != null ? ". Reason: " + rejectionReason : ""),
                ipAddress
        );

        // Notification to user
        notificationService.sendNotification(
                user,
                "Document Verification Update",
                "Your " + doc.getDocumentName() + " status is now: " + upperStatus +
                        (rejectionReason != null ? " (" + rejectionReason + ")" : "")
        );

        // Post-verification model synchronizations
        updateParentVerificationLifecycles(user, doc, upperStatus);

        return updated;
    }

    /**
     * Securely fetches document file resource for authorized streaming or download.
     */
    public Resource loadDocumentFile(Long documentId, User requester) throws IOException {
        Optional<UserDocument> docOpt = userDocumentRepository.findById(documentId);
        if (docOpt.isEmpty()) {
            throw new IllegalArgumentException("Document not found.");
        }
        UserDocument doc = docOpt.get();

        // Permission check: Admins, staff with VIEW_DRIVER_DOCS, or the document owner themselves
        boolean isOwner = requester != null && requester.getId().equals(doc.getUser().getId());
        boolean hasAdminAccess = requester != null && ("ADMIN".equalsIgnoreCase(requester.getRole()) ||
                supportPermissionService.hasPermission(requester, SupportPermissionService.VIEW_DRIVER_DOCS));

        if (!isOwner && !hasAdminAccess) {
            throw new SecurityException("Unauthorized access to document.");
        }

        return documentStorageService.loadAsResource(doc);
    }

    /**
     * Legacy synchronization: If User has an avatarUrl or DriverApplication has doc URLs/numbers
     * but no matching rows exist in user_documents, automatically populate them.
     */
    private void syncLegacyDocumentsIfMissing(User user) {
        // 1. Profile photo
        if (user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()) {
            Optional<UserDocument> existing = userDocumentRepository.findByUserIdAndDocumentTypeAndIsCurrentTrue(user.getId(), "AVATAR");
            if (existing.isEmpty()) {
                UserDocument doc = new UserDocument();
                doc.setUser(user);
                doc.setDocumentType("AVATAR");
                doc.setDocumentName("Profile Photo");
                doc.setCategory("PROFILE_PHOTO");
                doc.setFileUrl(user.getAvatarUrl());
                doc.setStorageProvider("LEGACY_AVATAR");
                doc.setMimeType("image/jpeg");
                doc.setVerificationStatus("APPROVED");
                doc.setUploadedAt(user.getCreatedAt() != null ? user.getCreatedAt() : LocalDateTime.now());
                doc.setIsCurrent(true);
                userDocumentRepository.save(doc);
            }
        }

        // 2. Identity Card Front / Back
        if (user.getIdCardFrontUrl() != null && !user.getIdCardFrontUrl().isBlank()) {
            Optional<UserDocument> existing = userDocumentRepository.findByUserIdAndDocumentTypeAndIsCurrentTrue(user.getId(), "ID_CARD_FRONT");
            if (existing.isEmpty()) {
                UserDocument doc = new UserDocument();
                doc.setUser(user);
                doc.setDocumentType("ID_CARD_FRONT");
                doc.setDocumentName("Government ID (Front Side)");
                doc.setCategory("IDENTITY_PROOF");
                doc.setFileUrl(user.getIdCardFrontUrl());
                doc.setDocumentNumber(user.getIdCardNumber());
                doc.setStorageProvider("USER_IDENTITY");
                doc.setVerificationStatus("VERIFIED".equalsIgnoreCase(user.getIdVerificationStatus()) ? "APPROVED" : "PENDING");
                doc.setUploadedAt(user.getCreatedAt() != null ? user.getCreatedAt() : LocalDateTime.now());
                doc.setIsCurrent(true);
                userDocumentRepository.save(doc);
            }
        }

        if (user.getIdCardBackUrl() != null && !user.getIdCardBackUrl().isBlank()) {
            Optional<UserDocument> existing = userDocumentRepository.findByUserIdAndDocumentTypeAndIsCurrentTrue(user.getId(), "ID_CARD_BACK");
            if (existing.isEmpty()) {
                UserDocument doc = new UserDocument();
                doc.setUser(user);
                doc.setDocumentType("ID_CARD_BACK");
                doc.setDocumentName("Government ID (Back Side)");
                doc.setCategory("IDENTITY_PROOF");
                doc.setFileUrl(user.getIdCardBackUrl());
                doc.setDocumentNumber(user.getIdCardNumber());
                doc.setStorageProvider("USER_IDENTITY");
                doc.setVerificationStatus("VERIFIED".equalsIgnoreCase(user.getIdVerificationStatus()) ? "APPROVED" : "PENDING");
                doc.setUploadedAt(user.getCreatedAt() != null ? user.getCreatedAt() : LocalDateTime.now());
                doc.setIsCurrent(true);
                userDocumentRepository.save(doc);
            }
        }

        // 3. Driver Application Documents
        Optional<DriverApplication> appOpt = driverApplicationRepository.findByUserId(user.getId());
        if (appOpt.isPresent()) {
            DriverApplication app = appOpt.get();
            String appStatus = "APPROVED".equalsIgnoreCase(app.getStatus()) ? "APPROVED" :
                    ("REJECTED".equalsIgnoreCase(app.getStatus()) ? "REJECTED" : "PENDING");

            // Licence Front
            if (app.getLicenseFrontUrl() != null || app.getLicenseNumber() != null) {
                if (userDocumentRepository.findByUserIdAndDocumentTypeAndIsCurrentTrue(user.getId(), "LICENSE_FRONT").isEmpty()) {
                    UserDocument doc = new UserDocument();
                    doc.setUser(user);
                    doc.setDocumentType("LICENSE_FRONT");
                    doc.setDocumentName("Driving Licence (Front Side)");
                    doc.setCategory("DRIVER_LICENSE");
                    doc.setFileUrl(app.getLicenseFrontUrl());
                    doc.setDocumentNumber(app.getLicenseNumber());
                    doc.setExpiryDate(app.getLicenseExpiryDate());
                    doc.setVerificationStatus(appStatus);
                    doc.setUploadedAt(app.getSubmittedAt() != null ? app.getSubmittedAt() : LocalDateTime.now());
                    doc.setReviewedAt(app.getReviewedAt());
                    doc.setReviewedBy(app.getReviewedBy());
                    doc.setRejectionReason(app.getReviewFeedback());
                    doc.setIsCurrent(true);
                    userDocumentRepository.save(doc);
                }
            }

            // Licence Back
            if (app.getLicenseBackUrl() != null) {
                if (userDocumentRepository.findByUserIdAndDocumentTypeAndIsCurrentTrue(user.getId(), "LICENSE_BACK").isEmpty()) {
                    UserDocument doc = new UserDocument();
                    doc.setUser(user);
                    doc.setDocumentType("LICENSE_BACK");
                    doc.setDocumentName("Driving Licence (Back Side)");
                    doc.setCategory("DRIVER_LICENSE");
                    doc.setFileUrl(app.getLicenseBackUrl());
                    doc.setDocumentNumber(app.getLicenseNumber());
                    doc.setExpiryDate(app.getLicenseExpiryDate());
                    doc.setVerificationStatus(appStatus);
                    doc.setUploadedAt(app.getSubmittedAt() != null ? app.getSubmittedAt() : LocalDateTime.now());
                    doc.setReviewedAt(app.getReviewedAt());
                    doc.setReviewedBy(app.getReviewedBy());
                    doc.setIsCurrent(true);
                    userDocumentRepository.save(doc);
                }
            }

            // Registration Certificate (RC)
            if (app.getRcDocumentUrl() != null || app.getRegistrationNumber() != null) {
                if (userDocumentRepository.findByUserIdAndDocumentTypeAndIsCurrentTrue(user.getId(), "RC").isEmpty()) {
                    UserDocument doc = new UserDocument();
                    doc.setUser(user);
                    doc.setDocumentType("RC");
                    doc.setDocumentName("Vehicle Registration Certificate (RC)");
                    doc.setCategory("VEHICLE_REGISTRATION");
                    doc.setFileUrl(app.getRcDocumentUrl());
                    doc.setDocumentNumber(app.getRegistrationNumber());
                    doc.setVerificationStatus(appStatus);
                    doc.setUploadedAt(app.getSubmittedAt() != null ? app.getSubmittedAt() : LocalDateTime.now());
                    doc.setReviewedAt(app.getReviewedAt());
                    doc.setReviewedBy(app.getReviewedBy());
                    doc.setIsCurrent(true);
                    userDocumentRepository.save(doc);
                }
            }

            // Vehicle Insurance
            if (app.getInsuranceDocumentUrl() != null || app.getInsuranceNumber() != null) {
                if (userDocumentRepository.findByUserIdAndDocumentTypeAndIsCurrentTrue(user.getId(), "INSURANCE").isEmpty()) {
                    UserDocument doc = new UserDocument();
                    doc.setUser(user);
                    doc.setDocumentType("INSURANCE");
                    doc.setDocumentName("Vehicle Insurance Certificate");
                    doc.setCategory("VEHICLE_INSURANCE");
                    doc.setFileUrl(app.getInsuranceDocumentUrl());
                    doc.setDocumentNumber(app.getInsuranceNumber());
                    doc.setExpiryDate(app.getInsuranceExpiryDate());
                    doc.setVerificationStatus(appStatus);
                    doc.setUploadedAt(app.getSubmittedAt() != null ? app.getSubmittedAt() : LocalDateTime.now());
                    doc.setReviewedAt(app.getReviewedAt());
                    doc.setReviewedBy(app.getReviewedBy());
                    doc.setIsCurrent(true);
                    userDocumentRepository.save(doc);
                }
            }
        }
    }

    private void synchronizeWithParentModels(User user, String type, UserDocument saved) {
        String accessUrl = saved.getFileUrl();
        switch (type) {
            case "AVATAR" -> {
                user.setAvatarUrl(accessUrl);
                userRepository.save(user);
            }
            case "ID_CARD_FRONT" -> {
                user.setIdCardFrontUrl(accessUrl);
                if (saved.getDocumentNumber() != null) user.setIdCardNumber(saved.getDocumentNumber());
                user.setIdVerificationStatus("PENDING");
                userRepository.save(user);
            }
            case "ID_CARD_BACK" -> {
                user.setIdCardBackUrl(accessUrl);
                user.setIdVerificationStatus("PENDING");
                userRepository.save(user);
            }
            case "LICENSE_FRONT", "LICENSE_BACK", "RC", "INSURANCE", "PERMIT", "FITNESS" -> {
                Optional<DriverApplication> appOpt = driverApplicationRepository.findByUserId(user.getId());
                DriverApplication app = appOpt.orElseGet(() -> {
                    DriverApplication newApp = new DriverApplication();
                    newApp.setUser(user);
                    newApp.setStatus("DRAFT");
                    return newApp;
                });

                if ("LICENSE_FRONT".equals(type)) {
                    app.setLicenseFrontUrl(accessUrl);
                    if (saved.getDocumentNumber() != null) app.setLicenseNumber(saved.getDocumentNumber());
                    if (saved.getExpiryDate() != null) app.setLicenseExpiryDate(saved.getExpiryDate());
                } else if ("LICENSE_BACK".equals(type)) {
                    app.setLicenseBackUrl(accessUrl);
                } else if ("RC".equals(type)) {
                    app.setRcDocumentUrl(accessUrl);
                    if (saved.getDocumentNumber() != null) app.setRegistrationNumber(saved.getDocumentNumber());
                } else if ("INSURANCE".equals(type)) {
                    app.setInsuranceDocumentUrl(accessUrl);
                    if (saved.getDocumentNumber() != null) app.setInsuranceNumber(saved.getDocumentNumber());
                    if (saved.getExpiryDate() != null) app.setInsuranceExpiryDate(saved.getExpiryDate());
                } else if ("PERMIT".equals(type)) {
                    app.setPermitDocumentUrl(accessUrl);
                    if (saved.getExpiryDate() != null) app.setPermitExpiryDate(saved.getExpiryDate());
                } else if ("FITNESS".equals(type)) {
                    app.setFitnessDocumentUrl(accessUrl);
                    if (saved.getExpiryDate() != null) app.setFitnessExpiryDate(saved.getExpiryDate());
                }
                driverApplicationRepository.save(app);
            }
        }
    }

    private void updateParentVerificationLifecycles(User user, UserDocument doc, String newStatus) {
        String type = doc.getDocumentType();
        if ("ID_CARD_FRONT".equals(type) || "ID_CARD_BACK".equals(type)) {
            if ("APPROVED".equals(newStatus)) {
                user.setIdVerificationStatus("VERIFIED");
                user.setVerified(true);
                userRepository.save(user);
            } else if ("REJECTED".equals(newStatus) || "REPLACEMENT_REQUESTED".equals(newStatus)) {
                user.setIdVerificationStatus("REJECTED");
                userRepository.save(user);
            }
        }
    }
}
