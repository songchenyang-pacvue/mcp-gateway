package com.pacvue.mcpgty.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关自定义指标
 * - gateway.tool.calls：工具调用计数（按 alias/tool/outcome）
 * - gateway.upstream.latency：下游调用耗时（按 alias）
 * - gateway.upstream.state：熔断状态 Gauge（UP=0, DEGRADED=1, DOWN=2）
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> stateGauges = new ConcurrentHashMap<>();

    public GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录工具调用
     * @param alias 下游别名
     * @param tool 工具名
     * @param outcome success / client_error / upstream_error
     */
    public void recordToolCall(String alias, String tool, String outcome) {
        Counter.builder("gateway.tool.calls")
                .tag("alias", alias)
                .tag("tool", tool)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    /**
     * 记录下游调用耗时
     */
    public void recordUpstreamLatency(String alias, long durationMs) {
        Timer.builder("gateway.upstream.latency")
                .tag("alias", alias)
                .register(registry)
                .record(java.time.Duration.ofMillis(durationMs));
    }

    /**
     * 更新熔断状态 Gauge
     * @param stateOrdinal UP=0, DEGRADED=1, DOWN=2
     */
    public void updateUpstreamState(String alias, int stateOrdinal) {
        AtomicInteger gauge = stateGauges.computeIfAbsent(alias, k -> {
            AtomicInteger value = new AtomicInteger(0);
            Gauge.builder("gateway.upstream.state", value, AtomicInteger::get)
                    .tag("alias", k)
                    .register(registry);
            return value;
        });
        gauge.set(stateOrdinal);
    }
}
