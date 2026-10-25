package com.rideshare.backend.service;

import com.rideshare.backend.model.Notification;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private NotificationRepository notificationRepository;

    public void sendNotification(User user, String title, String message) {
        if (user == null) return;
        try {
            Notification notification = new Notification();
            notification.setUser(user);
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setType("INFO");
            notification.setRead(false);
            notification.setCreatedAt(LocalDateTime.now());
            notificationRepository.save(notification);

            logger.info("Notification sent to userId {}: {}", user.getId(), title);
        } catch (Exception e) {
            logger.error("Failed to persist notification for user {}: {}", user.getId(), e.getMessage());
        }
    }
}
