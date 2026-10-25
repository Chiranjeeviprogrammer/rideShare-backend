package com.rideshare.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance Cache-Aside service backed by Redis Cloud.
 * Implements graceful fallback to relational PostgreSQL database upon any Redis failure.
 */
@Slf4j
@Service
public class RedisCacheService {

    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${rideshare.redis.enabled:true}")
    private boolean redisEnabled;

    // In-memory cache telemetry counters
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong putCount = new AtomicLong(0);
    private final AtomicLong deleteCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);

    // Standard Cache Key Prefixes
    public static final String PREFIX_RIDES_SEARCH = "rides:search:";
    public static final String PREFIX_RIDE_DETAIL = "ride:detail:";
    public static final String PREFIX_DRIVER_PROFILE = "driver:profile:";
    public static final String PREFIX_PLATFORM_SETTINGS = "platform:settings";
    public static final String PREFIX_CANCELLATION_RULES = "cancellation:rules";
    public static final String PREFIX_ACTIVE_COUPONS = "coupons:active";
    public static final String PREFIX_COUPON_CODE = "coupon:code:";

    // Standard TTLs
    public static final Duration TTL_SEARCH = Duration.ofMinutes(3);
    public static final Duration TTL_RIDE_DETAIL = Duration.ofMinutes(10);
    public static final Duration TTL_DRIVER_PROFILE = Duration.ofMinutes(15);
    public static final Duration TTL_SETTINGS = Duration.ofMinutes(30);
    public static final Duration TTL_COUPONS = Duration.ofMinutes(15);


    public boolean isAvailable() {
        if (!redisEnabled || redisTemplate == null) {
            return false;
        }
        try {
            RedisConnection connection = redisTemplate.getConnectionFactory() != null ?
                    redisTemplate.getConnectionFactory().getConnection() : null;
            if (connection != null) {
                String pingResponse = connection.ping();
                connection.close();
                return "PONG".equalsIgnoreCase(pingResponse);
            }
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.warn("[RedisCache] Health check ping failed (falling back to database): {}", e.getMessage());
        }
        return false;
    }

    public long pingLatencyMs() {
        if (!redisEnabled || redisTemplate == null) {
            return -1;
        }
        long start = System.currentTimeMillis();
        try {
            RedisConnection connection = redisTemplate.getConnectionFactory() != null ?
                    redisTemplate.getConnectionFactory().getConnection() : null;
            if (connection != null) {
                connection.ping();
                connection.close();
                return System.currentTimeMillis() - start;
            }
        } catch (Exception e) {
            failureCount.incrementAndGet();
            return -1;
        }
        return -1;
    }

    public <T> Optional<T> get(String key, Class<T> clazz) {
        if (!redisEnabled || redisTemplate == null) {
            return Optional.empty();
        }
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                hitCount.incrementAndGet();
                if (clazz.isInstance(cached)) {
                    return Optional.of(clazz.cast(cached));
                }
            } else {
                missCount.incrementAndGet();
            }
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.warn("[RedisCache] Read failed for key '{}' (falling back to database): {}", key, e.getMessage());
        }
        return Optional.empty();
    }

    public void set(String key, Object value, Duration ttl) {
        if (!redisEnabled || redisTemplate == null || value == null) {
            return;
        }
        try {
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redisTemplate.opsForValue().set(key, value, ttl);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
            putCount.incrementAndGet();
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.warn("[RedisCache] Write failed for key '{}': {}", key, e.getMessage());
        }
    }

    public void delete(String key) {
        if (!redisEnabled || redisTemplate == null || key == null) {
            return;
        }
        try {
            redisTemplate.delete(key);
            deleteCount.incrementAndGet();
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.warn("[RedisCache] Delete failed for key '{}': {}", key, e.getMessage());
        }
    }

    public void deletePattern(String pattern) {
        if (!redisEnabled || redisTemplate == null || pattern == null) {
            return;
        }
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                deleteCount.addAndGet(keys.size());
                log.debug("[RedisCache] Evicted {} keys matching pattern '{}'", keys.size(), pattern);
            }
        } catch (Exception e) {
            failureCount.incrementAndGet();
            log.warn("[RedisCache] Pattern delete failed for '{}': {}", pattern, e.getMessage());
        }
    }

    public void evictRideCaches(Long rideId) {
        if (rideId != null) {
            delete(PREFIX_RIDE_DETAIL + rideId);
        }
        // Invalidate all search query caches so riders see fresh seat availability immediately
        deletePattern(PREFIX_RIDES_SEARCH + "*");
    }

    public void evictSettingsCache() {
        delete(PREFIX_PLATFORM_SETTINGS);
        delete(PREFIX_CANCELLATION_RULES);
    }

    public void evictCouponCaches(String code) {
        delete(PREFIX_ACTIVE_COUPONS);
        if (code != null && !code.trim().isEmpty()) {
            delete(PREFIX_COUPON_CODE + code.trim().toUpperCase());
        }
    }


    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        long hits = hitCount.get();
        long misses = missCount.get();
        long total = hits + misses;
        double hitRatio = total > 0 ? ((double) hits / total) * 100.0 : 0.0;

        stats.put("enabled", redisEnabled);
        stats.put("available", isAvailable());
        stats.put("latencyMs", pingLatencyMs());
        stats.put("hits", hits);
        stats.put("misses", misses);
        stats.put("hitRatePercent", Math.round(hitRatio * 10.0) / 10.0);
        stats.put("puts", putCount.get());
        stats.put("deletions", deleteCount.get());
        stats.put("failures", failureCount.get());
        stats.put("strategy", "Cache-Aside with DB Fallback");
        return stats;
    }
}
