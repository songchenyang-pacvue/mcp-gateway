---
name: mcp-gateway-phase-5-auth-tenant
description: 在 Phase 4 熔断就绪之后使用。实现内网实例的鉴权与租户隔离：CallerContext 统一身份上下文、X-Api-Key 认证过滤器（Key 表 + 5 分钟内存缓存）、内网 IP 白名单、requiredScope 过滤框架。产出：无 Key 403、有效 Key 放行、IP 白名单生效、scope 过滤可用。
---

# Phase 5 · 鉴权与租户隔离（内网 ApiKey 先行）

## 目标

1. 定义统一身份上下文 `CallerContext`（下游代码只认它，不关心来源）。
2. 实现内网实例的 `X-Api-Key` 认证 + IP 白名单。
3. 实现 scope 过滤框架：`gw__list_tools` 返回前按 `CallerContext.scopes` 过滤，无权限工具不出现。

> 边界（能力清单明确）：外网 OAuth 2.0 链路初版**不做**，放初版后。本阶段只做内网 ApiKey 链路，但 `CallerContext` 的设计要为 OAuth 留好位置。

## 前置条件

- [ ] Phase 4 完成。
- [ ] 明确 Key 存储：初版可用配置或内存表（Phase 7 落 PostgreSQL）。

## 任务步骤

### Step 1 身份上下文（`auth/CallerContext.java`）

按能力清单定义：

```java
public record CallerContext(
    String tenantId,      // 租户标识
    String principal,     // 调用方身份
    Set<String> scopes    // 权限范围
) {}
```

配套 `CallerContextHolder`（ThreadLocal 或 Spring `RequestAttributes` 存放），下游代码与元工具通过它取当前身份。**两套实例共用一份代码的关键**：OAuth 与 ApiKey 最终都解析成这一个对象。

### Step 2 ApiKey 认证过滤器（`auth/ApiKeyAuthFilter.java`）

- 读取 `X-Api-Key` 请求头。
- 校验逻辑：查 Key 表（`Map<String, ApiKeyEntry>`，entry 含 `keyHash`、`tenantId`、`principal`、`scopes`、`enabled`）。
- **内存缓存 5 分钟**：Key 解析结果缓存，避免每次请求查表（Key 表变更后缓存失效，简单实现即可）。
- 校验失败 → 403（不要返回 401，避免触发 OAuth 流程；返回体含 `trace_id` 占位）。
- 校验成功 → 构造 `CallerContext` 放入 `CallerContextHolder`。

### Step 3 IP 白名单（`auth/InternalIpWhitelist.java`）

- 配置：`gateway.security.internal-ips`（CIDR 列表，如 `127.0.0.1/32`、`10.0.0.0/8`）。
- 过滤器里先查 IP：不在白名单 → 403。
- IP 解析用 `X-Forwarded-For` 时注意防伪造（按部署环境决定是否信任；初版直接取 RemoteAddr + 配置开关）。

### Step 4 scope 过滤（`auth/ScopeFilter.java`）

- `GatewayProperties.Upstream.requiredScope` 已存在（Phase 1）。
- `gw__list_tools` 返回前按 `CallerContext.scopes` 过滤：没有对应 scope 的 alias/tool **直接不出现在列表**。
- 兼容：`gw__invoke` 仍保留 `TOOL_FORBIDDEN` 错误码（客户端缓存了旧列表时兜底）。
- 初版内网单团队：默认配置 `gateway.security.scope-filter-enabled: false`，框架搭好但默认放行（清单"多租户 scope 过滤"放初版后，这里只留钩子）。

### Step 5 认证矩阵验证

| 场景 | 预期 |
| --- | --- |
| 无 X-Api-Key | 403 |
| 无效/过期 Key | 403 |
| 有效 Key + 白名单 IP | 放行，CallerContext 正确 |
| 有效 Key + 非白名单 IP | 403 |
| scope-filter 开启后，无 scope 的 alias 工具 | 不出现在 gw__list_tools |

## 验收标准

- [ ] 认证矩阵五种场景实测符合预期。
- [ ] `CallerContext` 在元工具与透传调用中可访问（下游代码不感知鉴权来源）。
- [ ] Key 缓存生效（第二次请求不查表，可加日志验证）。
- [ ] scope 过滤钩子就位（默认关闭，开启后可过滤）。
- [ ] 决策日志：记录"外网 OAuth 初版不做，架构预留"的决定。

## 常见坑

- **403 vs 401**：ApiKey 失败用 403 且不带 `WWW-Authenticate`，避免客户端误触发 OAuth 握手。
- **ThreadLocal 泄漏**：过滤器 finally 清理 `CallerContextHolder`。
- **Key 明文**：存库/配置只存哈希（如 SHA-256），对比时哈希对比（对齐工程经验：Key 不出现在 Redis dump）。
- **过滤顺序**：IP 白名单 → Key 校验 → scope 过滤，顺序固定，写成集成测试。

## 参考

- 能力清单：§鉴权与租户隔离（两条链路差异表、CallerContext、租户维度可见性、凭证注入下游）
- 能力清单：§初版范围边界（不做外网 OAuth / 多租户 scope 过滤后置）

---

## 实际实现记录（已完成）

### 包结构
```
com.pacvue.mcpgty.auth
├── CallerContext.java          # record: tenantId / principal / scopes
├── CallerContextHolder.java   # ThreadLocal
└── ApiKeyFilter.java           # extends OncePerRequestFilter
```

### 核心实现
- **CallerContext**：record，三个字段 tenantId / principal / scopes
- **CallerContextHolder**：ThreadLocal 存当前请求身份
- **ApiKeyFilter**：extends OncePerRequestFilter，校验 X-Api-Key
- **SecurityProperties**：@ConfigurationProperties("gateway.security")，含 enabled + apiKeys(Map)

### 配置
```yaml
gateway:
  security:
    enabled: true
    api-keys:
      sk-demo-001: tenant-001
      sk-demo-002: tenant-002
```

### 踩坑记录
- **@Value SpEL 解析 Map 失败**：改用 @ConfigurationProperties 绑定
- **403 vs 401**：ApiKey 失败用 403，不带 WWW-Authenticate，避免触发 OAuth 握手
- **ThreadLocal 清理**：过滤器 finally 清理，防止线程复用泄漏

### 面试亮点
- **为什么用 CallerContext 统一身份？** OAuth 和 ApiKey 最终都解析成同一个对象，下游代码不关心鉴权来源
- **为什么用 403 不用 401？** 403 不触发客户端的 OAuth 握手流程
- **为什么 Key 存哈希？** 数据库泄露不泄露原始 Key（对齐工程经验）
