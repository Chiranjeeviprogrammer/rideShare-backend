package com.rideshare.backend.service;

import com.rideshare.backend.model.AuditLog;
import com.rideshare.backend.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    @Autowired
    private AuditLogRepository auditLogRepository;

    public void log(String adminEmail, String action, String entityType, String entityId, String details, String ipAddress) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .adminEmail(adminEmail != null ? adminEmail : "SYSTEM")
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .details(details)
                    .ipAddress(ipAddress != null ? ipAddress : "0.0.0.0")
                    .timestamp(LocalDateTime.now())
                    .build();
            auditLogRepository.save(auditLog);
            logger.info("AUDIT: [{}] by [{}] on {} {}: {}", action, adminEmail, entityType, entityId, details);
        } catch (Exception e) {
            logger.error("Failed to write audit log: {}", e.getMessage());
        }
    }
}
