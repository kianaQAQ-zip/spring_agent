# M6 验证记录 — 三层上下文状态机 + QueryRewrite

> 日期：2026-08-26
> 状态：✅ 已构建 + 本机验证通过（**`mvn test` 54/54 全绿，BUILD SUCCESS**）。

## 1. 交付内容

| 模块 | 类 | 职责 |
| --- | --- | --- |
| L2 状态 | `agent/SessionState` + `SessionStateRepository` | `session_state` 表持久化 + `version` 乐观锁（§8.8.3） |
| 并发写回 | `agent/SessionWriteLock` | 同 session 串行锁（读-改-写临界区） |
| 增量提取 | `agent/SessionStateService` + `SessionStateDelta` | qwen-turbo + `BeanOutputConverter<SessionStateDelta>` 增量覆盖（非废话摘要） |
| 信号检测 | `agent/SignalDetector` + `Signal` | 纯规则打分 + 否定窗口 + 订单号抽取 + 孤立情绪不触发 |
| 状态机 | `agent/StateMachine` + `Decision` | 意图转移 + 主动澄清（多订单/代词/意图跳转） |
| 查询改写 | `agent/QueryRewriteService` | L1 感知改写（仅改检索句）+ Caffeine 缓存（MD5 key，TTL 10min） |
| 上下文装配 | `agent/ContextAssembler` | 分区模板 + DocumentSelector token 预算（1500） |
| 编排接入 | `agent/ChatService` | 信号→状态提取→状态机→QueryRewrite→检索→装配→流式 |

## 2. 关键设计决策

1. **三层上下文纠正「10 轮窗口」反模式**：L1 短期记忆（MessageChatMemoryAdvisor，M3）+ L2 结构化状态（M6 本里程碑）+ L3 检索知识（M5 混合召回）。不再无脑塞满最近 N 轮，而是「状态只存任务推进所需最小结构」。
2. **增量提取**：`SessionStateDelta` 只含非空字段，非空覆盖、空字段保持不变，实现「意图翻转替换、不并存」；LLM 失败保守降级 `noChange=true` 不阻塞对话。
3. **乐观锁 + 串行锁双重并发防护**（§8.8.3）：进程内 `SessionWriteLock` 串行化同 session 写回，DB 层 `UPDATE ... WHERE version=?` 乐观锁兜底（多实例安全）。
4. **否定窗口**（§8.8.1 高频追问）：「心情不好但订单没问题」因「没」落在「订单」前后 3 字窗口内而不误触发订单意图。
5. **QueryRewrite 缓存**：key = MD5(近 3 轮 + query)，Caffeine TTL 10min，避免每轮重复调 LLM 改写；改写只用于检索，用户原话原样进对话。
6. **主动澄清**：多订单口述冲突 / 代词指代无订单号 / 意图不兼容跳转 → 直接返回澄清话术，不进检索（省一次 LLM + 检索）。

## 3. 测试清单

| 测试类 | 类型 | 覆盖点 | 用例数 |
| --- | --- | --- | --- |
| `SignalDetectorTest` | 纯逻辑 | 否定窗口 / 退款+订单号 / 多订单 / 代词 | 4 |
| `StateMachineTest` | 纯逻辑 | 多订单澄清 / 代词澄清 / 兼容转移 | 3 |
| `ContextAssemblerTest` | 纯逻辑 | token 预算 / 状态分区+编号 | 2 |
| `QueryRewriteServiceTest` | Mockito | 改写 + Caffeine 缓存命中（仅 1 次 LLM） | 1 |
| `SessionStateServiceTest` | `@SpringBootTest`(H2) | 增量提取落库 / LLM 失败降级 | 2 |
| `ChatServiceTest`（更新） | `@SpringBootTest` | citations 先发 + 流式 + 记忆/截断 | 2 |

> 新增/更新 14 例；连同 M1–M5 既有 42 例，全量 **54 例全部通过**（本机 `mvn test` 已确认）。

## 4. 运行方式

- **本机验证**：`mvn test`。
- **真实联调**：
  1. 本机 PG `db/init.sql` 重建 `session_state`（`state_json` 已改 TEXT）；
  2. `mvn spring-boot:run`；
  3. 多轮对话：先问「ORD-1001 到哪了」，再问「那它能退吗」——观察 QueryRewrite 是否把代词改写成含订单号的检索句、session_state 是否增量更新。

## 5. 已知限制

- `session_state` 的 `state_json` 用 TEXT 存 JSON 字符串（跨 H2/PG 兼容）；如需 JSON 路径查询可换回 JSONB。
- 主动澄清分支当前不走 LLM 记忆落库（直接返回澄清话术），会话记忆一致性在 M8 状态循环中收口。
- 状态提取每次对话同步调 qwen-turbo，生产可改异步/批量化。
