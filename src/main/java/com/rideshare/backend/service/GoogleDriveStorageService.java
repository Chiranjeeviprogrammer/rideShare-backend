package com.rideshare.backend.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.FileContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.rideshare.backend.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class GoogleDriveStorageService {
    private static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;
    private static final List<String> ALLOWED_TYPES = List.of("image/jpeg", "image/png", "application/pdf");

    @Value("${google.drive.parent-folder-id:}")
    private String parentFolderId;

    @Value("${google.drive.service-account-file:}")
    private String credentialsFile;

    public Map<String, String> uploadDriverDocument(User user, String documentType, MultipartFile upload) throws Exception {
        if (parentFolderId == null || parentFolderId.isBlank() || credentialsFile == null || credentialsFile.isBlank()) {
            throw new IllegalStateException("Google Drive document storage is not configured.");
        }
        if (upload == null || upload.isEmpty() || upload.getSize() > MAX_DOCUMENT_BYTES || !ALLOWED_TYPES.contains(upload.getContentType())) {
            throw new IllegalArgumentException("Upload a JPG, PNG, or PDF document no larger than 10 MB.");
        }
        String safeType = switch (documentType) {
            case "LICENSE_FRONT", "LICENSE_BACK", "RC", "INSURANCE" -> documentType;
            default -> throw new IllegalArgumentException("Unsupported document type.");
        };

        Drive drive = createDrive();
        String folderId = getOrCreateDriverFolder(drive, user);
        String extension = upload.getContentType().equals("application/pdf") ? ".pdf" : upload.getContentType().equals("image/png") ? ".png" : ".jpg";
        Path temporaryFile = Files.createTempFile("rideshare-document-", extension);
        try {
            upload.transferTo(temporaryFile);
            File metadata = new File().setName(safeType.toLowerCase() + extension).setParents(List.of(folderId));
            File created = drive.files().create(metadata, new FileContent(upload.getContentType(), temporaryFile.toFile()))
                    .setFields("id,webViewLink").execute();
            return Map.of("fileId", created.getId(), "url", created.getWebViewLink());
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public boolean canAccessConfiguredFolder() {
        try {
            createDrive().files().get(parentFolderId).setFields("id").execute();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Drive createDrive() throws Exception {
        try (InputStream input = Files.newInputStream(Path.of(credentialsFile))) {
            GoogleCredentials credentials = GoogleCredentials.fromStream(input).createScoped(DriveScopes.DRIVE_FILE);
            return new Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GsonFactory.getDefaultInstance(), new HttpCredentialsAdapter(credentials))
                    .setApplicationName("RideShare Document Storage").build();
        }
    }

    private String getOrCreateDriverFolder(Drive drive, User user) throws Exception {
        String folderName = "driver-" + user.getId() + "-" + safeSegment(user.getName());
        String query = "name = '" + folderName.replace("'", "\\'") + "' and '" + parentFolderId + "' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        List<File> files = drive.files().list().setQ(query).setFields("files(id)").execute().getFiles();
        if (files != null && !files.isEmpty()) return files.get(0).getId();
        File folder = new File().setName(folderName).setMimeType("application/vnd.google-apps.folder").setParents(List.of(parentFolderId));
        return drive.files().create(folder).setFields("id").execute().getId();
    }

    private String safeSegment(String value) {
        return (value == null ? "unknown" : value).replaceAll("[^A-Za-z0-9_-]", "-").replaceAll("-+", "-");
    }
}
