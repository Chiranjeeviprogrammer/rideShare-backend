package com.rideshare.backend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private AdminDriverMonitoringWebSocketHandler adminDriverMonitoringWebSocketHandler;

    @Autowired
    private LocationHandler locationHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(locationHandler, "/ws/location").setAllowedOrigins("*");
        registry.addHandler(adminDriverMonitoringWebSocketHandler, "/ws/admin/monitoring").setAllowedOrigins("*");
    }
}
