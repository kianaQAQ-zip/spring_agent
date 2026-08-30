# 静默降级治理 — 实施记录

> 2026-08-30 · grilling 工作流产出 · 测试 69 → 80 全绿

## 问题：架构性静默失效

日志里反复出现的 `403 FreeTierOnly` 不只是"额度用完了"。真正的危害是一条**静默降级链**：

```
qwen-turbo 403
  ↓ try-catch 吞掉
SessionStateService.extractDelta → noChange=true
  ↓
状态机 intent=null, orderId=null → needsClarification 永不触发
  ↓
四个工具全部不触发（查订单 / 退款 / 改地址 / 优惠券）
  ↓
Agent 退化成 RAG 问答机，前端零感知，日志只剩一行 WARN
```

比直接 500 更危险——因为没人知道它坏了。

两个放大器：

- `deepSeekChatModel` Bean 定义了，注释写着"二者可互降"，**全项目零引用**。架构承诺了降级链，代码没接线。
- `/chat/health` 只判断 `chatClient != null`，额度耗尽、key 失效、网络中断全都报 UP。

## 实施

### P0

| 项 | 改动 |
|---|---|
| 404 归位 | `GlobalExceptionHandler.handleNotFound(NoResourceFoundException)` → 404 + debug 日志。此前被通用 handler 记成 ERROR 500，语义错误且刷屏 |
| 403 不重试 | `ModelConfig.aiRetryTemplate()`：`.notRetryOn(NonTransientAiException.class)`。默认策略对永久性错误照样重试，延迟和日志噪音翻倍 |

### P1

| 项 | 改动 |
|---|---|
| 降级信号 | 新建 `common/DegradationFlags`：ConcurrentHashMap + TTL 5min，惰性清理，输出字典序。三个服务的 catch 块 `mark()`，成功时 `clear()` |
| 契约透出 | citations 事件载荷 `{citations, degraded}`（新 record `ChatService.CitationsPayload`）；前端 `api/index.js` 兼容新旧两种格式 |
| 健康检查 | `/chat/health` 增返 `degraded` 列表与 `degradedCount` |
| 前端感知 | topbar 琥珀色 pill「基础问答模式」，hover 展开能力清单。安静不打扰，但诚实 |

### P2

| 项 | 改动 |
|---|---|
| 8080 定位 | 新建 `api/RootController`，`/` 返回端点清单，明确 8080 是纯 API、前端走 5173 |
| 降级链接线 | 新建 `common/FallbackChatModel`，主模型挂 → DeepSeek。**配置驱动**：`deepseek.api-key` 未配则原样返回裸模型，零开销 |

## 两个关键工程判断

**1. 流式降级只在第一个 token 之前切换。**

```java
chain = chain.onErrorResume(e -> started.get() ? Flux.error(e) : fb.stream(prompt));
```

已吐过 token 再切模型会导致前端出现重复内容。宁可原样抛错走 SSE error 事件。

**2. 降级链配置驱动，且明确标注局限性。**

```java
if (fallbackKey == null || fallbackKey.isBlank()) return primary;
```

若主备模型共用同一账户的免费额度池（比如都在百炼），额度耗尽时切过去照样 403——那种情况下降级链只是多打一次无谓请求。这个注释写在 `ModelConfig.withFallback()` 上，避免后人误以为装了就万事大吉。

## 契约变更

`event: citations` 的 data 从 JSON 数组改为对象：

```json
{ "citations": [...], "degraded": ["state-extract", "query-rewrite"] }
```

前端已做兼容（`Array.isArray(payload)` 分支）。

## 验证

- `mvn -o clean test` → 80/80 通过（新增 11 个测试，其中 6 个覆盖 `FallbackChatModel` 的降级/全挂/流式中途失败）
- `npx vite build` → 前端构建通过
- 提交前 key 扫描：无明文

## 待验证（需重启后端）

后端当前未运行，以下需重启后确认：

1. `curl http://localhost:8080/` → 返回端点清单
2. `curl http://localhost:8080/chat/health` → `degraded` 数组反映真实状态
3. 发一条对话 → topbar 出现「基础问答模式」pill（额度仍耗尽时）
4. 充值后重发 → pill 消失（`clear()` 生效，不必等 TTL）

## 已知限制

- `CostCalculator` 中 `glm-5.2` 沿用 `qwen-plus` 的 0.8/2.0 元价格，是占位值，需按百炼实际定价校准
- 降级标记 5 分钟 TTL，模型恢复后最坏情况 5 分钟才自动清除（成功调用会立即 `clear()`）
