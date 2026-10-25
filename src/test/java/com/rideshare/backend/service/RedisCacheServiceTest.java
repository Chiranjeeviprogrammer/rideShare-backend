package com.rideshare.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RedisCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection redisConnection;

    @InjectMocks
    private RedisCacheService redisCacheService;

    @BeforeEach
    public void setup() {
        ReflectionTestUtils.setField(redisCacheService, "redisEnabled", true);
    }

    @Test
    @DisplayName("Cache-Aside Read: Cache Hit returns value and increments hit counter")
    public void testCacheHitIncrementsCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ride:detail:100")).thenReturn("RideData100");

        Optional<String> result = redisCacheService.get("ride:detail:100", String.class);

        assertTrue(result.isPresent());
        assertEquals("RideData100", result.get());

        Map<String, Object> stats = redisCacheService.getCacheStats();
        assertEquals(1L, stats.get("hits"));
        assertEquals(0L, stats.get("misses"));
        assertEquals(100.0, (Double) stats.get("hitRatePercent"), 0.1);
    }

    @Test
    @DisplayName("Cache-Aside Read: Cache Miss returns empty and increments miss counter")
    public void testCacheMissIncrementsCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ride:detail:999")).thenReturn(null);

        Optional<String> result = redisCacheService.get("ride:detail:999", String.class);

        assertFalse(result.isPresent());

        Map<String, Object> stats = redisCacheService.getCacheStats();
        assertEquals(0L, stats.get("hits"));
        assertEquals(1L, stats.get("misses"));
        assertEquals(0.0, (Double) stats.get("hitRatePercent"), 0.1);
    }

    @Test
    @DisplayName("Graceful Fallback: When Redis throws an exception, returns empty Optional without crashing")
    public void testGracefulFallbackOnRedisFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        Optional<String> result = redisCacheService.get("rides:search:test", String.class);

        assertFalse(result.isPresent(), "Must fall back gracefully to DB query");
        Map<String, Object> stats = redisCacheService.getCacheStats();
        assertTrue((Long) stats.get("failures") > 0);
    }

    @Test
    @DisplayName("Cache Eviction: evictRideCaches invalidates ride detail and all search queries")
    public void testEvictRideCaches() {
        when(redisTemplate.keys("rides:search:*")).thenReturn(Set.of("rides:search:delhi-jaipur", "rides:search:noida-gurgaon"));

        redisCacheService.evictRideCaches(100L);

        // Verify specific ride detail deleted
        verify(redisTemplate).delete("ride:detail:100");
        // Verify search queries evicted so passengers see fresh inventory
        verify(redisTemplate).delete(Set.of("rides:search:delhi-jaipur", "rides:search:noida-gurgaon"));
    }

    @Test
    @DisplayName("Cache Eviction: evictSettingsCache invalidates platform settings and cancellation rules")
    public void testEvictSettingsCache() {
        redisCacheService.evictSettingsCache();

        verify(redisTemplate).delete(RedisCacheService.PREFIX_PLATFORM_SETTINGS);
        verify(redisTemplate).delete(RedisCacheService.PREFIX_CANCELLATION_RULES);
    }

    @Test
    @DisplayName("Health Probe: isAvailable returns true when Redis ping responds with PONG")
    public void testIsAvailableWithHealthyConnection() {
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        boolean available = redisCacheService.isAvailable();

        assertTrue(available);
        verify(redisConnection).close();
    }
}
