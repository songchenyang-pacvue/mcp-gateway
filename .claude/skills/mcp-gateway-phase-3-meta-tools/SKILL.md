---
name: mcp-gateway-phase-3-meta-tools
description: 在 Phase 2 透传链路跑通之后使用。实现网关自有元工具层：gw__list_servers、gw__list_tools、gw__describe_tool、gw__invoke 四个 @McpTool，以及统一错误码体系（区分可重试与不可重试）。产出：四个元工具逐一 curl 验证通过，错误场景返回正确错误码。
---

# Phase 3 · 网关元工具与错误码体系

## 目标

1. 实现四个 `gw__` 元工具：发现能力（list_servers / list_tools / describe_tool）+ 逃生通道（invoke）。
2. 建立统一错误码体系（六码），错误响应放 MCP 错误 `data.code`。
3. 明确区分"可重试"与"不可重试"错误——这是 AI 客户端行为的关键约定。

## 前置条件

- [ ] Phase 2 完成：透传工具可调用，UpstreamClientPool 提供 `all()` / `get()` / 状态。
- [ ] ToolRegistry 提供 `search(server, keyword)` 与 `describe(name)` 能力（Phase 1 已建，本阶段补齐缺的方法）。

## 任务步骤

### Step 1 错误码枚举（`errors/GatewayErrorCode.java`）

按能力清单"错误码"表定义枚举，含 `code`、`retryable`、`clientHint` 三要素：

| code | retryable | 客户端提示 |
| --- | --- | --- |
| `UPSTREAM_NOT_FOUND` | false | 重新调 `gw__list_servers` |
| `TOOL_NOT_FOUND` | false | 重新调 `gw__list_tools` |
| `UPSTREAM_TIMEOUT` | true | 可重试一次，再失败放弃 |
| `UPSTREAM_UNAUTHORIZED` | false | 不要重试，提示人工处理 |
| `TOOL_FORBIDDEN` | false | 不要重试 |
| `INVALID_ARGUMENTS` | true | 按报错修正后重试 |

配套 `GatewayException extends RuntimeException`，携带 `GatewayErrorCode`，在全局异常处理里转成 MCP 错误信封（`data.code` + `data.trace_id` 占位，trace 在 Phase 6 接入）。

### Step 2 元工具服务（`tools/GatewayMetaTools.java`）

按能力清单"骨架代码·元工具实现"，四个 `@McpTool`：

1. **`gw__list_servers`**（无参）：返回数组，每项含 `alias`、`status`（UP/DEGRADED/DOWN，Phase 4 前先返回 UP/UNKNOWN）、`toolCount`、`lastProbeAt`。数据来自 `pool.all()` + `registry.countOf(alias)`。
2. **`gw__list_tools`**（可选参 `server`、`keyword`）：返回每项只带 `name`、`server`、`description` 三个字段，**不带完整 schema**（避免吃下游窗口，清单明确的取舍）。按 `CallerContext.scopes` 过滤（Phase 5 实现过滤，本阶段先透传全部）。
3. **`gw__describe_tool`**（必填 `name`，带命名空间全名）：返回完整 `inputSchema`、`outputSchema` 与示例。配合 list_tools 构成"先搜后查"。
4. **`gw__invoke`**（必填 `server`、`tool`、`arguments`）：绕过名称拼接直接指定路由。用于：tool 名被截断过、tool 刚上线未进客户端缓存。**逃生通道，不是主路径**。

### Step 3 元工具的错误处理

- `pool.get(server)` 不存在 → `UPSTREAM_NOT_FOUND`。
- `registry` 查不到 tool → `TOOL_NOT_FOUND`。
- `arguments` 缺必填字段 → `INVALID_ARGUMENTS`。
- 错误消息与"工具不存在"尽量同形状，不泄露内部细节（对齐 .NET 工程的经验）。

### Step 4 全局异常处理（`errors/GatewayExceptionHandler.java`）

- 拦截 `GatewayException` → 转 MCP JSON-RPC 错误响应（HTTP 200 + `error.code` + `error.data.code`）。
- 拦截未知异常 → 统一 `INTERNAL_ERROR`（Phase 7 错误归一化可再细化）。
- 注意：`@McpTool` 抛异常时 Spring AI 有自己的包装行为，确认异常能透出 `data.code`；若被吞，改用 `CallToolResult.isError(true)` 返回（参考 Spring AI MCP 文档的错误处理）。

### Step 5 验收验证

逐一 curl 验证：

- `gw__list_servers`：返回 mock 上游列表与状态。
- `gw__list_tools`：返回不带 schema 的工具概览；`server=ads` 过滤生效；`keyword` 匹配生效。
- `gw__describe_tool`：传 `ads__query_campaign` 返回完整 schema。
- `gw__invoke`：显式指定 `server=ads, tool=query_campaign` 调用成功。
- 错误场景：不存在 server → `UPSTREAM_NOT_FOUND`；错误 tool → `TOOL_NOT_FOUND`；缺参 → `INVALID_ARGUMENTS`。

## 验收标准

- [ ] 四个元工具各自 curl 通过，返回结构符合清单字段定义。
- [ ] 六个错误码枚举定义完整，至少三个错误场景实测返回正确 `data.code`。
- [ ] `gw__list_tools` 不带完整 schema（清单取舍生效）。
- [ ] 错误响应不含堆栈/内部信息。

## 常见坑

- **`@McpTool` 方法签名**：入参命名要与客户端传参一致；可选参数用 `@McpToolParam(required = false)`。
- **返回类型**：返回 record/List 时 Spring AI 自动生成 schema；返回 `Map` 时 schema 是自由对象，注意描述清楚。
- **异常被 SDK 吞**：先验证 `GatewayException` 是否能透出 code；不能就改用 `isError(true)` 返回结构。

## 参考

- 能力清单：§网关自有 Tool 清单、§错误码
- 能力清单：§骨架代码（GatewayMetaTools）

---

## 实际实现记录（已完成）

### 关键决策：不用 @McpTool 注解，改用编程式注册
**踩坑**：GatewayMetaTools 用 `@Component + @McpTool` 注解方式，类被创建了（构造函数打印确认），但 `Registered tools: 1` 始终不增加——注解扫描器没认它。原因是 GatewayMetaTools 依赖 ToolRegistry，在扫描器就绪前就被创建了，导致 BeanPostProcessor 没处理它。

**解决方案**：改成 `CommandLineRunner`，在 `run()` 里用 `server.addTool(SyncToolSpecification)` 手动注册，和 Phase 2 动态注册下游工具同一套方式。

### 四个元工具实现
| 元工具 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `gw__list_servers` | 无 | 上游列表（alias/status/toolCount） | 从 registry.all() 聚合 |
| `gw__list_tools` | 无 | 工具概览（name/server/description） | 不带 schema，省 token |
| `gw__describe_tool` | name（必填） | 完整 schema | 先 list 再 describe |
| `gw__invoke` | server/tool/arguments | 直接调下游 | 逃生通道，绕过命名拼接 |

### 错误处理
- **不抛异常**，直接返回 `CallToolResult.builder().content(...).isError(true).build()`。
- content 里放结构化 JSON：`{"errorCode":"TOOL_NOT_FOUND","retryable":false,"clientHint":"..."}`
- 避免泄露内部异常类名（GatewayException 等）。

### 错误码枚举（errors/GatewayErrorCode.java）
```java
UPSTREAM_NOT_FOUND(false, "重新调 gw__list_servers")
TOOL_NOT_FOUND(false, "重新调 gw__list_tools")
UPSTREAM_TIMEOUT(true, "可重试一次")
UPSTREAM_UNAUTHORIZED(false, "提示人工处理")
TOOL_FORBIDDEN(false, "不要重试")
INVALID_ARGUMENTS(true, "按报错修正后重试")
```

### 踩坑记录
- **@McpTool 注解扫描器时机**：依赖其他 bean 的 @Component 类，可能在扫描器就绪前就被创建，导致 @McpTool 不生效。解决方案：改用编程式 `server.addTool()`。
- **CallToolResult 构造函数**：Spring AI 2.0 需要 4 个参数，用 builder 链式调用。
- **Map.of() 不允许 null**：构造返回值时确保没有 null 值。
- **inputSchema 构造**：手动构造 Map 时注意 required 字段是 `List<String>` 不是 `String`。

### 面试亮点
- **为什么要有元工具？** 大模型不用一次性吞所有工具的 schema（占 context 窗口），先 `gw__list_tools` 搜索，再 `gw__describe_tool` 看详情——按需加载。
- **为什么错误要结构化？** 区分可重试（超时、参数错误）和不可重试（工具不存在、未授权），大模型知道要不要重试。
- **为什么不抛异常？** 抛异常会泄露内部类名和堆栈，返回 `isError=true` + 结构化错误码更安全。
- **为什么有 `gw__invoke` 逃生通道？** 工具名被截断或刚上线未进客户端缓存时，直接指定 server + tool 调用。
