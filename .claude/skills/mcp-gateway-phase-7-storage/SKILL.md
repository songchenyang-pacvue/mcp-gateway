---
name: mcp-gateway-phase-7-storage
description: 在 Phase 6 可观测与流控就绪之后使用。落地存储选型：PostgreSQL 存结构化数据（调用审计 / 路由表 / Key 表）、Redis 存会话上下文、pgvector 做记忆模块最小验证（向量入库 + 相似查询）。产出：数据落库可查、会话可恢复、向量检索 demo 跑通。
---

# Phase 7 · 存储落地（PostgreSQL + Redis + pgvector）

## 目标

1. PostgreSQL：审计记录、路由表元数据、ApiKey 表落库。
2. Redis：MCP 会话上下文存取（会话状态可恢复）。
3. pgvector：记忆模块最小验证（为后续 Agent 记忆铺路）。

> 说明：能力清单初版为 YAML 静态配置，本阶段把"必须持久化的状态"落库，为后续动态注册/多实例部署做准备。架构不变，只加存储。

## 前置条件

- [ ] Phase 6 完成：审计需要的字段已明确（trace_id、alias、tool、outcome、duration、request 摘要、caller）。
- [ ] 本地 PostgreSQL 与 Redis 可用（Docker 或本机安装）。
- [ ] PostgreSQL 开启 pgvector 扩展（`CREATE EXTENSION vector;`）。

## 任务步骤

### Step 1 依赖与配置

`pom.xml` 添加（版本由 Boot 4 BOM 管理）：

- `spring-boot-starter-data-jpa`（或 `spring-boot-starter-jdbc`，按团队习惯选一，写进决策日志）
- `org.postgresql:postgresql`
- `spring-boot-starter-data-redis`
- `org.springframework.ai:spring-ai-starter-vector-store-pgvector`（若 Spring AI 2.0 提供；否则用原生 JDBC 写向量操作——以官方文档为准）

`application.yaml` 添加 `spring.datasource.*`（PostgreSQL）与 `spring.data.redis.*` 连接配置。

### Step 2 PostgreSQL：审计表（`repository/AuditRecord.java` + JPA Repository）

按清单"日志脱敏"约束设计字段：

- `trace_id`（主索引）、`caller_tenant`、`principal`、`alias`、`tool`、`outcome`、`duration_ms`、`request_fields_summary`（字段名+长度摘要，**不存 arguments 原文**）、`response_truncated`、`error_code`、`created_at`。
- 写入路径：Phase 6 的埋点/调用链处同步或异步写（初版同步写即可，注明后续可换队列）。
- 提供按 `trace_id` 查询的接口（对齐 .NET 工程"拿 trace_id 查审计"的运维习惯）。

### Step 3 PostgreSQL：路由表与 Key 表

- 路由表：初版配置是 YAML 真相源，DB 只做**审计视图**——可把每次 `ToolRegistry.refresh` 的快照（alias、tool 数、白名单版本、时间）记一张 `registry_snapshot` 表，便于追溯"某个时间点对外暴露了什么"。
- Key 表：`api_key`（key_hash、tenant_id、principal、scopes、enabled、expires_at）。Phase 5 的内存 Key 表改为启动时从 DB 加载 + 5 分钟缓存刷新。

### Step 4 Redis：会话上下文（`session/McpSessionStore.java`）

- 用途：MCP 会话（Session-Id / 调用上下文）的状态存取。当前 Stateless 模式下主要存：会话级上下文、限流桶（可选迁移）、短暂缓存。
- 设计：`McpSessionStore` 封装 Redis 操作，key 规范如 `mcpgw:session:{sessionId}`，TTL 按会话生命周期。
- 验证：写入一个会话对象 → 重启网关 → 按 sessionId 取回（模拟"会话可恢复"）。
- 若初版 Stateless 无会话，则先存"最近 N 次调用摘要"供 `gw__list_tools` 的上下文提示使用（占位实现即可，重点是链路打通）。

### Step 5 pgvector：记忆最小验证（`memory/VectorMemoryStore.java`）

- 建表：`memory_chunks(id, content, embedding vector(1536))`（维度按所选 embedding 模型定）。
- 写路径：一条文本 → embedding（可用 Spring AI 的 EmbeddingModel，或 mock 一个固定向量）→ 入库。
- 读路径：查询文本 embedding → `ORDER BY embedding <-> ? LIMIT k` 相似检索。
- 验收：插入 3–5 条，相似查询返回相关性排序正确。
- **边界**：这是预研 demo，不接入主链路；记忆的正式设计（记忆什么、何时写入、如何衰减）留到初版后。

### Step 6 验收验证

1. 发起一次工具调用 → 审计表出现记录，含 trace_id、字段摘要，**无 arguments 原文**。
2. 重启网关 → ApiKey 从 DB 重新加载，鉴权不受影响。
3. Redis 会话写入/读取/过期验证通过。
4. pgvector 插入 5 条 + 相似查询，相关性排序合理。
5. 决策日志：记录存储选型、同步/异步审计选择、会话方案。

## 验收标准

- [ ] 审计记录可查，字段符合脱敏约束。
- [ ] ApiKey 落库 + 启动加载 + 缓存刷新可用。
- [ ] Redis 会话存取可恢复。
- [ ] pgvector 相似检索 demo 跑通。
- [ ] 决策日志更新。

## 常见坑

- **pgvector 维度**：embedding 维度必须与模型输出一致，写死前确认。
- **审计性能**：同步写库在热路径上，先跑通再考虑异步；注释里写明后续换队列的位置。
- **Redis 序列化**：默认 JDK 序列化可读性差，用 JSON 序列化（Jackson 2 与 Boot 兼容）。
- **密钥**：`key_hash` 存哈希，数据库泄露不泄露原始 Key（对齐工程经验）。

## 参考

- 能力清单：§可观测性与流控（日志脱敏）——审计字段约束来源
- Spring AI Vector Store（pgvector）：<https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html>

---

## 实际实现记录（已完成）

### 包结构
```
com.pacvue.mcpgty.repository
├── AuditRecord.java            # JPA 实体
└── AuditRecordRepository.java  # JpaRepository
```

### 审计表字段
| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 自增主键 |
| trace_id | String | 调用追踪 ID |
| caller_tenant | String | 租户 ID |
| alias | String | 下游别名 |
| tool | String | 工具名 |
| outcome | String | success / client_error / upstream_error |
| duration_ms | Long | 耗时（毫秒） |
| request_summary | String | 字段名+长度（脱敏） |
| error_code | String | 错误码 |
| created_at | Instant | 创建时间 |

### 配置
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mcp_gateway
    username: songchenyang
    password: postgres
  jpa:
    hibernate:
      ddl-auto: update
  data:
    redis:
      host: localhost
      port: 6379
```

### 踩坑记录
- **JPA ddl-auto: update**：开发阶段自动建表，生产改成 validate
- **审计写入失败不影响主流程**：try-catch 包住，打印日志但不抛异常

### 面试亮点
- **为什么审计不存 arguments 原文？** 脱敏，防止敏感信息泄露
- **为什么同步写审计？** 初版简单，后续可换队列异步
- **为什么 JPA ddl-auto: update？** 开发阶段自动建表，生产改成 validate
