package com.pacvue.mcpgty.flow;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 令牌桶限流（按租户）
 * 60 次/分钟/租户，超出返回 false
 * 原理：每个租户一个桶，桶里最多 60 个 token，每秒补充 1 个
 */
@Component
public class RateLimiter {

    private static final int CAPACITY = 60;      // 桶容量
    private static final int REFILL_PER_SEC = 1; // 每秒补 1 个（60/分钟）

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * 尝试获取一个 token
     * @return true=放行，false=限流
     */
    public boolean tryAcquire(String tenantId) {
        Bucket bucket = buckets.computeIfAbsent(tenantId, k -> new Bucket());
        return bucket.tryConsume();
    }

    /**
     * 令牌桶内部实现
     */
    private static class Bucket {
        private final AtomicInteger tokens = new AtomicInteger(CAPACITY);
        private long lastRefillTime = System.currentTimeMillis();

        synchronized boolean tryConsume() {
            // 补 token
            long now = System.currentTimeMillis();
            long elapsedMs = now - lastRefillTime;
            int refill = (int) (elapsedMs / 1000 * REFILL_PER_SEC);
            if (refill > 0) {
                tokens.set(Math.min(CAPACITY, tokens.get() + refill));
                lastRefillTime = now;
            }
            // 取 token
            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        }
    }
}
