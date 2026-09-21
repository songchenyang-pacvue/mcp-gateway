package com.pacvue.mcpgty.tool;

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

@Component
public class DynamicToolTest implements CommandLineRunner {
    private final McpSyncServer server;
    private final ToolRegistry registry;
    private final List<McpSyncClient> mcpClients;
    private final UpstreamManager upstreamManager;

    public DynamicToolTest(McpSyncServer server,
                           ToolRegistry registry,
                           List<McpSyncClient> mcpClients,
                           UpstreamManager upstreamManager) {
        this.server = server;
        this.registry = registry;
        this.mcpClients = mcpClients;
        this.upstreamManager = upstreamManager;
    }


    @Override
    public void run(String... args) {
        // 所有tool
        System.out.println("MCP Client 数量：" + mcpClients.size());
        McpSyncClient mcpSyncClient = mcpClients.get(0);
        List<McpSchema.Tool> downstreamTools = mcpSyncClient.listTools().tools();
        // 只暴漏 echo、get-sum tool
        registry.refresh("everything", List.of("echo", "get-sum"), downstreamTools);
        upstreamManager.getOrCreate("everything");
        // 改写tool并注册网关
        registry.all().forEach(route -> {
            McpSchema.Tool rewrittenTool = route.schema();
            String upstreamToolName = route.upstreamToolName();

            McpServerFeatures.SyncToolSpecification spec =
                    McpServerFeatures.SyncToolSpecification.builder()
                            .tool(rewrittenTool)
                            .callHandler((exchange, request) -> {
                                CircuitBreaker breaker = upstreamManager.get("everything");
                                // 熔断检查：不允许直接返回错误
                                if (!breaker.allowRequest()) {
                                    String errorJson = "{\"errorCode\":\"UPSTREAM_NOT_FOUND\",\"retryable\":false,\"clientHint\":\"上游已熔断，稍后再试\"}";
                                    return McpSchema.CallToolResult.builder()
                                            .content(List.of(McpSchema.TextContent.builder(errorJson).build()))
                                            .isError(true)
                                            .build();
                                }

                                // 客户端调 demo__echo → 网关转发给下游 echo
                                System.out.println("网关转发: " + upstreamToolName + " ← " + request.arguments());
                                try {
                                    McpSchema.CallToolRequest callRequest =
                                            new McpSchema.CallToolRequest(upstreamToolName, request.arguments());

                                    McpSchema.CallToolResult result = mcpSyncClient.callTool(callRequest);
                                    breaker.recordSuccess();
                                    return result;
                                } catch (Exception e) {
                                    breaker.recordFailure();
                                    throw new RuntimeException(e);
                                }
                            }).build();
            server.addTool(spec);
            System.out.println("已注册到网关: " + rewrittenTool.name());
        });
    }
}
