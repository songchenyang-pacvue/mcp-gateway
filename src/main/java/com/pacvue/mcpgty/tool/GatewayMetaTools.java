package com.pacvue.mcpgty.tool;

import com.pacvue.mcpgty.registry.ToolRegistry;
import com.pacvue.mcpgty.upstream.UpstreamManager;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GatewayMetaTools implements CommandLineRunner {

    private final ToolRegistry registry;
    private final McpSyncServer server;
    private final UpstreamManager upstreamManager;
    private final List<McpSyncClient> mcpClients;

    public GatewayMetaTools(ToolRegistry registry,
                            McpSyncServer server,
                            UpstreamManager upstreamManager,
                            List<McpSyncClient> mcpClients) {
        this.registry = registry;
        this.server = server;
        this.upstreamManager = upstreamManager;
        this.mcpClients = mcpClients;
    }

    @Override
    public void run(String... args) {
        registerListServers();
        registerListTools();
        registerDescribeTool();
        registerInvoke();
    }

    private void registerListServers() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("gw__list_servers")
                .description("列出所有已连接的上游 MCP Server")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();

        McpServerFeatures.SyncToolSpecification spec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    List<Map<String, Object>> result = new ArrayList<>();
                    upstreamManager.all().forEach((alias, breaker) -> {
                        long count = registry.all().stream().filter(r -> r.alias().equals(alias)).count();
                        result.add(Map.of(
                                "alias", alias,
                                "status", breaker.getState().name(),
                                "toolCount", count
                        ));
                    });
                    String json = result.toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(json).build()))
                            .isError(false)
                            .build();
                })
                .build();

        server.addTool(spec);
        System.out.println("已注册元工具: gw__list_servers");
    }

    private void registerListTools() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("gw__list_tools")
                .description("列出网关暴露的所有工具概览（不含完整 schema）")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();

        McpServerFeatures.SyncToolSpecification spec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    List<Map<String, Object>> result = new ArrayList<>();
                    registry.all().forEach(route -> result.add(Map.of(
                            "name", route.exposedName(),
                            "server", route.alias(),
                            "description", route.schema().description()
                    )));
                    String json = result.toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(json).build()))
                            .isError(false)
                            .build();
                })
                .build();

        server.addTool(spec);
        System.out.println("已注册元工具: gw__list_tools");
    }

    private void registerDescribeTool() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("gw__describe_tool")
                .description("查看指定工具的完整 schema（先调 gw__list_tools 搜索，再用这个看详情）")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string", "description", "工具全名，如 everything__echo")),
                        "required", List.of("name")
                ))
                .build();

        McpServerFeatures.SyncToolSpecification spec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String name = (String) request.arguments().get("name");
                    ToolRegistry.Route route = registry.resolve(name);
                    if (route == null) {
                        String errorJson = "{\"errorCode\":\"TOOL_NOT_FOUND\",\"retryable\":false,\"clientHint\":\"重新调 gw__list_tools\"}";
                        return McpSchema.CallToolResult.builder()
                                .content(List.of(McpSchema.TextContent.builder(errorJson).build()))
                                .isError(true)
                                .build();
                    }
                    Map<String, Object> result = Map.of(
                            "name", route.exposedName(),
                            "server", route.alias(),
                            "upstreamTool", route.upstreamToolName()
                    );
                    String json = result.toString();
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder(json).build()))
                            .isError(false)
                            .build();
                })
                .build();

        server.addTool(spec);
        System.out.println("已注册元工具: gw__describe_tool");
    }

    private void registerInvoke() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("gw__invoke")
                .description("逃生通道：绕过命名拼接直接指定上游和工具调用（用于工具名被截断或刚上线未进缓存时）")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "server", Map.of("type", "string", "description", "上游别名，如 everything"),
                                "tool", Map.of("type", "string", "description", "上游工具原名，如 echo"),
                                "arguments", Map.of("type", "object", "description", "调用参数", "additionalProperties", true)
                        ),
                        "required", List.of("server", "tool", "arguments")
                ))
                .build();

        McpServerFeatures.SyncToolSpecification spec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    String server = (String) request.arguments().get("server");
                    String toolName = (String) request.arguments().get("tool");
                    Map<String, Object> arguments = (Map<String, Object>) request.arguments().get("arguments");

                    System.out.println("gw__invoke: server=" + server + ", tool=" + toolName);

                    // 目前只有一个下游，简化处理
                    McpSyncClient client = mcpClients.get(0);
                    McpSchema.CallToolRequest callRequest = new McpSchema.CallToolRequest(toolName, arguments);
                    return client.callTool(callRequest);
                })
                .build();

        server.addTool(spec);
        System.out.println("已注册元工具: gw__invoke");

    }
}
