---
name: mcp-gateway-phase-4-circuit-breaker
description: 在 Phase 3 元工具与错误码就绪之后使用。实现下游熔断与降级三档状态机（UP / DEGRADED / DOWN）：30 秒窗口失败率超 50% 且请求数 ≥ 5 进入 DOWN、半开 30 秒探活、DOWN 时工具从列表移除并返回 UPSTREAM_NOT_FOUND。产出：杀掉 mock 下游后状态迁移与工具列表变化可观察。
---

# Phase 4 · 熔断与降级

## 目标

1. 实现下游三档状态机：`UP` / `DEGRADED` / `DOWN`。
2. 按清单参数实现熔断判定与半开探活。
3. **DOWN 时工具从列表移除**（而非保留报错）——避免 AI 客户端看到 tool 存在就反复尝试。

## 前置条件

- [ ] Phase 3 完成：元工具可返回 `status` 字段（当前为占位，本阶段替换为真实状态机）。
- [ ] `gw__list_servers` 的 `status` 字段已预留（UP/DEGRADED/DOWN）。

## 任务步骤

### Step 1 状态机模型（`upstream/UpstreamState.java`）

- 状态枚举：`UP`、`DEGRADED`、`DOWN`。
- 每个上游维护：`state`、失败窗口统计（滑动 30s：请求数、失败数）、`lastProbeAt`、`halfOpenSince`。

### Step 2 熔断判定（`upstream/CircuitBreaker.java`）

按清单参数实现，纯内存即可（初版不引入外部熔断库，手写能讲清原理；也可选用 Resilience4j 对比）：

- 判定窗口：**30 秒**。
- 触发条件：窗口内**失败率 > 50% 且请求数 ≥ 5** → `DOWN`。
- 进入 `DOWN` 后：拒绝新调用，返回 `UPSTREAM_NOT_FOUND`。
- **半开**：进入 `DOWN` 30 秒后允许一次探活请求；成功 → `UP`（重置统计），失败 → 继续 `DOWN`。
- `DEGRADED`：探活超时但未达熔断阈值（如失败率 > 0 但 < 50% 或请求数 < 5）→ 仍转发，失败计入统计。

状态迁移图（写进类注释，面试要能画）：

```
UP --失败率>50% 且 ≥5请求--> DOWN --30s 后半开探活成功--> UP
UP --部分失败--> DEGRADED --继续失败--> DOWN
DOWN --探活失败--> DOWN
```

### Step 3 接入调用链路

- 转发前检查状态：`DOWN` → 直接抛 `GatewayException(UPSTREAM_NOT_FOUND)`，不发请求。
- 每次调用后更新统计：成功/失败（超时、5xx、连接失败均计失败；4xx 业务错误按清单不计入熔断——**重试策略只覆盖连接失败**，这里保持一致）。
- 把统计更新做成**并发安全**：窗口统计用原子计数或同步块，避免高并发下计数错乱。

### Step 4 探活实现

- 探活 = 对下游发一次 `tools/list`（或 `ping`，以 mock 支持为准），记录 `lastProbeAt`。
- 半开状态下允许的探活与正常转发共用一条路径（一次调用即一次探活）。

### Step 5 列表联动

- `gw__list_servers`：返回真实状态。
- **工具列表联动**：`DOWN` 状态下，该 alias 的透传工具从对外 `tools/list` 移除（Phase 2 的 proxiedTools/路由表加状态过滤）；`gw__invoke` 仍返回 `UPSTREAM_NOT_FOUND`（不泄露工具存在性）。

### Step 6 验收验证

1. 正常：mock 在线，`gw__list_servers` 显示 UP，工具全列表可见。
2. 杀掉 mock 进程：
   - 连续调用触发失败统计 → 观察 `DEGRADED` → 条件满足后 `DOWN`。
   - `DOWN` 后 `tools/list` 中该 alias 工具消失。
   - 直接调用透传工具 → `UPSTREAM_NOT_FOUND`。
3. 重启 mock：半开探活成功 → 恢复 `UP`，工具重新出现。

## 验收标准

- [ ] 三档状态迁移可观察（`gw__list_servers` 反映真实状态）。
- [ ] `DOWN` 后工具从 `tools/list` 移除，调用返回 `UPSTREAM_NOT_FOUND`。
- [ ] 半开探活成功恢复、失败维持 DOWN。
- [ ] 状态迁移图写入类注释或决策日志。

## 常见坑

- **统计窗口**：用"固定 30s 窗口"简单可靠；用滑动窗口更平滑但复杂，初版选其一并在注释说明。
- **并发更新**：熔断统计在热路径上，避免加锁过重；用 `LongAdder`/原子类。
- **误伤**：4xx 业务错误不计入熔断（清单默认参数只重试连接失败），别把客户端错误算成下游故障。

## 参考

- 能力清单：§降级行为（三档表、熔断阈值、DOWN 移除理由）
- 能力清单：§默认参数（重试仅连接失败 1 次）

---

## 实际实现记录（已完成）

### 包结构
```
com.pacvue.mcpgty.upstream
├── UpstreamState.java          # 枚举：UP / DEGRADED / DOWN
├── CircuitBreaker.java         # 每个上游一个实例，滑动窗口统计
└── UpstreamManager.java        # @Component，持有 alias → CircuitBreaker 映射
```

### 核心参数
- 统计窗口：30 秒
- 最少请求数：5
- 失败率阈值：50%
- 半开等待：30 秒

### 状态机逻辑
```java
// allowRequest()：转发前检查
if (state == DOWN) {
    if (等够30秒) → 切 DEGRADED，放行一次（探活）
    else → 拒绝
}

// recordSuccess()：调用成功
resetWindow() + 如果是 DEGRADED → 切回 UP

// recordFailure()：调用失败
if (state == DEGRADED) → 立刻切回 DOWN（探活失败）
else → 累计计数，窗口到了检查失败率
```

### 接入位置
- **DynamicToolTest.callHandler**：转发前 `allowRequest()`，成功 `recordSuccess()`，失败 `recordFailure()`
- **GatewayMetaTools.gw__list_servers**：返回 `breaker.getState().name()`

### 踩坑记录
- **半开探活失败后回到 DOWN**：DEGRADED 状态下失败要立刻切回 DOWN，不能重新开窗口统计（否则所有请求又被放行）
- **窗口判断时机**：是"窗口到了才检查失败率"，不是"累计5次就熔断"。点得快会累计更多次才触发。
- **下游未启动时网关报错**：Spring AI MCP Client 启动时自动连接下游，连不上直接 `Client failed to initialize`。测试熔断前先启动下游，测的时候再关掉。

### 面试亮点
- **为什么需要熔断？** 下游挂了还一直转发 = 每次都等超时，浪费时间还把错误堆给大模型
- **为什么最小 5 个请求？** 避免偶发波动误判（2次失败就熔断太敏感）
- **为什么 30 秒窗口？** 短时间内连续失败才说明下游真挂了，不是网络抖动
- **半开探活的意义？** 不是永久熔断，等 30 秒放一个请求试试，成功就恢复，失败继续熔断
- **为什么 DOWN 时不转发直接拒绝？** 下游已经挂了，转发也是等超时，不如快速失败返回错误码
