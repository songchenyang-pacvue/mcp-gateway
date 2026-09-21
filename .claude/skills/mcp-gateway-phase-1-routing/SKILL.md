---
name: mcp-gateway-phase-1-routing
description: 在 Phase 0 验证技术栈之后使用。实现配置驱动的下游接入与命名空间路由：GatewayProperties 配置绑定、ToolRegistry 路由表（alias__toolName 命名空间）、白名单透传过滤与 schema 改写、别名冲突 fail-fast。产出：YAML 配置 2 个上游后路由表正确构建，冲突时启动失败。
---

# Phase 1 · 配置绑定与命名空间路由

## 目标

1. 用 `@ConfigurationProperties` 把 YAML 中的上游声明绑定成强类型配置。
2. 实现 `ToolRegistry`：对外工具名 `{alias}__{toolName}` 的构建、解析、白名单过滤、schema 改写。
3. 别名合法性校验与冲突 fail-fast。

## 前置条件

- [ ] Phase 0 完成：技术栈可启动，`docs/decision-log.md` 已建立。
- [ ] 明确下游 mock 方案（本地最小 MCP Server，阶段 2 接入；本阶段只做配置与内存路由，不连下游）。

## 任务步骤

### Step 1 配置绑定（`config/GatewayProperties.java`）

按能力清单"骨架代码·配置绑定"实现：

```java
@ConfigurationProperties("gateway")
public record GatewayProperties(List<Upstream> upstreams) {

    public record Upstream(
        String alias,
        String url,
        String transport,        // 初版固定 streamable-http
        Duration timeout,        // 默认 10s，报表类可覆写为 30s
        String requiredScope,    // 租户 scope 过滤，Phase 5 启用
        ToolFilter tools,        // 透传白名单
        boolean enabled,
        Map<String, Object> auth // {type, header, value}，Phase 2 用
    ) {}

    public record ToolFilter(List<String> include) {}
}
```

- 注册方式：主类加 `@EnableConfigurationProperties(GatewayProperties.class)` 或 `@ConfigurationPropertiesScan`。
- 在 `application.yaml` 添加 `gateway.upstreams`，至少两个 alias（如 `ads`、`report`），占位 URL 即可（本阶段不真正连接）。

### Step 2 别名校验器（`config/AliasValidator.java`）

按能力清单"命名与路由规则"实现校验：

- 小写字母、数字、单下划线；首字符为字母；长度 2–16；不允许含 `__`。
- 不合法 → 启动时抛异常（fail fast）。

### Step 3 路由表（`registry/ToolRegistry.java`）

按能力清单"骨架代码·路由注册器"实现，要点：

- 数据结构：`ConcurrentHashMap<String, Route>`，key 为对外全名 `{alias}__{toolName}`。
- `record Route(String alias, String upstreamToolName, McpSchema.Tool schema)`。
- `refresh(alias, upstreamTools)`：按白名单过滤 → 重写 schema → 放入路由表。
- `resolve(exposedName)`：查路由表（Phase 2 起负责转发前的路由决策）。
- `search(server, keyword)`：供 `gw__list_tools` 使用（Phase 3 实现）。

### Step 4 schema 改写（`registry/SchemaRewriter.java`）

按能力清单"透传策略·Schema 改写"：

1. `name` 加 `{alias}__` 前缀。
2. `description` 末尾追加 `[via {alias}]` 来源标注。
3. **`inputSchema` 一律不改**（改写入参会让下游校验报错对不上号）。
4. 名称长度超 64 → 截断 + 追加 4 位哈希后缀，映射记入路由表。

### Step 5 冲突检测（启动时）

启动时扫描全部 upstream：

- 两个 server 用了相同别名 → **启动失败**，报错信息点名两个冲突配置。
- 同 server 内 tool 名重复 → 启动失败或告警（按清单：fail fast 优先）。
- 下游自身 tool 名含 `__` → 保留原名，切分按第一个 `__`（本阶段只需在注释/文档里声明该规则，切分逻辑在 Phase 2 生效）。

### Step 6 单元验证

写一个简单的启动测试或 `CommandLineRunner`，验证：

- 2 个 alias 配置 → 路由表条目数正确，`ads__query_campaign` 能被 resolve。
- 白名单 `include: [query_campaign, list_campaigns]` → 未声明的 tool 不在路由表。
- 非法别名 / 重复别名 → 启动抛异常。

## 验收标准

- [ ] `mvn spring-boot:run` 启动后，路由表包含全部白名单工具的 `{alias}__{name}` 条目。
- [ ] `resolve("ads__query_campaign")` 返回带 alias 与上游原名的 Route。
- [ ] 未声明白名单的 tool 不进入路由表。
- [ ] 别名冲突配置启动即失败，错误信息可定位。
- [ ] schema 改写满足：name 加前缀、description 加 `[via alias]`、inputSchema 原样。

## 常见坑

- **`@ConfigurationProperties` 不生效**：record 类型需要 Boot 3.2+，Boot 4 支持；确认已启用扫描。
- **YAML 里 `auth` 用了 `${ENV}` 占位**：本阶段不解析，保持占位即可（`value: ${ADS_MCP_KEY}`），解析在 Phase 2。
- **ConcurrentHashMap 遍历与修改并发**：refresh 与 resolve 并发时用不可变 snapshot 或按 key 原子 put，不要边遍历边改。

## 参考

- 能力清单：§命名与路由规则、§配置格式、§透传 Tool 策略（Schema 改写、冲突处理）
- 能力清单：§骨架代码（ToolRegistry / GatewayProperties）

---

## 实际实现记录（已完成）

### 实际包结构
```
com.pacvue.mcpgty
├── config/
│   ├── GatewayProperties.java      # @ConfigurationProperties("gateway")
│   └── AliasValidator.java         # @EventListener(ApplicationReadyEvent) 校验
├── registry/
│   ├── ToolRegistry.java           # ConcurrentHashMap<String, Route>
│   └── SchemaRewriter.java         # name 加前缀、description 加 [via]
└── tool/
    ├── CalculatorTools.java        # @McpTool 静态工具（Phase 0 遗留）
    ├── DynamicToolTest.java        # CommandLineRunner 动态注册下游工具
    └── GatewayMetaTools.java       # CommandLineRunner 注册元工具
```

### 关键决策
- **alias 硬编码**：初版在 DynamicToolTest.run() 里硬编码 alias="everything"，未走 gateway.upstreams 配置驱动。后续 Phase 接入多下游时再改为配置驱动。
- **白名单过滤**：在 `registry.refresh(alias, includeList, downstreamTools)` 里按白名单过滤，未声明的工具不进路由表。
- **SchemaRewriter**：name 加 `{alias}__` 前缀，description 末尾追加 `[via {alias}]`，inputSchema 原样不动。

### 踩坑记录
- **record 作为 @ConfigurationProperties**：Boot 4 + JDK 25 支持 record 绑定，无需额外配置。
- **ConcurrentHashMap 并发**：refresh 和 resolve 并发时按 key 原子 put，不要边遍历边改。
- **包名统一**：最终定为 `com.pacvue.mcpgty`（去掉下划线），所有类在这个包下。

### 面试亮点
- **为什么用 `{alias}__{toolName}` 命名空间？** 多个下游同名工具不冲突，客户端一看就知道哪个工具来自哪个下游。
- **为什么 inputSchema 不改？** 改写入参会让下游校验报错对不上号，原样透传最安全。
- **为什么白名单过滤？** 下游可能有几十个工具，全部暴露给大模型会占 context 窗口，只暴露业务需要的。
- **为什么别名冲突 fail-fast？** 启动就报错比运行时路由错乱好排查。
