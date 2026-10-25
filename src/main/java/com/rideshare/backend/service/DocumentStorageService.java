package com.rideshare.backend.service;

import com.rideshare.backend.model.User;
import com.rideshare.backend.model.UserDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

@Service
public class DocumentStorageService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentStorageService.class);
    private static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024; // 10 MB
    private static final List<String> ALLOWED_MIME_TYPES = List.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf"
    );

    @Value("${rideshare.storage.local-vault-dir:uploads/documents}")
    private String localVaultDir;

    @Autowired(required = false)
    private GoogleDriveStorageService googleDriveStorageService;

    public static class StoredDocumentResult {
        public String storageProvider; // LOCAL_VAULT or GOOGLE_DRIVE
        public String storagePath;
        public String publicOrStreamUrl;
        public String mimeType;
        public long fileSizeBytes;

        public StoredDocumentResult(String storageProvider, String storagePath, String publicOrStreamUrl, String mimeType, long fileSizeBytes) {
            this.storageProvider = storageProvider;
            this.storagePath = storagePath;
            this.publicOrStreamUrl = publicOrStreamUrl;
            this.mimeType = mimeType;
            this.fileSizeBytes = fileSizeBytes;
        }
    }

    /**
     * Stores an uploaded document securely with automatic Local Vault / Google Drive failover.
     */
    public StoredDocumentResult storeDocument(User user, String documentType, MultipartFile file) throws IOException {
        validateFile(file);

        String contentType = file.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "image/jpeg";
        }
        long size = file.getSize();

        // 1. Try Google Drive if configured
        if (googleDriveStorageService != null) {
            try {
                if (googleDriveStorageService.canAccessConfiguredFolder()) {
                    Map<String, String> driveRes = googleDriveStorageService.uploadDriverDocument(user, mapToDriveType(documentType), file);
                    String fileId = driveRes.get("fileId");
                    String viewLink = driveRes.get("url");
                    logger.info("Successfully stored document in Google Drive for user {}: {}", user.getId(), fileId);
                    return new StoredDocumentResult("GOOGLE_DRIVE", fileId, viewLink, contentType, size);
                }
            } catch (Exception ex) {
                logger.warn("Google Drive storage failed or is unconfigured for user {}, falling back to Local Vault: {}", user.getId(), ex.getMessage());
            }
        }

        // 2. Local Vault Storage
        Path userFolder = Paths.get(localVaultDir, "user_" + user.getId()).toAbsolutePath().normalize();
        if (!Files.exists(userFolder)) {
            Files.createDirectories(userFolder);
        }

        String extension = getExtensionForMimeType(contentType, file.getOriginalFilename());
        String safeName = documentType.toLowerCase().replaceAll("[^a-z0-9_-]", "_") + "_" + System.currentTimeMillis() + extension;
        Path targetPath = userFolder.resolve(safeName).normalize();

        // Prevent path traversal
        if (!targetPath.startsWith(userFolder)) {
            throw new SecurityException("Invalid target file path.");
        }

        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Successfully stored document in Local Vault for user {}: {}", user.getId(), targetPath);

        return new StoredDocumentResult("LOCAL_VAULT", targetPath.toString(), null, contentType, size);
    }

    /**
     * Resolves a stored document file as a Spring Resource for previewing or downloading.
     */
    public Resource loadAsResource(UserDocument document) throws IOException {
        if (document == null || document.getStoragePath() == null || document.getStoragePath().isBlank()) {
            throw new IllegalArgumentException("Document has no stored file path.");
        }

        Path filePath = Paths.get(document.getStoragePath()).toAbsolutePath().normalize();
        try {
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new IOException("File does not exist or is not readable: " + filePath.getFileName());
            }
        } catch (MalformedURLException e) {
            throw new IOException("Malformed file path URL: " + filePath, e);
        }
    }

    public void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please select a document file to upload.");
        }
        if (file.getSize() > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("File size exceeds 10 MB limit. Please compress or choose a smaller file.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Unsupported file type (" + contentType + "). Allowed types: JPEG, PNG, WEBP, PDF.");
        }
    }

    private String getExtensionForMimeType(String mimeType, String originalFilename) {
        if (originalFilename != null && originalFilename.contains(".")) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
            if (List.of(".jpg", ".jpeg", ".png", ".webp", ".pdf").contains(ext)) {
                return ext;
            }
        }
        return switch (mimeType.toLowerCase()) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private String mapToDriveType(String documentType) {
        return switch (documentType) {
            case "LICENSE_FRONT" -> "LICENSE_FRONT";
            case "LICENSE_BACK" -> "LICENSE_BACK";
            case "RC" -> "RC";
            case "INSURANCE" -> "INSURANCE";
            default -> "LICENSE_FRONT";
        };
    }
}
