package com.pacvue.mcpgty.registry;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由表单元测试：不启动 Spring，直接 new 出协作对象，毫秒级跑完。
 */
class ToolRegistryTest {

    private static final List<String> ADS_WHITELIST = List.of("query_campaign", "list_campaigns");

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(new SchemRewriter());
    }

    @Test
    @DisplayName("只有白名单内的工具进路由表，对外名加 alias__ 前缀")
    void refreshRegistersOnlyWhitelistedTools() {
        registry.refresh("ads", ADS_WHITELIST, List.of(
                tool("query_campaign", "查询广告活动"),
                tool("list_campaigns", "列出所有活动"),
                tool("internal_debug", "内部工具，不在白名单")));

        assertThat(registry.all())
                .extracting(ToolRegistry.Route::exposedName)
                .containsExactlyInAnyOrder("ads__query_campaign", "ads__list_campaigns");
    }

    @Test
    @DisplayName("路由保留下游原始工具名，schema 被改写成对外名并标注来源")
    void refreshRewritesSchemaButKeepsUpstreamName() {
        Map<String, Object> inputSchema = Map.of("type", "object", "properties", Map.of());
        registry.refresh("ads", ADS_WHITELIST, List.of(
                McpSchema.Tool.builder()
                        .name("query_campaign")
                        .description("查询广告活动")
                        .inputSchema(inputSchema)
                        .build()));

        ToolRegistry.Route route = registry.resolve("ads__query_campaign");

        assertThat(route).isNotNull();
        assertThat(route.alias()).isEqualTo("ads");
        assertThat(route.upstreamToolName()).isEqualTo("query_campaign");
        assertThat(route.schema().name()).isEqualTo("ads__query_campaign");
        assertThat(route.schema().description()).isEqualTo("查询广告活动 [via ads]");
        assertThat(route.schema().inputSchema()).isNotNull();
    }

    @Test
    @DisplayName("resolve 未注册的对外名返回 null")
    void resolveReturnsNullForUnknownName() {
        registry.refresh("ads", ADS_WHITELIST, List.of(tool("query_campaign", "查询广告活动")));

        assertThat(registry.resolve("ads__internal_debug")).isNull();
        assertThat(registry.resolve("query_campaign")).isNull();
    }

    @Test
    @DisplayName("重复 refresh 同一 alias 会清掉上一轮的残留路由")
    void refreshClearsStaleRoutesOfSameAlias() {
        registry.refresh("ads", ADS_WHITELIST, List.of(
                tool("query_campaign", "查询广告活动"),
                tool("list_campaigns", "列出所有活动")));

        registry.refresh("ads", ADS_WHITELIST, List.of(tool("query_campaign", "查询广告活动")));

        assertThat(registry.all())
                .extracting(ToolRegistry.Route::exposedName)
                .containsExactly("ads__query_campaign");
    }

    @Test
    @DisplayName("刷新一个上游不影响其他上游的路由")
    void refreshOfOneAliasDoesNotAffectOthers() {
        registry.refresh("ads", ADS_WHITELIST, List.of(tool("query_campaign", "查询广告活动")));
        registry.refresh("report", List.of("export_report"), List.of(tool("export_report", "导出报表")));

        registry.refresh("ads", ADS_WHITELIST, List.of(tool("list_campaigns", "列出所有活动")));

        assertThat(registry.all())
                .extracting(ToolRegistry.Route::exposedName)
                .containsExactlyInAnyOrder("ads__list_campaigns", "report__export_report");
    }

    private static McpSchema.Tool tool(String name, String description) {
        return McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .build();
    }
}
