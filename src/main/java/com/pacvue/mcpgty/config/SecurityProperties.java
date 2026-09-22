package com.pacvue.mcpgty.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@ConfigurationProperties("gateway.security")
public record SecurityProperties(
        boolean enabled,
        Map<String, String> apiKeys
) {
    public SecurityProperties {
        if (apiKeys == null) apiKeys = new HashMap<>();
    }
}
