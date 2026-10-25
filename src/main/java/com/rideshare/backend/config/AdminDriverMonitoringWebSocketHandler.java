package com.rideshare.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AdminDriverMonitoringWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> adminSessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // Authenticate admin session via token query param: /ws/admin/monitoring?token=...
        URI uri = session.getUri();
        String token = extractToken(uri);

        if (token == null || !isValidAdminToken(token)) {
            session.sendMessage(new TextMessage("{\"type\":\"AUTH_ERROR\",\"message\":\"Administrative authorization required\"}"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        adminSessions.add(session);
        session.sendMessage(new TextMessage("{\"type\":\"CONNECTED\",\"message\":\"Connected to real-time Driver Monitoring live feed\"}"));
    }

    private String extractToken(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String param : uri.getQuery().split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && "token".equalsIgnoreCase(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    private boolean isValidAdminToken(String token) {
        try {
            String email = jwtUtil.extractEmail(token);
            if (email != null && jwtUtil.validateToken(token, email)) {
                User user = userRepository.findByEmail(email);
                return user != null && "ADMIN".equalsIgnoreCase(user.getRole()) && !"SUSPENDED".equalsIgnoreCase(user.getStatus());
            }
        } catch (Exception e) {
            // Token parsing or signature failure
        }
        return false;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        adminSessions.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        adminSessions.remove(session);
    }

    /**
     * Broadcasts updated monitoring payload to all active authorized admin sessions.
     */
    public void broadcastToAdmins(Object payload) {
        if (adminSessions.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : adminSessions) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(message);
                    } catch (IOException e) {
                        adminSessions.remove(session);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error broadcasting driver monitoring payload: " + e.getMessage());
        }
    }

    public int getActiveAdminConnectionsCount() {
        return adminSessions.size();
    }
}
