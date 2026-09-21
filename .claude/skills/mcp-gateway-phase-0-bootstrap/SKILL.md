***

name: mcp-gateway-phase-0-bootstrap

description: 在 MCP 网关工程初始化之后、开始写业务代码之前使用。用于验证 Spring Boot 4.1 + Spring AI 2.0 + JDK 25 技术栈能否跑通 MCP Server 最小链路，并回答 "Spring AI 2.0 是否支持运行时增删工具" 这一决定后续架构的关键问题。产出：可 curl 验证的最小网关 + 动态工具能力结论（写入决策日志）。



***

# Phase 0・技术栈验证与最小 MCP Server

## 目标



1. 确认技术栈可启动、MCP Server 端点可被协议客户端访问。

2. 用一个 `@McpTool` 静态工具跑通 `initialize → tools/list → tools/call` 三连。

3. **验证关键问题：Spring AI 2.0 是否支持运行时增删工具**—— 该结论直接决定 Phase 2 透传列表的刷新机制（见能力清单 "待确认问题①"）。

## 前置条件



* [ ] 工程已初始化，`pom.xml` 含 `spring-boot-starter-webmvc`、`spring-ai-starter-mcp-client`、`spring-ai-starter-mcp-server-webmvc`（版本由 `spring-ai-bom` 管理）。

* [ ] `java.version` 为 25，本机 JDK 25 可用（`java -version` 确认）。

* [ ] 包名已统一（建议 `com.pacvue.mcpgateway`，若 Initializr 生成了默认包名则先重构）。

* [ ] 根目录已建 `docs/decision-log.md`，记录关键决策（本阶段结论写这里）。

## 任务步骤

### Step 1 配置 MCP Server 端点

在 `src/main/resources/application.yaml` 添加 MCP Server 配置：



```
spring:

&#x20; application:

&#x20;   name: mcp-gateway

&#x20; ai:

&#x20;   mcp:

&#x20;     server:

&#x20;       name: pacvue-mcp-gateway

&#x20;       version: 1.0.0
```



* 端点路径与 transport 细节以 Spring AI 官方 "MCP Server Boot Starter" 文档为准（见参考）。

* 若属性名与文档不一致，以官方文档为准，本 Skill 不假设版本内属性名固定。

### Step 2 写第一个静态工具

新建 `tools/GatewayStatusTool.java`（包 `com.pacvue.mcpgateway.tools`）：



* 一个 `@Component` 类，方法用 `@McpTool` 注解。

* 工具名 `status`，description 描述 "返回网关运行状态"。

* 方法无参或一个可选参数，返回 `String` 或简单 record（Spring AI 会自动转成 MCP 输出 schema）。

* 返回内容至少包含：网关名、版本、当前时间、进程 PID 或实例标识。

要点：`@McpTool` 所在类必须被 Spring 扫描到（放在主类所在包或其子包）。

### Step 3 启动并验证协议三连

启动应用后，用 curl 依次验证（`Accept` 头必须带 `text/event-stream`，Streamable HTTP 的返回可能是 SSE）：



```
\# ① 握手

curl -X POST http://localhost:8080/mcp \\

&#x20; -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \\

&#x20; -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'

\# ② 列工具：应能看到 status

curl -X POST http://localhost:8080/mcp \\

&#x20; -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \\

&#x20; -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

\# ③ 调工具

curl -X POST http://localhost:8080/mcp \\

&#x20; -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \\

&#x20; -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"status","arguments":{}}}'
```

若 `initialize` 协商失败或 404，先排查端点路径与 Spring Security 是否拦了 `/mcp`。

### Step 4 验证运行时增删工具（关键决策点）

目标：回答 "Spring AI 2.0 的 MCP Server 是否允许在运行期添加 / 移除工具，且 `tools/list` 会随之变化 "。

做法（最小 demo，只验证能力，不做业务实现）：



1. 注入 Spring AI MCP Server 相关的工具注册入口（`ToolCallbackProvider` /server 构建器持有的工具集合，以官方 2.0 API 为准）。

2. 启动后，通过一个临时 `@RestController` 暴露两个接口：`POST /demo/add-tool`、`POST /demo/remove-tool`，分别调用注册入口添加 / 移除一个临时工具（如 `demo_echo`）。

3. 调用后立刻 curl `tools/list`，观察工具是否出现 / 消失。

**结论三选一，写入&#x20;**`docs/decision-log.md`**：**



| 结论       | 对 Phase 2 的影响                                |
| -------- | -------------------------------------------- |
| 支持运行时增删  | 透传列表可热刷新，`list_changed` 通知有意义，实现动态注册         |
| 不支持（需重启） | 初版退化为 " 启动时同步一次 + `gw__invoke` 逃生通道 "，刷新机制简化 |
| 受限支持     | 记录限制条件，按限制实现                                 |

### Step 5 建立决策日志

`docs/decision-log.md` 记录：技术栈版本、动态增删结论、任何偏离清单默认值的决定。后续每个 Phase 的决策都追加到这里 —— 这是面试时 "决策记录" 素材的来源。

## 验收标准



* [ ] `java -version` 显示 JDK 25，`mvn spring-boot:run` 正常启动无版本冲突。

* [ ] curl 三步全部返回预期结果（serverInfo / 工具列表含 status / 调用返回状态文字）。

* [ ] 动态增删 demo 跑通，结论明确写入 `docs/decision-log.md`。

* [ ] 包名统一，无 Initializr 默认包残留。

## 常见坑



* `@McpTool`**&#x20;不生效**：类没被扫描（包路径问题）或方法不是 public。

* **404 on /mcp**：端点路径配置不对，或 Spring Security 默认拦截了未放行路径。

* **Jackson 冲突**：Spring AI 2.x 用 Jackson 3（`tools.jackson`），与 Boot 自带的 Jackson 2 包名不同可共存；若出现序列化异常，优先检查是否混用了 `ObjectMapper`。

* **版本不匹配**：`spring-ai-bom` 版本必须与 Boot 4.x 配套，不要手动指定 starter 版本。

## 参考



* 能力清单：§ 待确认问题①、§ 骨架代码（`@McpTool` 用法）

* Spring AI MCP Server Boot Starter：[https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)

* MCP 规范（Streamable HTTP / JSON-RPC）：[https://modelcontextprotocol.io/specification/2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18)