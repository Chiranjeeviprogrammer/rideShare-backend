package com.rideshare.backend.service;

import com.rideshare.backend.model.SupportPermission;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.SupportPermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class SupportPermissionService {

    public static final String VIEW_DASHBOARD = "VIEW_DASHBOARD";
    public static final String VIEW_USERS = "VIEW_USERS";
    public static final String VIEW_DRIVER_DOCS = "VIEW_DRIVER_DOCS";
    public static final String MANAGE_DRIVERS = "MANAGE_DRIVERS";
    public static final String VIEW_RIDES = "VIEW_RIDES";
    public static final String VIEW_FINANCE = "VIEW_FINANCE";
    public static final String MANAGE_SUPPORT = "MANAGE_SUPPORT";
    public static final String MANAGE_BOOKINGS_REFUNDS = "MANAGE_BOOKINGS_REFUNDS";
    public static final String VIEW_LOGS = "VIEW_LOGS";

    public static final List<String> ALL_PERMISSIONS = List.of(
            VIEW_DASHBOARD,
            VIEW_USERS,
            VIEW_DRIVER_DOCS,
            MANAGE_DRIVERS,
            VIEW_RIDES,
            VIEW_FINANCE,
            MANAGE_SUPPORT,
            MANAGE_BOOKINGS_REFUNDS,
            VIEW_LOGS
    );

    @Autowired
    private SupportPermissionRepository supportPermissionRepository;

    @Autowired
    private AuditLogService auditLogService;

    public boolean hasPermission(User user, String permission) {
        if (user == null || "SUSPENDED".equalsIgnoreCase(user.getStatus())) {
            return false;
        }
        if ("ADMIN".equalsIgnoreCase(user.getRole())) {
            return true;
        }
        if ("SUPPORT".equalsIgnoreCase(user.getRole())) {
            if (permission == null || permission.isBlank()) {
                return true;
            }
            return supportPermissionRepository.existsByUserIdAndPermission(user.getId(), permission);
        }
        return false;
    }

    public List<String> getPermissionsForUser(User user) {
        if (user == null) {
            return List.of();
        }
        if ("ADMIN".equalsIgnoreCase(user.getRole())) {
            return ALL_PERMISSIONS;
        }
        if ("SUPPORT".equalsIgnoreCase(user.getRole())) {
            return supportPermissionRepository.findPermissionNamesByUserId(user.getId());
        }
        return List.of();
    }

    @Transactional
    public List<String> updateSupportPermissions(User supportUser, List<String> requestedPermissions, String adminEmail) {
        if (supportUser == null || !"SUPPORT".equalsIgnoreCase(supportUser.getRole())) {
            throw new IllegalArgumentException("Permissions can only be assigned to accounts with the SUPPORT role.");
        }

        List<String> validatedPermissions = new ArrayList<>();
        if (requestedPermissions != null) {
            for (String perm : requestedPermissions) {
                if (perm != null && ALL_PERMISSIONS.contains(perm.trim().toUpperCase())) {
                    validatedPermissions.add(perm.trim().toUpperCase());
                }
            }
        }

        // Get old permissions for audit log
        List<String> oldPerms = supportPermissionRepository.findPermissionNamesByUserId(supportUser.getId());

        // Replace permissions
        supportPermissionRepository.deleteByUserId(supportUser.getId());
        for (String perm : validatedPermissions) {
            supportPermissionRepository.save(new SupportPermission(supportUser, perm, adminEmail));
        }

        // Audit log
        String details = String.format("Updated permissions for support user %s (%d). Old: %s, New: %s",
                supportUser.getEmail(), supportUser.getId(), oldPerms, validatedPermissions);
        auditLogService.log(
                adminEmail,
                "UPDATE_SUPPORT_PERMISSIONS",
                "USER",
                String.valueOf(supportUser.getId()),
                details,
                "127.0.0.1"
        );

        return validatedPermissions;
    }
}
