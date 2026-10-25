package com.rideshare.backend.controller;

import com.rideshare.backend.model.User;
import com.rideshare.backend.model.UserDocument;
import com.rideshare.backend.repository.UserDocumentRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import com.rideshare.backend.service.SupportPermissionService;
import com.rideshare.backend.service.UserDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class UserDocumentController {

    @Autowired
    private UserDocumentService userDocumentService;

    @Autowired
    private UserDocumentRepository userDocumentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private SupportPermissionService supportPermissionService;

    private User getAuthenticatedUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            String email = jwtUtil.extractEmail(token);
            return userRepository.findByEmail(email);
        } catch (Exception e) {
            return null;
        }
    }

    private User getAuthenticatedUserFromTokenOrHeader(String authHeader, String tokenParam) {
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (tokenParam != null && !tokenParam.isBlank()) {
            token = tokenParam;
        }

        if (token == null) return null;
        try {
            String email = jwtUtil.extractEmail(token);
            return userRepository.findByEmail(email);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Admin Endpoint: List all documents uploaded by a specific user.
     */
    @GetMapping("/admin/users/{userId}/documents")
    public ResponseEntity<?> getUserDocuments(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable("userId") Long userId) {

        Long targetUserId = userId;
        User admin = getAuthenticatedUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }

        boolean canViewDocs = "ADMIN".equalsIgnoreCase(admin.getRole()) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.VIEW_DRIVER_DOCS) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.VIEW_USERS);

        if (!canViewDocs) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied. Required permission: VIEW_DRIVER_DOCS"));
        }

        List<UserDocument> docs = userDocumentService.getUserDocuments(targetUserId, admin);
        return ResponseEntity.ok(docs);
    }

    /**
     * Admin Endpoint: Review, approve, reject, or request replacement for a user document.
     */
    @PutMapping("/admin/users/{userId}/documents/{documentId}/verify")
    public ResponseEntity<?> verifyUserDocument(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable Long userId,
            @PathVariable Long documentId,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "X-Forwarded-For", required = false) String clientIp) {

        User admin = getAuthenticatedUser(authHeader);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }

        boolean canManage = "ADMIN".equalsIgnoreCase(admin.getRole()) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.MANAGE_DRIVERS) ||
                supportPermissionService.hasPermission(admin, SupportPermissionService.MANAGE_SUPPORT);

        if (!canManage) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied. Required permission: MANAGE_DRIVERS"));
        }

        String status = payload.get("status");
        String rejectionReason = payload.get("rejectionReason");
        if (rejectionReason == null || rejectionReason.isBlank()) {
            rejectionReason = payload.get("reason");
        }

        try {
            UserDocument updated = userDocumentService.verifyDocument(documentId, admin, status, rejectionReason, clientIp);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Failed to verify document: " + ex.getMessage()));
        }
    }

    /**
     * Secure Document Stream: Stream image or PDF inline for authorized dashboard viewing.
     */
    @GetMapping({"/admin/documents/{documentId}/stream", "/documents/{documentId}/stream"})
    public ResponseEntity<?> streamDocument(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "token", required = false) String tokenParam,
            @PathVariable Long documentId) {

        User user = getAuthenticatedUserFromTokenOrHeader(authHeader, tokenParam);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }

        Optional<UserDocument> docOpt = userDocumentRepository.findById(documentId);
        if (docOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Document not found"));
        }
        UserDocument doc = docOpt.get();

        // If fileUrl is a public external link (mock/unsplash/sample), redirect safely
        if (doc.getFileUrl() != null && doc.getFileUrl().startsWith("http") && !doc.getFileUrl().contains("/api/admin/documents/")) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(doc.getFileUrl())).build();
        }

        try {
            Resource resource = userDocumentService.loadDocumentFile(documentId, user);
            String mimeType = doc.getMimeType() != null ? doc.getMimeType() : "image/jpeg";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + doc.getDocumentName() + "\"")
                    .body(resource);
        } catch (SecurityException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", ex.getMessage()));
        } catch (IOException ex) {
            // If local file is missing on disk, return helpful placeholder or 404
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Document binary is not available on local storage vault."));
        }
    }

    /**
     * Secure Document Download: Download document with attachment header.
     */
    @GetMapping({"/admin/documents/{documentId}/download", "/documents/{documentId}/download"})
    public ResponseEntity<?> downloadDocument(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "token", required = false) String tokenParam,
            @PathVariable Long documentId) {

        User user = getAuthenticatedUserFromTokenOrHeader(authHeader, tokenParam);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }

        Optional<UserDocument> docOpt = userDocumentRepository.findById(documentId);
        if (docOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Document not found"));
        }
        UserDocument doc = docOpt.get();

        if (doc.getFileUrl() != null && doc.getFileUrl().startsWith("http") && !doc.getFileUrl().contains("/api/admin/documents/")) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(doc.getFileUrl())).build();
        }

        try {
            Resource resource = userDocumentService.loadDocumentFile(documentId, user);
            String mimeType = doc.getMimeType() != null ? doc.getMimeType() : "application/octet-stream";
            String filename = doc.getDocumentName().replaceAll("[^a-zA-Z0-9.-]", "_");

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (SecurityException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", ex.getMessage()));
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Document file not found"));
        }
    }

    /**
     * Mobile / User Upload Endpoint: User uploads any verification or profile document.
     */
    @PostMapping(value = "/users/me/documents/{documentType}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadMyDocument(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable String documentType,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "documentNumber", required = false) String documentNumber,
            @RequestParam(value = "expiryDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate) {

        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }

        try {
            UserDocument saved = userDocumentService.uploadDocument(user, documentType, file, documentNumber, expiryDate);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Upload failed: " + ex.getMessage()));
        }
    }

    /**
     * Mobile / User Endpoint: List authenticated user's own documents.
     */
    @GetMapping("/users/me/documents")
    public ResponseEntity<?> getMyDocuments(@RequestHeader("Authorization") String authHeader) {
        User user = getAuthenticatedUser(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        }
        return ResponseEntity.ok(userDocumentService.getUserDocuments(user.getId(), user));
    }
}
