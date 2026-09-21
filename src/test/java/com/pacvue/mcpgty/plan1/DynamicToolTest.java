package com.pacvue.mcpgty.plan1;

import com.pacvue.mcpgty.registry.ToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态工具注入集成测试：验证运行期往 McpSyncServer 加工具后 tools/list 能看到。
 * 下游真实调用（McpSyncClient）需要已启动的上游 server，留到 Phase 2 用 mock 上游覆盖。
 */
@SpringBootTest
class DynamicToolTest {

    private static final String DYNAMIC_TOOL_NAME = "greeting";

    @Autowired
    private McpSyncServer server;

    @Autowired
    private ToolRegistry registry;

    @AfterEach
    void removeDynamicTool() {
        if (containsTool(DYNAMIC_TOOL_NAME)) {
            server.removeTool(DYNAMIC_TOOL_NAME);
        }
    }

    @Test
    @DisplayName("运行期 addTool 后工具出现在 tools/list，removeTool 后消失")
    void dynamicallyAddedToolShowsUpInToolsList() {
        assertThat(containsTool(DYNAMIC_TOOL_NAME)).isFalse();

        server.addTool(greetingSpec());

        assertThat(server.listTools())
                .filteredOn(t -> DYNAMIC_TOOL_NAME.equals(t.name()))
                .singleElement()
                .satisfies(t -> assertThat(t.description()).isEqualTo("动态注入的问候工具"));

        server.removeTool(DYNAMIC_TOOL_NAME);

        assertThat(containsTool(DYNAMIC_TOOL_NAME)).isFalse();
    }

    @Test
    @DisplayName("容器里的 ToolRegistry 已注入 SchemRewriter，白名单工具按命名空间注册")
    void springManagedRegistryRoutesWhitelistedTools() {
        registry.refresh("ads", List.of("query_campaign", "list_campaigns"), List.of(
                upstreamTool("query_campaign", "查询广告活动"),
                upstreamTool("list_campaigns", "列出所有活动"),
                upstreamTool("internal_debug", "内部工具，不在白名单")));

        ToolRegistry.Route route = registry.resolve("ads__query_campaign");

        assertThat(route).isNotNull();
        assertThat(route.upstreamToolName()).isEqualTo("query_campaign");
        assertThat(route.schema().description()).isEqualTo("查询广告活动 [via ads]");
        assertThat(registry.resolve("ads__internal_debug")).isNull();
    }

    private boolean containsTool(String name) {
        return server.listTools().stream().anyMatch(t -> name.equals(t.name()));
    }

    private static McpServerFeatures.SyncToolSpecification greetingSpec() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name(DYNAMIC_TOOL_NAME)
                        .description("动态注入的问候工具")
                        .inputSchema(Map.of("type", "object", "properties", Map.of()))
                        .build())
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                        .content(List.of(McpSchema.TextContent.builder("Hello from dynamic tool!").build()))
                        .build())
                .build();
    }

    private static McpSchema.Tool upstreamTool(String name, String description) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();
    }
}
