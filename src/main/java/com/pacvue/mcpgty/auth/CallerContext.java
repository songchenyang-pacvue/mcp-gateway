package com.pacvue.mcpgty.auth;

import java.util.Set;

/**
 * 统一身份上下文
 * 两套实例（内网 ApiKey / 外网 OAuth）最终都解析成这个对象
 * 下游代码只认它，不关心身份来源
 */
public record CallerContext(
        String tenantId,    // 租户标识
        String principal,   // 调用方身份（如 tenant-001 / user-xxx）
        Set<String> scopes  // 权限范围，用于 scope 过滤
) {
}
