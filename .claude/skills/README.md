# MCP Gateway 开发 Skills

基于《MCP Gateway 能力清单（初版）》拆分的分阶段开发 Skill 集，每个阶段一个 Skill，遵循 Agent Skills 规范（`SKILL.md` + YAML frontmatter，Anthropic 2025 年发布后为业界通用结构，Claude Code / Cursor / 各类 Agent 工具均可识别）。

## 安装方式

将本目录下每个 `mcp-gateway-phase-*` 目录整体复制到项目根目录的 `.claude/skills/` 下：

```
<项目根>/.claude/skills/
├── mcp-gateway-phase-0-bootstrap/SKILL.md
├── mcp-gateway-phase-1-routing/SKILL.md
├── mcp-gateway-phase-2-mcp-client/SKILL.md
├── mcp-gateway-phase-3-meta-tools/SKILL.md
├── mcp-gateway-phase-4-circuit-breaker/SKILL.md
├── mcp-gateway-phase-5-auth-tenant/SKILL.md
├── mcp-gateway-phase-6-observability-flow/SKILL.md
├── mcp-gateway-phase-7-storage/SKILL.md
└── mcp-gateway-phase-8-interview/SKILL.md
```

- 若使用 Cursor，可同时复制到 `.cursor/skills/`。
- 阶段按序执行，每个 Skill 的"前置条件"声明了依赖的上一阶段产物。
- 每个阶段完成后，把"验收标准"逐项勾掉；结论写入 `docs/decision-log.md`（阶段 0 建立）。

## 阶段总览

| Skill | 阶段 | 产出 |
| --- | --- | --- |
| phase-0-bootstrap | 技术栈验证 | 最小 MCP Server + 动态增删 tool 结论 |
| phase-1-routing | 配置与路由 | GatewayProperties + ToolRegistry |
| phase-2-mcp-client | Client 接入 | UpstreamClientPool + 首次透传 |
| phase-3-meta-tools | 元工具 | 4 个 gw__ 工具 + 错误码 |
| phase-4-circuit-breaker | 熔断 | 三档状态机 |
| phase-5-auth-tenant | 鉴权 | CallerContext + ApiKey + scope |
| phase-6-observability-flow | 可观测+流控 | 指标 + 限流 + 截断 + 脱敏 |
| phase-7-storage | 存储 | PG + Redis 会话 + pgvector |
| phase-8-interview | 收尾 | README + 面试讲稿 |

## 依赖

- 工程：Spring Boot 4.1.1 + Spring AI 2.0.1 + JDK 25
- 依赖清单见项目 `pom.xml`（webmvc / mcp-client / mcp-server-webmvc）
- 官方参考：Spring AI MCP 文档 <https://docs.spring.io/spring-ai/reference/api/mcp/index.html>
