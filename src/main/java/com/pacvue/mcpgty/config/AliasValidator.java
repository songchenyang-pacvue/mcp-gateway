package com.pacvue.mcpgty.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AliasValidator {
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{1,15}$");

    private final GatewayProperties properties;

    public AliasValidator(GatewayProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        Set<String> seen = new HashSet<>();

        for (GatewayProperties.Upstream upstream : properties.upstreams()) {
            String alias = upstream.alias();

            // 规则1：格式校验（小写字母开头，字母数字下划线，2-16 位）
            if (!ALIAS_PATTERN.matcher(alias).matches()) {
                throw new IllegalStateException(
                        "非法 alias: '" + alias + "'，规则：小写字母开头，仅含小写字母/数字/下划线，长度 2-16");
            }

            // 规则2：不能包含双下划线（命名空间分隔符）
            if (alias.contains("__")) {
                throw new IllegalStateException(
                        "alias '" + alias + "' 不能包含 '__'（双下划线是命名空间分隔符）");
            }

            // 规则3：重复别名 → 启动失败
            if (!seen.add(alias)) {
                throw new IllegalStateException(
                        "alias 重复: '" + alias + "'，两个上游不能用同一个别名");
            }
        }

        System.out.println("=== Alias 校验通过，共 " + seen.size() + " 个上游 ===");
    }

}
