package com.pacvue.mcpgty.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("gateway")
public record GatewayProperties(List<Upstream> upstreams) {
    public record Upstream(
            String alias,
            String url,
            String transport,
            Duration timeout,
            String requiredScope,
            ToolFilter tools,
            boolean enabled,
            Map<String, Object> auth
    ){}

    public record ToolFilter(List<String> include) {}
}
