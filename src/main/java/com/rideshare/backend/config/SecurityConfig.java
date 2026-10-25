package com.rideshare.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.rideshare.backend.security.JwtFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Autowired
    private JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // Always allow preflight OPTIONS requests
                .requestMatchers("/", "/index.html", "/favicon.ico", "/**/*.css", "/**/*.js", "/**/*.png", "/**/*.svg", "/**/*.jpg").permitAll() // Web Assets
                .requestMatchers("/api/auth/**").permitAll() // Auth (login, register, otp, reset)
                .requestMatchers(HttpMethod.POST, "/api/users").permitAll() // Signup
                .requestMatchers(HttpMethod.GET, "/api/rides", "/api/rides/**").permitAll() // Search published rides
                .requestMatchers("/api/public/**").permitAll() // Public Website APIs (contact, settings, stats)
                .requestMatchers("/api/payments/webhook").permitAll() // Payment gateway webhooks (signature verified)
                .requestMatchers("/api/payments/quote").permitAll() // Pricing quote calculation
                .requestMatchers("/api/payments/methods").permitAll() // Payment methods config
                .requestMatchers("/api/coupons/**").permitAll() // Public coupon offers & validation

                .requestMatchers("/api/email/**").hasRole("ADMIN") // Email diagnostics and test delivery
                .requestMatchers("/api/vehicles/reference").permitAll() // Reference car makes/models
                .requestMatchers("/api/translations/**").permitAll() // Public language translations
                .requestMatchers("/h2-console/**").permitAll() // H2 console
                .requestMatchers("/ws/**").permitAll() // WebSockets
                .requestMatchers("/error").permitAll() // Spring Boot error dispatcher
                .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPPORT") // Admin & Staff endpoints with granular RBAC
                .requestMatchers(HttpMethod.PUT, "/api/driver/applications/*/review").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/driver/applications").hasRole("ADMIN")
                .requestMatchers("/api/commission/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated() // All other requests require authenticated user
            )
            // Allow frames for H2 console
            .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.disable()))
            .addFilterBefore(jwtFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
            
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Use configured allowed origins rather than universal wildcard
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            config.setAllowedOriginPatterns(allowedOrigins);
        } else {
            config.setAllowedOriginPatterns(List.of(
                    "http://localhost:5173",
                    "http://localhost:5174",
                    "http://localhost:5175",
                    "http://localhost:8081",
                    "https://*.ngrok-free.dev",
                    "https://*.ngrok-free.app",
                    "https://*.ngrok.io",
                    "https://*.trycloudflare.com",
                    "https://cultural-giddiness-pueblo.ngrok-free.dev"
            ));
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Link", "X-Total-Count", "ngrok-skip-browser-warning"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
