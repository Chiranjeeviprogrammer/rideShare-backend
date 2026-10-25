package com.rideshare.backend.config;

import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.UserRepository;
import com.rideshare.backend.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Secure WebSocket handler for real-time driver and passenger location updates.
 * Requires JWT authentication upon connection and isolates location messages to authorized sessions.
 */
@Component
public class LocationHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LocationHandler.class);

    private final Set<WebSocketSession> authenticatedSessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, String> sessionUserEmailMap = new ConcurrentHashMap<>();

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        String token = extractToken(uri);

        if (token == null || !isValidUserToken(token, session)) {
            session.sendMessage(new TextMessage("{\"type\":\"AUTH_ERROR\",\"message\":\"Authentication required to access location socket\"}"));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        authenticatedSessions.add(session);
        session.sendMessage(new TextMessage("{\"type\":\"CONNECTED\",\"message\":\"Connected to secure real-time location socket\"}"));
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

    private boolean isValidUserToken(String token, WebSocketSession session) {
        try {
            String email = jwtUtil.extractEmail(token);
            if (email != null && jwtUtil.validateToken(token, email)) {
                User user = userRepository.findByEmail(email);
                if (user != null && !"SUSPENDED".equalsIgnoreCase(user.getStatus())) {
                    sessionUserEmailMap.put(session.getId(), email);
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("WebSocket location auth failed: {}", e.getMessage());
        }
        return false;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!authenticatedSessions.contains(session)) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        String senderEmail = sessionUserEmailMap.get(session.getId());
        String payload = message.getPayload();

        // Relay updates strictly to active authenticated peers
        for (WebSocketSession target : authenticatedSessions) {
            if (target.isOpen() && !target.getId().equals(session.getId())) {
                try {
                    target.sendMessage(new TextMessage("{\"sender\":\"" + (senderEmail != null ? senderEmail : "driver") + "\",\"payload\":" + payload + "}"));
                } catch (IOException e) {
                    log.warn("Failed to dispatch location message to session {}: {}", target.getId(), e.getMessage());
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        authenticatedSessions.remove(session);
        sessionUserEmailMap.remove(session.getId());
    }
}
