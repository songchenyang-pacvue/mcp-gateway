---
name: mcp-gateway-phase-2-mcp-client
description: 在 Phase 1 路由表就绪之后使用。实现网关的 MCP Client 角色：UpstreamClientPool 管理下游 MCP Server 连接与生命周期、透传工具向 Spring AI MCP Server 的动态注册（proxiedTools）、首次真实透传调用。产出：客户端能看到 ads__xxx 透传工具并能真实调用下游。
---

# Phase 2 · MCP Client 接入与首次透传

## 目标

1. 网关以 MCP Client 身份连接下游 MCP Server（本地 mock 先行）。
2. 透传工具注册进 MCP Server 的对外工具列表（`proxiedTools`）。
3. 跑通"上游客户端 → 网关 → 下游 MCP Server"的首次真实调用。

## 前置条件

- [ ] Phase 1 完成：GatewayProperties + ToolRegistry + schema 改写可用。
- [ ] Phase 0 的动态增删结论已记录——**本阶段的刷新机制按该结论实现**（支持→动态刷新 + list_changed；不支持→启动时同步一次）。
- [ ] 本地 mock 下游 MCP Server 可用（独立工程，端口如 8081，提供 2–3 个简单工具）。

## 任务步骤

### Step 1 搭建 mock 下游（独立最小工程）

在项目外新建 `mcp-mock-downstream` 最小工程（同一套 Spring AI 依赖）：

- 一个 MCP Server 端点（如 8081 端口 `/mcp`）。
- 2–3 个 `@McpTool`：如 `query_campaign`（返回静态数据）、`list_campaigns`、`export_daily`。
- 其中一个工具故意含 `__`（如 `health__check`）用于验证命名空间切分规则。
- 启动后 curl 验证 tools/list 正常。

这个 mock 同时是你面试的素材："我写过 MCP Server 端，也写过 MCP Client 端。"

### Step 2 更新配置指向 mock

`application.yaml` 的 `gateway.upstreams` 把占位 URL 换成 mock 地址：

```yaml
gateway:
  upstreams:
    - alias: ads
      url: http://localhost:8081/mcp
      transport: streamable-http
      auth: { type: none }
      timeout: 10s
      enabled: true
      tools:
        include: [query_campaign, list_campaigns]
```

### Step 3 下游连接池（`upstream/UpstreamClientPool.java`）

按能力清单"骨架代码"实现：

- 启动时为每个 `enabled` 的 upstream 建立 MCP Client（Spring AI MCP Client API，`McpClient`/starter 自动装配，以官方 2.0 文档为准）。
- 池提供：`get(alias)` 取连接、`all()` 枚举、`close()` 关闭全部。
- 记录每个上游的 `lastProbeAt` 与连接状态（供 Phase 4 状态机使用）。
- 凭证注入：按配置 `auth` 节设置下游请求头（`X-Api-Key` 等），不透传上游凭证。
- 超时：连接级超时按配置的 `timeout`（默认 10s）。

### Step 4 透传工具注册（`config/ProxiedToolsConfig.java`）

按能力清单"骨架代码·透传部分"实现 `@Bean List<SyncToolSpecification> proxiedTools(...)`：

- 遍历 `registry.allRoutes()`，为每个路由生成一个 `SyncToolSpecification`。
- handler 里调用 `pool.get(route.alias()).callTool(route.upstreamToolName(), args)`。
- **关键**：调用时按 `{alias}__{toolName}` 的第一个 `__` 切分，前半查别名表，后半原样透传（下游 tool 名自身含 `__` 不受影响）。
- 刷新机制按 Phase 0 结论实现（支持动态 → 提供 refresh 入口并考虑 `notifications/tools/list_changed` 订阅；不支持 → 启动时构建一次，变更走 `gw__invoke`，在类注释里写明当前策略）。

### Step 5 首次透传验证

启动网关，验证：

1. `tools/list` 返回 `ads__query_campaign`、`ads__list_campaigns` 等透传工具，description 带 `[via ads]`。
2. `tools/call` 调 `ads__query_campaign`，返回 mock 下游的数据。
3. 白名单外的 mock 工具（如未 include 的）不出现在列表。
4. 调用不存在工具 → 返回错误（错误码体系在 Phase 3 统一，本阶段先用 SDK 默认错误）。

## 验收标准

- [ ] mock 下游可独立启动并 curl 验证。
- [ ] `tools/list` 出现透传工具，命名/描述符合改写规则。
- [ ] `tools/call` 透传调用成功返回下游数据，链路完整。
- [ ] 白名单过滤生效。
- [ ] 含 `__` 的下游工具名切分正确。
- [ ] 决策日志补充：运行时刷新策略（按 Phase 0 结论）。

## 常见坑

- **MCP Client 连不上**：端点路径不一致（网关配的 url 要与 mock 的 `/mcp` 匹配）、Accept 头缺失。
- **工具注册时机**：若 Bean 在启动时构建，而下游连接是懒初始化，注意初始化顺序——先建池再注册工具。
- **动态 vs 静态**：Phase 0 若结论为"不支持运行时增删"，不要在 proxiedTools 上硬做热刷新，按退路实现并记录。
- **凭证**：给 mock 配 `auth: { type: none }` 最简单，别在 mock 阶段引入签名逻辑。

## 参考

- 能力清单：§骨架代码（UpstreamClientPool / proxiedTools）、§透传 Tool 策略（缓存与刷新、降级行为预览）、§凭证注入下游
- Spring AI MCP Client：<https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html>

---

## 实际实现记录（已完成）

### 下游选择
没有自建 mock 工程，直接用**官方 everything server** 作为下游：
```bash
npx @modelcontextprotocol/server-everything streamableHttp
```
端口 3001，路径 `/mcp`。包含 13 个工具（echo、get-sum、longRunningOperation 等），自带慢工具可测超时。

### 配置
```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            everything:
              url: http://localhost:3001/mcp
```
注意：配置属性是 `streamable-http.connections`，不是 `streamable`。

### 核心实现（DynamicToolTest.java）
```java
@Component
public class DynamicToolTest implements CommandLineRunner {
    private final List<McpSyncClient> mcpClients;  // 必须注入 List，不能单个
    private final ToolRegistry registry;
    private final McpSyncServer server;

    @Override
    public void run(String... args) {
        // 1. 拿下游工具列表
        List<McpSchema.Tool> downstreamTools = mcpClients.get(0).listTools().tools();
        // 2. 白名单过滤 + 改写 schema，进路由表
        registry.refresh("everything", List.of("echo", "get-sum"), downstreamTools);
        // 3. 遍历路由表，构造 SyncToolSpecification 注册到网关
        registry.all().forEach(route -> {
            McpSchema.Tool specTool = route.schema();
            String upstreamToolName = route.upstreamToolName();
            McpServerFeatures.SyncToolSpecification spec = McpServerFeatures.SyncToolSpecification.builder()
                .tool(specTool)
                .callHandler((exchange, request) -> {
                    McpSchema.CallToolRequest callRequest = new McpSchema.CallToolRequest(upstreamToolName, request.arguments());
                    return mcpClients.get(0).callTool(callRequest);
                })
                .build();
            server.addTool(spec);
        });
    }
}
```

### 踩坑记录
- **McpSyncClient 注入**：必须用 `List<McpSyncClient>` 注入，单个 `McpSyncClient` 报 `No qualifying bean`。即使只配了一个下游也是 List。
- **TextContent API（Spring AI 2.0）**：`McpSchema.TextContent.builder("文本内容")` 直接传字符串，不需要 `.text().build()` 链式。
- **CallToolResult 构造**：不能直接 `new CallToolResult(content, isError)`（需要 4 个参数），用 `CallToolResult.builder().content(...).isError(false).build()`。
- **Tool.builder().inputSchema()**：收 `Map<String, Object>`，不收 String。
- **Postman 测试**：手动复制 `Mcp-Session-Id` 到 Headers，重启服务后旧 session 失效需重新 initialize。
- **测试文件**：`mcp-test/` 目录下放 initialize.json / tools-list.json / tools-call-echo.json 等，用 `-d @file.json` 避免 cmd 转义问题。

### 面试亮点
- **网关同时是 Server 和 Client**：对上游是 MCP Server（暴露工具），对下游是 MCP Client（连接别的 MCP Server）——这是网关的核心定位。
- **为什么用 `server.addTool()` 动态注册？** 下游工具可能增删，动态注册不用重启就能热加载。
- **为什么 callHandler 里调下游 client？** 转发逻辑就是：收到客户端请求 → 按路由表找到下游 → 把参数原样转发 → 下游结果透传回客户端。
- **为什么 `List<McpSyncClient>` 注入？** Spring AI MCP Client 自动配置就是按多连接设计的，即使只配一个也是 List。
