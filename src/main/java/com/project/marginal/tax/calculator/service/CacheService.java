package com.project.marginal.tax.calculator.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    private static final Duration TTL = Duration.ofHours(1);

    private final RedisTemplate<String, Object> redisTemplate;

    public void put(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value, TTL);
        } catch (Exception e) {
            log.warn("Cache write failed for key {}: {}", key, e.getMessage());
        }
    }

    public Object get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Cache read failed for key {}: {}", key, e.getMessage());
            return null;
        }
    }

    public void evictByPattern(String pattern) {
        try {
            Set<String> keys = redisTemplate.keys(pattern);
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Evicted {} keys matching pattern {}", keys.size(), pattern);
            }
        } catch (Exception e) {
            log.warn("Cache eviction failed for pattern {}: {}", pattern, e.getMessage());
        }
    }
}