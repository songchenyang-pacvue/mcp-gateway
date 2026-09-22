package com.pacvue.mcpgty.tool;

import com.pacvue.mcpgty.auth.CallerContextHolder;
import com.pacvue.mcpgty.flow.RateLimiter;
import com.pacvue.mcpgty.observability.GatewayMetrics;
import com.pacvue.mcpgty.repository.AuditRecord;
import com.pacvue.mcpgty.repository.AuditRecordRepository;
import com.pacvue.mcpgty.registry.ToolRegistry;
import com.pacvue.mcpgty.upstream.CircuitBreaker;
import com.pacvue.mcpgty.upstream.UpstreamManager;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class DynamicToolTest implements CommandLineRunner {
    private final McpSyncServer server;
    private final ToolRegistry registry;
    private final List<McpSyncClient> mcpClients;
    private final UpstreamManager upstreamManager;
    private final GatewayMetrics metrics;
    private final RateLimiter rateLimiter;
    private final AuditRecordRepository auditRepository;

    public DynamicToolTest(McpSyncServer server,
                           ToolRegistry registry,
                           List<McpSyncClient> mcpClients,
                           UpstreamManager upstreamManager,
                           GatewayMetrics metrics,
                           RateLimiter rateLimiter,
                           AuditRecordRepository auditRepository) {
        this.server = server;
        this.registry = registry;
        this.mcpClients = mcpClients;
        this.upstreamManager = upstreamManager;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.auditRepository = auditRepository;
    }


    @Override
    public void run(String... args) {
        System.out.println("MCP Client 数量：" + mcpClients.size());
        McpSyncClient mcpSyncClient = mcpClients.get(0);
        List<McpSchema.Tool> downstreamTools = mcpSyncClient.listTools().tools();
        registry.refresh("everything", List.of("echo", "get-sum"), downstreamTools);
        upstreamManager.getOrCreate("everything");
        registry.all().forEach(route -> {
            McpSchema.Tool rewrittenTool = route.schema();
            String upstreamToolName = route.upstreamToolName();

            McpServerFeatures.SyncToolSpecification spec =
                    McpServerFeatures.SyncToolSpecification.builder()
                            .tool(rewrittenTool)
                            .callHandler((exchange, request) -> {
                                String tenantId = CallerContextHolder.get() != null
                                        ? CallerContextHolder.get().tenantId()
                                        : "unknown";
                                String traceId = UUID.randomUUID().toString();

                                // 1. 限流检查
                                if (!rateLimiter.tryAcquire(tenantId)) {
                                    String errorJson = "{\"errorCode\":\"RATE_LIMITED\",\"retryable\":true,\"clientHint\":\"请求过于频繁，请稍后重试\"}";
                                    metrics.recordToolCall("everything", upstreamToolName, "client_error");
                                    saveAudit(traceId, tenantId, upstreamToolName, "client_error", 0, "rate_limited", null);
                                    return McpSchema.CallToolResult.builder()
                                            .content(List.of(McpSchema.TextContent.builder(errorJson).build()))
                                            .isError(true)
                                            .build();
                                }

                                // 2. 熔断检查
                                CircuitBreaker breaker = upstreamManager.get("everything");
                                if (!breaker.allowRequest()) {
                                    String errorJson = "{\"errorCode\":\"UPSTREAM_NOT_FOUND\",\"retryable\":false,\"clientHint\":\"上游已熔断，稍后再试\"}";
                                    metrics.recordToolCall("everything", upstreamToolName, "upstream_error");
                                    saveAudit(traceId, tenantId, upstreamToolName, "upstream_error", 0, "circuit_open", null);
                                    return McpSchema.CallToolResult.builder()
                                            .content(List.of(McpSchema.TextContent.builder(errorJson).build()))
                                            .isError(true)
                                            .build();
                                }

                                // 3. 转发
                                long start = System.currentTimeMillis();
                                System.out.println("网关转发: " + upstreamToolName + " (tenant=" + tenantId + ", trace=" + traceId.substring(0, 8) + ")");
                                try {
                                    McpSchema.CallToolRequest callRequest =
                                            new McpSchema.CallToolRequest(upstreamToolName, request.arguments());
                                    McpSchema.CallToolResult result = mcpSyncClient.callTool(callRequest);
                                    breaker.recordSuccess();
                                    long latency = System.currentTimeMillis() - start;
                                    metrics.recordToolCall("everything", upstreamToolName, "success");
                                    metrics.recordUpstreamLatency("everything", latency);
                                    metrics.updateUpstreamState("everything", 0);
                                    saveAudit(traceId, tenantId, upstreamToolName, "success", latency,
                                            "fields=" + request.arguments().size(), null);
                                    return result;
                                } catch (Exception e) {
                                    breaker.recordFailure();
                                    long latency = System.currentTimeMillis() - start;
                                    metrics.recordToolCall("everything", upstreamToolName, "upstream_error");
                                    metrics.updateUpstreamState("everything", breaker.getState().ordinal());
                                    saveAudit(traceId, tenantId, upstreamToolName, "upstream_error", latency,
                                            "call_failed", "UPSTREAM_ERROR");
                                    throw new RuntimeException(e);
                                }
                            }).build();
            server.addTool(spec);
            System.out.println("已注册到网关: " + rewrittenTool.name());
        });
    }

    private void saveAudit(String traceId, String tenantId, String tool, String outcome,
                           long durationMs, String requestSummary, String errorCode) {
        try {
            auditRepository.save(new AuditRecord(traceId, tenantId, "everything", tool,
                    outcome, durationMs, requestSummary, errorCode));
        } catch (Exception e) {
            System.out.println("审计写入失败（不影响主流程）: " + e.getMessage());
        }
    }
}
