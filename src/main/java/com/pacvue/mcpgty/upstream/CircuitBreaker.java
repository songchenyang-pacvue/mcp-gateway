package com.pacvue.mcpgty.upstream;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 熔断状态机：每个上游一个实例
 *
 * 状态迁移：
 *   UP --失败率>50% 且 ≥5请求--> DOWN
 *   UP --偶尔失败--> DEGRADED
 *   DOWN --30秒后--> 半开（允许1次探活）
 *   探活成功 --> UP
 *   探活失败 --> 继续 DOWN
 */
public class CircuitBreaker {

    private final String alias;

    // 当前状态：UP（正常）/ DEGRADED（半挂）/ DOWN（熔断）
    private volatile UpstreamState state = UpstreamState.UP;

    // 进入 DOWN 的时间戳，用于计算半开等待
    private volatile long halfOpenSince = 0;

    // ===== 30秒滑动窗口统计 =====
    // 用 AtomicInteger 是因为多个请求线程并发访问，要线程安全
    private final AtomicInteger totalRequests = new AtomicInteger(0);   // 窗口内总请求数
    private final AtomicInteger failedRequests = new AtomicInteger(0);   // 窗口内失败数
    private volatile long windowStart = System.currentTimeMillis();     // 当前窗口开始时间

    // ===== 阈值配置（写死，后面可以挪到配置文件）=====
    private static final long WINDOW_MS = 30_000;              // 统计窗口：30秒
    private static final int MIN_REQUESTS = 5;                  // 最少请求数（避免2次失败就熔断）
    private static final double FAILURE_RATE_THRESHOLD = 0.5;   // 失败率阈值：50%
    private static final long HALF_OPEN_WAIT_MS = 30_000;      // DOWN后等30秒再探活

    public CircuitBreaker(String alias) {
        this.alias = alias;
    }

    /**
     * 转发前调用：这个请求要不要放过去？
     * - DOWN 状态：直接拒绝，除非已经等了30秒（半开探活）
     * - UP/DEGRADED：直接放行
     */
    public synchronized boolean allowRequest() {
        if (state == UpstreamState.DOWN) {
            // DOWN 状态：等够30秒才放一个请求去探活
            if (System.currentTimeMillis() - halfOpenSince >= HALF_OPEN_WAIT_MS) {
                state = UpstreamState.DEGRADED;
                System.out.println("[" + alias + "] 半开探活，允许一次请求");
                return true;
            }
            // 还在熔断期，拒绝
            return false;
        }
        // UP 或 DEGRADED 都放行
        return true;
    }

    /**
     * 调用成功后调用：重置统计，恢复正常
     */
    public synchronized void recordSuccess() {
        resetWindow();
        if (state == UpstreamState.DEGRADED) {
            state = UpstreamState.UP;
            System.out.println("[" + alias + "] 恢复 UP");
        }
    }

    /**
     * 调用失败后调用：累计失败，检查是否触发熔断
     */
    public synchronized void recordFailure() {
        totalRequests.incrementAndGet();
        failedRequests.incrementAndGet();
        // 半开探活失败，立刻回到 DOWN，重新计时
        if (state == UpstreamState.DEGRADED) {
            state = UpstreamState.DOWN;
            halfOpenSince = System.currentTimeMillis();
            System.out.println("[" + alias + "] 半开探活失败，回到 DOWN");
            resetWindow();
            return;
        }
        long now = System.currentTimeMillis();
        // 窗口到了，检查是否触发熔断
        if (now - windowStart >= WINDOW_MS) {
            double rate = (double) failedRequests.get() / totalRequests.get();
            // 请求数够多 + 失败率够高 → 熔断
            if (totalRequests.get() >= MIN_REQUESTS && rate > FAILURE_RATE_THRESHOLD) {
                state = UpstreamState.DOWN;
                halfOpenSince = now;
                System.out.println("[" + alias + "] 进入 DOWN，失败率=" + String.format("%.0f%%", rate * 100));
            }
            // 开新窗口
            resetWindow();
        }
    }

    /**
     * 清空窗口统计，开新窗口
     */
    private void resetWindow() {
        totalRequests.set(0);
        failedRequests.set(0);
        windowStart = System.currentTimeMillis();
    }

    public UpstreamState getState() {
        return state;
    }

    public String getAlias() {
        return alias;
    }
}
