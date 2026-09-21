package com.pacvue.mcpgty.registry;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

@Component
public class SchemRewriter {

    /**
     * 把下游工具改写对外暴漏
     * @param alias
     * @param upstreamTool
     * @return
     */
    public McpSchema.Tool rewrite(String alias, McpSchema.Tool upstreamTool) {
        String exposedName = alias + "__" + upstreamTool.name();
        String exposedDesc = upstreamTool.description() + " [via " + alias + "]";

        return McpSchema.Tool.builder()
                .name(exposedName)
                .description(exposedDesc)
                .inputSchema(upstreamTool.inputSchema()) // 原样
                .build();
    }
}
