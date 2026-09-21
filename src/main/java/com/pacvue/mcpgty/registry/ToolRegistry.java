package com.pacvue.mcpgty.registry;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ToolRegistry {

    private final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();



    public record Route(
            String exposedName,      // 对外全名，如 ads__query_campaign
            String alias,            // 上游别名，如 ads
            String upstreamToolName, // 下游原始工具名，如 query_campaign
            McpSchema.Tool schema   // 改写后的工具 schema（Step 4 完善）
    ) {}

    private final SchemRewriter rewriter;

    public ToolRegistry(SchemRewriter rewriter) {
        this.rewriter = rewriter;
    }
    /**
     * @description 刷新某个上游的工具路由
     * @param alias
     * @param whitelist 白名单
     * @param upstreamTools 注入工具名单
     */
    public void refresh(String alias, List<String> whitelist, List<McpSchema.Tool> upstreamTools) {
        // 1. 先清掉这个 alias 的旧路由（避免上次残留）
        routes.entrySet().removeIf(e -> e.getValue().alias().equals(alias));

        // 2. 按白名单过滤
        for (McpSchema.Tool tool : upstreamTools) {
            if (!whitelist.contains(tool.name())) {
                continue; // 不在白名单，不透传
            }

            // 3. 对外名 = alias__toolName（Step 4 再做 schema 重写）
            String exposedName = alias + "__" + tool.name();
//            routes.put(exposedName, new Route(exposedName, alias, tool.name(), tool));
            McpSchema.Tool rewritten = rewriter.rewrite(alias, tool);
            routes.put(exposedName, new Route(exposedName, alias, tool.name(), rewritten));
        }
    }

    /**
     * 根据对外名解析路由（Phase 2 转发前调）
     */
    public Route resolve(String exposedName) {
        return routes.get(exposedName);
    }

    /**
     * 列出全部路由（供 gw__list_tools 用）
     */
    public List<Route> all() {
        return List.copyOf(routes.values());
    }
}
