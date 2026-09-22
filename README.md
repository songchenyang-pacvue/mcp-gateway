# MCP 网关（Spring AI 2.0 实现）

## 一句话定位

对 AI 客户端是 MCP Server，对下游业务系统是 MCP Client——统一承担命名空间路由、鉴权、熔断、限流、可观测。

## 架构

```
AI 客户端（Claude/Cursor/Postman）
        ↓ 只连一个端点
┌─────────────────────────────────┐
│         MCP 网关                │
│  ┌─────────────────────────┐   │
│  │  ApiKeyFilter（鉴权）   │   │
│  ├─────────────────────────┤   │
│  │  RateLimiter（限流）    │   │
│  ├─────────────────────────┤   │
│  │  CircuitBreaker（熔断） │   │
│  ├─────────────────────────┤   │
│  │  ToolRegistry（路由表） │   │
│  └─────────────────────────┘   │
└─────────┬─────────────┬─────────┘
          ↓             ↓
   everything      其他下游
   (echo/get-sum)  (未来扩展)
```

## 核心机制

### 1. 命名空间路由
所有下游工具统一成 `{alias}__{toolName}`，如 `everything__echo`。
- 白名单过滤：只暴露业务需要的工具
- schema 改写：name 加前缀，description 加 `[via alias]`
- 别名冲突启动即报错（fail-fast）

### 2. 元工具（网关自描述）
四个 `gw__` 工具：
- `gw__list_servers`：看上游状态
- `gw__list_tools`：搜索工具（不带完整 schema，省 token）
- `gw__describe_tool`：看完整 schema
- `gw__invoke`：逃生通道，直接指定 server + tool 调用

### 3. 熔断降级
三档状态机：UP → DEGRADED → DOWN
- 30 秒窗口，失败率 > 50% 且请求数 ≥ 5 → DOWN
- DOWN 30 秒后半开探活，成功恢复，失败继续 DOWN
- 下游挂了不再转发，快速失败返回错误码

### 4. 鉴权
- 内网 ApiKey：X-Api-Key 请求头校验
- CallerContext 统一身份：OAuth 和 ApiKey 最终都解析成同一个对象
- 403 不带 WWW-Authenticate（不触发 OAuth 握手）

### 5. 流控
- 令牌桶限流：60 次/分钟/租户
- 手写令牌桶，按租户维度

### 6. 可观测
- 三个指标：调用计数、下游耗时、熔断状态
- Micrometer 埋点，actuator 暴露
- 日志脱敏：不打印 arguments 原文

### 7. 审计落库
- PostgreSQL 存调用审计：trace_id、tenant、tool、outcome、duration
- 脱敏：只存字段名+长度，不存原文

## 技术栈

- Spring Boot 4.1.1 + JDK 25
- Spring AI 2.0.1（MCP Server + MCP Client）
- PostgreSQL + Redis（预留）
- Micrometer + Actuator

## 快速开始

```bash
# 1. 启动下游
npx @modelcontextprotocol/server-everything streamableHttp

# 2. 启动网关
mvn spring-boot:run

# 3. 测试（Postman）
POST http://localhost:8080/mcp
Header: X-Api-Key: sk-demo-001
Body: {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
```
