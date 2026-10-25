package com.rideshare.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {
    private final Key key;
    private final long expirationTime;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration-ms}") long expirationTime) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters long.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        this.expirationTime = expirationTime;
    }

    public String generateToken(String email) {
        return generateToken(email, null, null);
    }

    public String generateToken(String email, String role, Long userId) {
        Map<String, Object> claims = new HashMap<>();
        if (role != null) {
            claims.put("role", role);
        }
        if (userId != null) {
            claims.put("userId", userId);
        }
        return createToken(claims, email);
    }

    public String extractRole(String token) {
        try {
            return extractClaim(token, claims -> (String) claims.get("role"));
        } catch (Exception e) {
            return null;
        }
    }

    public Long extractUserId(String token) {
        try {
            return extractClaim(token, claims -> {
                Object val = claims.get("userId");
                if (val instanceof Number) {
                    return ((Number) val).longValue();
                }
                return null;
            });
        } catch (Exception e) {
            return null;
        }
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(key)
                .compact();
    }

    public Boolean validateToken(String token, String userEmail) {
        final String extractedEmail = extractEmail(token);
        return (extractedEmail.equals(userEmail) && !isTokenExpired(token));
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }
}
