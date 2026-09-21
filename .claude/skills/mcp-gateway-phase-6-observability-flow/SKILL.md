---
name: mcp-gateway-phase-6-observability-flow
description: 在 Phase 5 鉴权就绪之后使用。实现可观测性与流控：三个自定义 Micrometer 指标、两层 span 与 traceparent 透传、令牌桶限流（60 次/分钟/租户）、单下游并发限制（20）、响应体截断（1MB）、日志脱敏（不记录 arguments 内容）。产出：指标可查、限流触发、截断标记、日志无敏感参数。
---

# Phase 6 · 可观测与流控

## 目标

1. 三个自定义 Micrometer 指标（工具调用 / 下游延迟 / 熔断档位）。
2. 调用链：网关入口一层 span + 转发下游一层 span，traceId 经 `traceparent` 透传。
3. 流控：令牌桶 60/min/租户、单下游并发 20、响应体 1MB 截断。
4. 日志脱敏：不记录 `arguments` 完整内容（AI 参数可能含用户原始提问）。

## 前置条件

- [ ] Phase 5 完成：CallerContext 可用（限流按租户维度需要它）。
- [ ] Spring AI 2.0 自带 Micrometer 埋点已确认启用（见参考）。

## 任务步骤

### Step 1 自定义指标（`observability/GatewayMetrics.java`）

按清单"可观测性与流控"表实现，注入 `MeterRegistry`：

| 指标 | 类型 | 标签 | 语义 |
| --- | --- | --- | --- |
| `gateway.tool.calls` | Counter | `alias`、`tool`、`outcome` | 工具真实使用量 |
| `gateway.upstream.latency` | Timer | `alias` | 定位慢下游 |
| `gateway.upstream.state` | Gauge | `alias` | 熔断当前档位 |

- `outcome` 只取三个值：`success` / `client_error` / `upstream_error`。**不把具体错误码当标签**（避免基数爆炸）。
- 埋点位置：转发调用前后（成功/失败分支）、熔断状态变更时更新 Gauge。
- 验证：加 `spring-boot-starter-actuator` 暴露 `/actuator/prometheus`（或 `/actuator/metrics`）查看。

### Step 2 两层 span 与 traceparent 透传

- 网关入口 span：在一次 MCP 请求入口创建（名字如 `gateway.tool.call`），标签 alias/tool。
- 转发 span：调下游前创建子 span（`gateway.upstream.call`）。
- 透传：调用下游 MCP 时把当前 trace 写入 **W3C `traceparent` 头**，让下游日志能串在同一条链上（mock 下游若支持可打印收到的 traceparent 验证）。
- 实现方式：优先用 Micrometer Tracing（`spring-boot-starter-actuator` + tracing bridge）的自动传播；若 Spring AI MCP Client 已自带，确认默认行为即可。

### Step 3 日志脱敏

- 网关日志**不记录 `arguments` 完整内容**。
- 统一脱敏工具：记录字段名 + 值长度（如 `args: {keyword: len=12, region: len=2}`），或截断前 32 字符。
- 排查已存在的 `toString()` 全量打印，统一替换。

### Step 4 令牌桶限流（`flow/RateLimiter.java`）

按清单默认参数：**60 次/分钟/租户**，超出返回限流错误（错误码沿用 Phase 3 体系，如 `INVALID_ARGUMENTS` 外新增 `RATE_LIMITED` 或复用上游错误码，写进决策日志）。

- 实现：手写令牌桶（`AtomicLong` + 时间戳补 token）即可讲清原理；或引入 Bucket4j。
- 维度：按 `CallerContext.tenantId`（未认证请求在 Phase 5 已被 403，限流只发生在已认证路径）。
- 仅对 `tools/call` 计量（协议握手方法不计，避免会话握手耗尽配额）。
- 错误响应携带重试提示（`retry_after_seconds`）。

### Step 5 单下游并发限制（`flow/UpstreamConcurrencyLimiter.java`）

- 每 alias 并发上限 **20**：用 `Semaphore(20)` 按 alias 隔离。
- 超限行为：快速失败（返回可重试错误）或排队等待（选一，写决策日志；清单意图是"隔离慢下游拖垂整体"，快速失败更符合）。
- 与熔断的交互：并发拒绝计入失败统计吗？——不计入熔断（是网关自身保护，非下游故障），写清楚。

### Step 6 响应体截断（`flow/ResponseTruncator.java`）

- 下游响应上限 **1MB**（可按 alias 覆写）。
- 超限：截断 + **在响应里明确标记被截断**（如 `_meta.truncated: true` + `_meta.original_size`），并提示改用分页参数。
- 落审计时记录原始大小（审计在 Phase 7 落库）。

### Step 7 验收验证

1. 发起一批调用 → `/actuator/metrics/gateway.tool.calls` 计数增长，`outcome` 分类正确。
2. 临时把限流阈值调成 2/min 触发限流，观察错误响应与 `retry_after_seconds`。
3. 用超大响应 mock 工具触发截断，确认 `_meta.truncated` 标记。
4. mock 下游打印收到的 `traceparent`，与网关日志 traceId 同链。
5. 检查日志：无 `arguments` 全量内容。

## 验收标准

- [ ] 三个指标可在 actuator 查到，标签符合定义。
- [ ] 限流触发正确，错误响应带重试提示。
- [ ] 并发限制生效（可压测验证 20 上限）。
- [ ] 截断响应带明确标记。
- [ ] 日志无 arguments 内容（可 grep 抽查）。
- [ ] traceparent 透传验证通过。

## 常见坑

- **指标基数**：outcome 只放三值，别把错误码/别名全塞进标签——别名本身是合理基数，错误码不是。
- **限流与重试**：AI 客户端遇限流可能自动重试，令牌桶窗口与错误提示要能配合（给 retry_after）。
- **并发限制与熔断口径**：两个保护机制口径要写清楚（并发拒绝 ≠ 下游故障），否则排查时互相误导。
- **actuator 暴露**：生产环境按需暴露，别把全部端点开给公网。

## 参考

- 能力清单：§可观测性与流控（指标表、调用链、日志脱敏）
- 能力清单：§默认参数（限流/并发/响应体上限/重试）
