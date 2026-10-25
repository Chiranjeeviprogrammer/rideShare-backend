package com.rideshare.backend.controller;

import com.rideshare.backend.model.Notification;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.NotificationRepository;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    @Autowired
    private NotificationRepository notificationRepository;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private JwtUtil jwtUtil;

    @GetMapping
    public List<Notification> getMyNotifications(@RequestHeader("Authorization") String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    // A test endpoint to simulate a notification arriving
    @PostMapping("/test-generate")
    public Notification generateTestNotification(@RequestHeader("Authorization") String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email);
        
        Notification notif = new Notification();
        notif.setTitle("Ride Confirmed!");
        notif.setMessage("Your ride to Jaipur has been confirmed by Rahul Sharma.");
        notif.setType("RIDE_UPDATE");
        notif.setUser(user);
        notif.setRead(false);
        
        return notificationRepository.save(notif);
    }
}
