# 电商客服 Agent — 架构文档（企业级 / 面试 demo）

> 单商户自用，代码预留 `tenant_id` 多租户接缝。技术栈：Spring Boot 3.5 + Spring AI 1.0 / PgVector / Apache Tika / 通义 qwen-plus（主，阿里云百炼）+ DeepSeek-V3（降级）+ 通义 text-embedding-v3（阿里云百炼）/ Qwen-VL（阿里云百炼，多模态）/ Vue3+Vite+ElementPlus。

---

## 0. 模型接入：阿里云百炼（Bailian / DashScope）

所有阿里系模型统一走**阿里云百炼 DashScope 的 OpenAI 兼容接口**：

```properties
# 百炼 OpenAI 兼容网关（所有通义/Qwen 模型共用）
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
# API Key 必须走环境变量，禁止落库（§5 合规）
DASHSCOPE_API_KEY=sk-***
```

- **接入模型**：`qwen-plus`（主 LLM）、`qwen-turbo`（QueryRewrite / OutputGuardrail 的轻量判定模型）、`text-embedding-v3`（embedding，dim=1024）、`qwen-vl-max` / `qwen2.5-vl-72b`（多模态图表摘要）。
- **Spring AI 1.0 接法**：用 `OpenAiChatModel` / `OpenAiEmbeddingModel`，把 `base-url` 指向上述地址、model 填 `qwen-plus` / `text-embedding-v3` 等即可；多模态用 `UserMessage` + `Media` 走同一 base-url。
- **降级 LLM**：DeepSeek-V3 属独立供应商，base-url 不同（`https://api.deepseek.com/v1`），二者均经 OpenAI 兼容协议，可互降（见 M1 多模型 Bean 配置）。
- **数据边界**：Qwen-VL 仅上传图表截图、不含业务 PII（§9.3）；chat/embedding 走百炼云端，生产换本地 BGE 做数据不出域（面试讲设计）。

### 部署拓扑（当前本机原生 · 保留 Docker 兼容性）

> **当前阶段指令**：先不管 Docker，直接连本机 PostgreSQL（`D:\PostgreSQL`）开发。但**保留 Docker 兼容性**作为未来服务器部署方向（企业级项目通常上服务器）。本机有 Python 环境，故 OCR / 重排 / 清洗等重处理**单独建成一个 Python 子项目**，由 Spring Boot 通过清晰接口调用。

- **当前阶段（M1–M9 主体）**：Spring Boot 应用**本机运行**，向量库用**本机 PostgreSQL + pgvector**（`D:\PostgreSQL`，连 `localhost:5432`）。**当前不启 Docker**。
- **Python 文档处理子项目（独立工程，`doc-processor/`）**：专责 OCR 文字识别、文本重排（rerank）、数据清洗等核心处理（§10）。当前**本机原生 Python 进程**运行（FastAPI，连 `localhost:8000`）；其 `Dockerfile` 已就绪，未来可直接容器化上服务器。
  - Java 侧通过**声明式 HTTP 客户端**（如 Spring `RestClient` / `WebClient`）调用，接口契约见 §10.2；配置项 `doc-processor.url` 在 dev=`http://localhost:8000`、prod=服务名（或容器名），**解耦、可切换**。
- **重服务当前即真接（不再降级占位）**：§9.2（MinerU+OCR）、§9.3（Qwen-VL 多模态）、§9.5 的 Cross-encoder 重排**经 `doc-processor` 子项目落地**，不再走"未来目标 / 降级"路径：
  - 解析：经 Python 子项目 `MinerU`（layout + 内置 PaddleOCR）做扫描件 OCR 还原；
  - 重排：`bge-reranker-v2-m3` 在 Python 子项目内运行，Java 传 query+documents 取回重排序；
  - 多模态：图表摘要走 Qwen-VL（DashScope，仅传图表截图不含 PII），归入 Python 子项目或 Java 直调均可，接口统一。
- **Docker 兼容性（未来）**：
  - `doc-processor/` 自带 `Dockerfile`（FastAPI + torch + paddle + FlagEmbedding），`docker build` 即服务；
  - Spring Boot 应用未来也可加 `Dockerfile` + `docker-compose.yml`（app + doc-processor + postgres-pgvector）一条命令起整套，面试/交付可演示；
  - **当前不强制**：开发期全本机原生，Docker 仅作"可容器化"的兼容保障，不阻断日常迭代。
- **配置**：仅 `application.yml`（连本机 PG `D:\PostgreSQL` + 百炼 API + `doc-processor.url=http://localhost:8000`），不拆 dev/prod profile（保留切换点即可）。

---

## 1. Advisor 链（显式，面试核心追问）

Spring AI 的 Advisor 在请求方向顺序执行、响应方向逆序执行。`ChatClient` 编排如下链：

**请求方向（user → LLM）**
| # | Advisor | 职责 | 关键配置 |
|---|---|---|---|
| 1 | `MessageChatMemoryAdvisor`（**仅 Layer 1 短期记忆**） | 注入最近 8 轮原始对话，保语气/代词指代连贯；**不含业务状态** | `ChatMemory` 实现（内存/Redis），窗口=最近 8 轮 |
| 2 | `QueryRewriteAdvisor`（自定义） | 用历史把口语化 query 重写为**独立检索句**（仅喂 RAG 检索，不改原始 user message） | 低温度、可走轻量模型 qwen-turbo |
| 3 | `QuestionAnswerAdvisor` | 用重写后的 query 做向量检索 + 拼接上下文进 prompt | `SearchRequest`: topK=4, similarityThreshold=0.78, filterExpression=`tenant_id == :tenantId` |
| 4 | `ToolCallConfirmAdvisor`（自定义，包裹 tool 执行） | 拦截模型返回的 `tool_call`：**需确认** → 存 pending、短路返回"待确认"信号；**只读** → 直接执行 | 基于自定义 `ToolExecutionStrategy` |

**响应方向（LLM → user，逆序）**
| # | Advisor | 职责 |
|---|---|---|
| 5 | `OutputGuardrailAdvisor`（自定义） | PII 输出脱敏 + 越界拒答 + 事实一致性（答案不得超出检索上下文断言） |
| 6 | Spring AI 原生 `Observation` + `CostTrackingAdvisor`（自定义） | Micrometer/OTel trace 与 token 成本（**用框架原生，不自建表**） |

**生产升级路径**：Spring AI 1.0 模块化 RAG 用 `RetrievalAugmentationAdvisor` + `QueryTransformer`（`CompressionQueryTransformer`）+ `DocumentRetriever` + `DocumentRanker`。上文 `QueryRewriteAdvisor` 即对应其 `QueryTransformer` 环节；demo 用 `QuestionAnswerAdvisor` 最简，生产切模块化 RAG。

**工具门控实现要点**：通过 `ChatClient.Builder.defaultToolExecutionStrategy(customStrategy)` 注入自定义策略。策略读取 tool 上的 `@ConfirmRequired` 标记接口：
- 只读 tool（订单/物流查询）→ 直接执行；
- 需确认 tool → 检查 `ToolContext` 中是否已带"已确认"凭证，无则写入 `pending_action`、返回 sentinel 让链路短路，不调用真实服务。

> **上下文管理 ≠ 把历史塞进 prompt**：`MessageChatMemoryAdvisor` 只覆盖 Layer 1 短期记忆。完整的"理解上下文 / 维持任务 / 提供事实"三层模型与结构化会话状态见 **§8**（面试高频追问，决定 Agent 是玩具还是生产级）。

### 1.1 OutputGuardrailAdvisor：事实一致性（LLM-as-judge）
在 `after` 阶段调一次轻量模型（qwen-turbo）做幻觉判定，Prompt：
```
文本
检索到的上下文：{retrievedDocs}
模型回答：{modelResponse}
判断：回答中是否有任何事实性断言不在检索上下文中？
仅回复：PASS（全部可验证）或 FAIL（存在幻觉）+ 具体句子
```
- `PASS` → 正常流出；
- `FAIL` → 丢弃原回答，返回降级话术："根据已有信息，我暂无法确认该问题，建议转接人工客服。"（与 PII 脱敏同在该 Advisor 内串行执行）。

### 1.2 流式场景的历史写入时机（边界情况）
`MessageChatMemoryAdvisor` 默认在 `ChatResponse` 完全生成后才写历史；SSE 流式下若用户在生成中途打断，最后一轮可能丢失。
- **正常结束**：在 SSE `onComplete` 回调中手动 `chatMemory.add(userMessage, assistantMessage)`；
- **异常中断**：`onError` 中也追加该轮，并对 assistant 消息打 `truncated` 标记，避免半句残留在历史里误导后续改写/回答。
- 面试主动提这一点，说明考虑了流式边界。

---

## 2. 确认护栏：并发 / 超时 / 幂等 / 双执行（工程深度）

### 2.1 超时（坐席 5 分钟不确认）
- `pending_action.created_at` + 可配 TTL（默认 300s）。
- `PendingActionReaper`（`@Scheduled` fixedDelay）扫描：
  `SELECT ... WHERE status='pending' AND created_at < now()-TTL FOR UPDATE SKIP LOCKED`
  → 置 `status='expired'` → 推事件给坐席台（卡片置灰"已超时"）+ 向客户聊天推送"退款请求超时未确认，请重新发起或转人工"。
- `SKIP LOCKED` 保证多实例部署时只有一台处理，是生产级接缝。

### 2.2 幂等（重复点击 / 同笔重复发起）
- 幂等键 `idempotency_key = hash(conversation_id + tool + normalized_params)`。
- LLM 提议 tool_call 时先查：若存在 `status IN ('pending','confirmed')` 的同键记录 → **不新建**，返回"已有待确认/已处理请求"。
- 客户端防抖：同一客户消息只生成一次提议；客户主动"取消" → `status='cancelled'`。

### 2.3 双执行防护（两个坐席同时确认）
- `UPDATE pending_action SET status='confirmed' WHERE id=? AND status='pending'` 依赖行级锁；受影响行数 = 0 即"他人已处理" → 返回冲突，不重复执行。
- 执行器执行前再校验 `status='confirmed' AND executed_at IS NULL`，执行后置 `executed_at`；真实写服务调用放在事务内。

### 2.4 结果回灌
执行结果（如"实际退款 ¥100"）写入消息流并作为下一轮 `AdvisedRequest` 的上下文，LLM 据此续答（"已为您退款 ¥100"），保证模型认知与真实发生一致。

---

## 3. QueryRewriteAdvisor（被低估的核心环节）

**问题**：客户说"这个能退吗？" → RAG 直接拿原句检索，命中率崩。需结合上文补全为"订单 ORD-1001 的 iPhone 15 七天无理由退货政策"。

**设计要点**：
- 自定义 Advisor 调用轻量模型（qwen-turbo，temperature≈0）生成**独立检索句**，输入 = 最近若干轮 + 当前 query，输出 = 重写后的检索 query。
- **仅改检索 query，不改传给主 LLM 的 user message**：客户原话仍用于生成，保证语气自然；重写句只进 `QuestionAnswerAdvisor` 的检索。
- 低延迟：改写串行但在 RAG 之前，模型轻量。

### 3.1 缓存实现（Caffeine）
- **选型**：Caffeine 本地缓存（客服场景重复问题多，命中率预估 60%+）。
- **key** = `MD5(最近 3 轮对话摘要 + 当前 query)`；**value** = 改写后的检索句；**TTL = 10 分钟**。
- 命中则跳过轻量模型直出检索句；未命中则改写并回填。多实例下可选 Redis 二级缓存（demo 用本地即可）。

---

## 4. 评估集与 EvalRunner（自动化判分）

### 4.1 eval-set.json 结构
```json
[
  {
    "id": "refund-001",
    "category": "refund",
    "question": "我昨天买的耳机能退吗？",
    "context": { "expected_doc_id": "policy_return_7day", "expected_keywords": ["七天无理由","不影响二次销售"] },
    "forbidden_keywords": ["一定全额退","免单"],
    "requires_tool": { "tool": "RefundTool", "expect_params": { "orderNo": "ORD-1001" } }
  }
]
```

### 4.2 EvalRunner（Spring Boot 测试 / CLI）
逐 case 跑完整链路（检索评测可只跑 RAG），输出报告：
- **检索召回** recall@k：期望 doc 是否进 top-k；
- **忠实度**（LLM-as-judge，qwen-plus 按 rubric 打 1–5）：答案是否抵触检索上下文；
- **关键词覆盖**：expected 命中、forbidden 必须缺失；
- **工具正确性**：requires_tool case 是否提议正确 tool + 参数（参数经确认护栏校验）。
- 报告：每 case pass/fail + 聚合分（JSON/HTML），可作为 CI 回归门禁（demo 先离线跑）。

---

## 5. PII 双重脱敏（位置澄清）

`common/PiiMaskUtil` 是脱敏引擎，在**两道缝**调用，消除歧义：

1. **入库脱敏（对话落库时）**：客户消息（手机号/地址/订单号）在写入 `message` 表**之前** `mask()` → 保护静态数据。
2. **输出脱敏（响应流出时）**：`OutputGuardrailAdvisor` 对 LLM 回答做 PII 检测并遮盖后再流式推给前端 → 保护界面与日志。
3. **日志/链路**：自定义 `ObservationFilter`/日志脱敏，确保 trace 与日志不落明文。

> 注意：RAG 检索用原文语义检索即可；客户 PII 一律在落库与输出两道缝脱敏，知识库文档若含样例 PII 在 ingest 阶段一并脱敏。

---

## 6. 前端确认台交互（产品感）

三屏：客户聊天窗(SSE) / 坐席确认台 / 知识库上传页。确认台交互：

- **待确认卡片**：展示 tool 名（退款申请）、LLM 提议参数（orderNo / amount / reason）以 **ElementPlus 可编辑表单**呈现、依据片段（"LLM 依据：…"）、置信来源。
- **四个动作**：
  - `确认` → 原参执行；
  - `改参后确认` → 坐席改 amount/reason（`<el-input-number>`/`<el-input>`）→ PUT `/confirm/{id}` 带 `finalParams`+operator → 后端校验（amount ≤ 订单余额、reason 非空）→ 执行 → 结果 SSE 推回客户聊天；
  - `驳回` → status=rejected → LLM 接"已驳回"续答/转人工；
  - `取消` → status=cancelled。
- **实时**：pending 创建 → SSE/WebSocket 推坐席台（或 2s 轮询）；确认结果推客户窗（保持客户 SSE 通道或独立通知通道）。
- **审计条**：谁确认、何时、原参 vs 终参、执行结果 —— 面试讲"可审计 HITL"的落点。
- **超时态**：pending 过期 → 卡片置灰"已超时"，客户聊天显示超时提示。

---

## 7. 检索引用与溯源（Citation / Grounding）

**能，而且这是提升可信度的关键特性（面试讲 grounding 的落点）。**

### 7.1 实现
- **检索编号**：`QuestionAnswerAdvisor` 返回的 `List<Document>` 按 topK 顺序编号 `1..k`，写入响应 metadata。每个 `Document` 的 metadata 含 `doc_id, source, chunk_index, page, score`。
- **Prompt 约束**：system 中要求"引用所给上下文时用 `[1][2]` 标注；不得引用未提供的来源"。生成后做后处理：校验 `[n]` 是否在 `1..k`，越界标记剔除（防模型编造引用）。
- **下发**：SSE 先发 `event: citations` 携带 `[{index, docId, docTitle, source, chunkContent, page, score}]`，再发文本流（文本内含 `[n]`）。前端拿到 map 后把 `[n]` 渲染为可点击 chip。
- **点击看原文**：点 chip → `GET /kb/doc/{docId}?chunk={chunkIndex}` → 后端返回该文档全文（`knowledge_doc.parsed_text`）+ 高亮对应 chunk；前端用抽屉/弹层展示并高亮命中段落。PDF/Word 由 Tika 解析时已存 `parsed_text`，可直接展示文本（需原格式则回源对象存储）。
- **去重**：同一 doc 多 chunk 命中时合并为同一引用，扩展其高亮片段集合。

### 7.2 与事实一致性的关系
§1.1 的事实一致性让答案"不可胡编"，本节的 citation 让答案"可查"——两者互补，构成可信 RAG 的两道防线。

---

## 8. 三层上下文管理 & 结构化会话状态（核心：状态机思维）

> 修正：`MessageChatMemoryAdvisor` 只负责 Layer 1 短期记忆，**不等于**上下文管理全貌。客服 Agent 的本质是"带自然语言接口的状态机"——上下文必须分层，并以"最小必要上下文"为装配原则，而非把历史全塞进 prompt。

### 8.1 三层模型
| 层 | 职责 | 实现 | 适合 / 不适合 |
|---|---|---|---|
| L1 短期对话记忆 | 保语气、代词指代、紧接追问 | `MessageChatMemoryAdvisor`，窗口=最近 8 轮原始消息 | 适合连贯；不适合无限增长（成本/噪声） |
| L2 结构化会话状态 | 当前任务事实：身份/意图/订单/业务状态/情绪 | `SessionState`（Postgres `session_state` 表）+ `SessionStateService` | 驱动工具调用与回复稳定性；每轮增量更新 |
| L3 外部知识与业务数据 | 知识库、政策、订单/CRM/工单系统 | RAG（`QuestionAnswerAdvisor`）+ 业务 Tool | 提供"真相"；决策绑定后端数据，不靠模型猜 |

**原则**：对话历史负责理解上下文，结构化状态负责维持任务，外部系统负责提供事实依据。三者不混。

### 8.2 结构化状态 Schema（SessionState）
```json
{
  "session_id": "sess_20260409_001",
  "user_id": "u_18392",
  "intent": "refund_query",
  "order_id": "A20260409001",
  "order_status": "delivering",
  "delivery_delayed": true,
  "emotion": "negative",
  "asked_for_human": false,
  "last_policy_checked": "refund_policy_v3",
  "next_best_action": "explain_refund_rule_or_offer_manual_transfer",
  "order_status_confirmed_by_system": true
}
```
持久化：`session_state(session_id PK, tenant_id, user_id, intent, order_id, order_status, emotion, asked_for_human, state_json jsonb, version, updated_at)`，`version` 乐观锁防并发写覆盖。

### 8.3 增量提取 + 冲突消解
- **增量提取**：每轮（收消息 + 工具返回后）调用 `SessionStateService.extractState(...)`：轻量模型（qwen-turbo，低温度）+ 结构化输出（`BeanOutputConverter<SessionStateDelta>`）产出状态**增量**，而非整段摘要。好的提取必须能驱动下一步动作（如"因物流延迟产生负面情绪，若今日未送达可能升级投诉"），而非"用户咨询了订单问题"。
- **成本控制（demo 务实）**：并非每轮都调 LLM 提取。仅在 (a) 工具返回新业务数据，或 (b) 启发式命中状态信号（订单号正则、意图关键词、情绪词）时触发；否则复用既有状态。
- **冲突消解规则**（写进状态层，不靠模型自悟）：
  1. 系统数据 > 用户口述：工具查到 `order_status=delivering` 而用户说"已收到" → 置 `order_status_confirmed_by_system=false`，话术转稳妥，不直接拍板。
  2. 意图翻转：新意图**替换**旧意图（不并存）；旧诉求未明放弃却跳转 → `needs_clarification=true`，主动澄清。
  3. 多订单号：处理对象不明 → `needs_clarification=true`，问"处理刚才那个还是另一个？"。
  4. 反模式"状态只增不减"：`high_risk`/`asked_for_human` 等标签在情绪恢复或撤回诉求时**必须清除**，否则后续过度谨慎/乱转人工。

### 8.4 上下文装配模板（最小必要上下文）
`ContextAssembler` 在调主模型前统一组装 system 消息，分区清晰防混淆：
```
[系统角色] 你是电商客服助手，回答准确克制，不编造政策。
[当前会话状态] 用户ID/意图/订单号/情绪/是否需转人工 …
[最近对话] 仅最近若干轮（Layer1）
[实时业务数据] 订单状态/退款规则/物流节点（来自 L3 工具）
[回复要求] 先回应关切→明确规则边界→不确定给下一步→情绪恶化建议转人工
```
按当前任务组装：用户仅追问"多久到账"时，重点是退款状态+到账规则+最近一轮，而非整段历史。

### 8.5 处理流程（每轮）
1. 接收用户输入
2. 从 Layer1 最近对话理解当前语义（代词/追问）
3. **更新结构化会话状态**（§8.3）
4. 判断调知识库/业务系统（RAG + Tool）
5. **装配最小必要上下文**（§8.4）→ 主模型生成
6. 输出回复（SSE）；`onComplete` 持久化消息（PII 脱敏）+ 写回状态

### 8.6 状态机与主动澄清
- 意图即状态：`Intent` 枚举 + 允许转移；条件触发切人工（`emotion=negative` 且 `asked_for_human` 或连续追问/升级倾向）、重查系统（状态依赖缺失字段）、重置上下文（用户明确放弃主任务）。
- **主动澄清优于强答**：出现以下情况 Agent 应问一句而非硬答——多订单对象不明 / 意图跳转未放弃旧诉求 / 口述与系统冲突 / 政策判断缺字段。

### 8.7 与既有设计的关系
- L1 连贯 ↔ §1 `MessageChatMemoryAdvisor` + §1.2 流式边界。
- L2 状态驱动工具 ↔ §1 `ToolCallConfirmAdvisor` 读 `SessionState` 决定参数默认值/确认必要性。
- L3 事实依据 ↔ §3 RAG + §7 引用溯源 + 业务 Tool。

---

### 8.8 实现细节追问（面试兜底）

#### 8.8.1 启发式命中怎么判断（防误触发）
用 `SignalDetector`（**纯规则、无 LLM、毫秒级**）对每条用户消息打分，再决定是否调 `extractState`：
- **订单信号**：订单号正则（`ORD-\d+` / `A\d{8,}` / `订单\s*[:：]?\s*\S+`）→ 高置信，必触发提取。
- **意图信号**：意图关键词集（退款/物流/投诉…）→ 触发。
- **情绪信号（带否定窗口）**：情绪词（`生气/差评/投诉/愤怒`）前后 ±N 字内若出现否定/转折标记（`不/没/但/只是/其实`），**仅抑制该情绪信号**，不影响其他信号。如"我心情不好但订单没问题"→ 保留"心情不好"这一真实情绪，但"订单"信号被"没问题"抑制，**不会误触发订单相关提取**。
- **防过度触发**：孤立情绪词（无业务/意图信号、无风险词 `12315/黑猫/起诉/曝光`）不触发 LLM 提取，仅轻量记录，避免每次闲聊都调模型。
- **阈值 + 去重**：`signalScore ≥ 阈值` 或"工具返回新业务数据"才调提取；若信号已存在于 state 且未变，跳过。

#### 8.8.2 Token 预算保护（连续追问不爆 prompt）
- **根本机制**：L2 结构化状态已承载"任务事实"，L1 只需最近 8 轮即可连贯，**无需全量历史**——这是预算可控的前提。
- **各块预算上限**（demo 参考；qwen-plus 上下文 32k，但业务侧 cap 更低以控延迟/成本）：
  - 系统角色：固定 ~150 tokens；
  - 结构化状态：JSON ~200 tokens（天然小）；
  - 最近对话（L1）：超 8 轮只取最近，oldest-first 截断；
  - RAG 文档：**按 token 预算选**而非盲取 topK——`DocumentSelector` 累加 chunk token，达上限（如 1500）即停；
  - 实时业务数据：只取与当前 `intent` 相关字段并截断长度。
- `ContextAssembler` 装配后做**总量估算**（字符数/4 近似或轻量 tokenizer），超硬上限（如 6k）按优先级丢弃：先丢最旧对话轮 → 再丢低相关文档 → 业务数据截断。保证永不超模型上下文。

#### 8.8.3 写回状态的并发（流式途中用户又发消息）
- **主防线：同 session 串行处理**。每个 `session_id` 绑定一把锁/单线程队列（`ConcurrentHashMap<sessionId, Lock>` 或 per-session `Executor`），同一会话消息严格排队：turn N 的 SSE `onComplete` 写完状态后，turn N+1 才开始。流式途中来的新消息进队列，**不会被并发写回覆盖**；前端同步禁用输入框至助手完成（UX 辅助）。
- **纵深防御：乐观锁 + 行锁**。多实例部署时，`session_state.version` 乐观锁 `UPDATE ... SET state_json=?, version=version+1 WHERE session_id=? AND version=?`，受影响行=0 即冲突→重读最新状态合并重试；turn 开始时对 `session_state` 行 `SELECT ... FOR UPDATE` 抢占，防跨节点并发。
- 结果：流式写回与新的用户消息在状态层不相互覆盖，顺序与对话一致。

---

## 9. 文档 Ingestion 与检索增强（企业级 RAG 流水线细节）

> 对应里程碑 **M2（入库）与 M3/M5（检索）**。原 §2 仅给出 Tika→切块→embedding 主链，本节补充"脏数据清洗 / 复杂排版 / 多模态 / 柔性分块 / 重排精排"五处企业级细节。

### 9.1 非结构化文档清洗（DocumentCleaner）
Tika `AutoDetectParser` 产出的是"原始线性文本"，含大量噪声，必须后处理：
1. **归一化**：Unicode `NFKC`、去零宽/控制字符（`\u200b`、`\u00a0`）、去 BOM；按 CJK 断句规则规范化空白与换行（句内单换行合并、段间双换行保留）。
2. **页眉页脚去除**：统计每页顶部/底部出现频率 >80% 的相同片段（如"某电商售后政策 V3"、页码）→ 判定为 boilerplate 剔除。
3. **目录/书签残骸**：识别"标题.....12"点线+页码模式并删除；孤立页码行删除。
4. **近重复去重**：段落级 `SimHash`/`MinHash`，重复条款（如多页相同的免责声明）只留一份。
5. **编码与语种校验**：读 Tika metadata 的 encoding；校验中文占比；**若提取文本长度 / 文件体积 低于阈值 → 判定扫描件，转入 OCR 兜底**（Tesseract / PaddleOCR）。
6. **质量评分**：清洗后算 `clean_score`（噪声比、空行比、乱码比）；低于阈值 → **隔离待人工复核**，不直接入库。
7. **PII 预扫**：知识库样例 PII 在 ingest 阶段即 `mask()`（呼应 §5）。

### 9.2 复杂排版解析（Layout-aware Parser，真接 OCR）
默认 Tika `BodyContentHandler` 会压平多栏/表格/脚注，丢结构。**已确认真接 layout 解析 + OCR，扫描件必须可跑通**：
- **主解析器 MinerU**（上海 AI Lab）：基于布局检测 + 内置 OCR（PaddleOCR），输出带 `bounding box` 与 `block_type`（title/section/text/table/figure/list/footnote）的区块，并给出**阅读顺序**（多栏按列聚类后从上到下、栏内从左到右）；扫描页自动走 OCR 还原文本。该能力由独立 **Python `doc-processor` 子项目**提供（§10），可本机进程运行，亦可容器化上服务器。
- **OCR 引擎兜底 PaddleOCR**：当 MinerU 不可用，纯扫描件经 PaddleOCR 抽取文本后并入 layout 区块。
- **Tika 仍作轻量兜底**：纯文本/Word/HTML 走 Tika `XHTMLContentHandler` 保留 `<h1>/<ul>/<table>` 语义标签。
- **路由策略**：所有 PDF 先过 MinerU；解析后 `clean_score` 仍低（OCR 失败/图片模糊）→ 隔离复核并告警，不静默入库。
- **语义标签落 metadata**：`block_type` 写入 chunk metadata，供分块与检索加权。

### 9.3 多模态图表/表格提取（真接 Qwen-VL）
- **纯图表（无文字）**：截取图像区域 → 调 **Qwen-VL（`qwen-vl-max` / `qwen2.5-vl-72b`，经 DashScope OpenAI 兼容视觉接口）** 生成文字摘要（"图：2024 各品类退款率，电子类 12% 最高"）。Spring AI 1.0 用 `UserMessage` + `Media`(image) 多模态消息调用，封装为 `MultimodalCaptionService`。摘要作为文本 chunk 入库，原图存**本地资产目录**（`file.assets.dir`，MinIO 由本地文件系统替代，零依赖），metadata 记 `asset_url` 与 `block_type=figure`。
- **纯数字表格**：表格检测 → 导出 **Markdown/CSV** 保留单元格结构（不压平）；作为 `block_type=table` 的**原子 chunk**。
- **触发范围**：仅对检测到的 figure/table 区域调用 VLM，不对整文档跑，控成本（VLM 调用走批量/限流）。
- **向量化**：图表 embed 其摘要文本；表格 embed 其 Markdown/CSV 文本。
- **降级**：Qwen-VL 不可用时，图表退化为"图片引用 + OCR 文本"（若有），metadata 标 `caption_fallback=true`，不阻断主流程。

### 9.4 柔性分块策略（Structure-aware Chunking）
摒弃固定 500 token 刚性切分，改为**结构感知 + 弹性窗口**：
- **语义边界优先**：CJK 感知分隔符（。/；/换行/标题）递归切分；chunk 目标区间 **400–800 token**，允许在语义单元处**溢出**以避免切断句子/条款。
- **Token 估算**：中文用 `HanLP`/jieba 分词或 `length/1.5` 近似（tiktoken 对 CJK 不准）；chunk metadata 记 `token_count`。
- **原子块保护**：`block_type ∈ {table, figure, key_data}` 标记 `atomic=true` → **内部绝不切分**；超 800 token 的宽表作为独立宽 chunk 提升优先级或"摘要+原文链接"双存。
- **层级分块（parent-document）**：同时存"节(parent, 大上下文)"与"块(child, 用于 embedding)"；检索返回 child 但可回扩 parent，解决"固定 500 丢上下文"。
- **重叠**：相邻 chunk 10–15% 重叠保跨边界语义。
- **实现**：`StructureAwareChunker` 消费 §9.2 的布局区块，按 `block_type` 决策：
  - heading → 新 chunk 起点（heading 作为 chunk 标题）；
  - paragraph → 累计至 ~600 token 后在句边界 flush；
  - table/figure → 整块原子；
  - list → 整体 < 上限则合，否则按条目边界切。
- metadata：`doc_id, chunk_index, block_type, heading_path, token_count, atomic, page`。

### 9.5 召回检索与重排精排（两阶段 + 精排）
- **Stage 1 稠密召回**：PgVector 余弦检索，先经 §3 `QueryRewriteAdvisor` 改写 query；`topK=20` 宽召回（非直接 4）。
- **Stage 2 重排（Cross-encoder Reranker）**：对 top-20 用 **reranker** 重打分，远优于 bi-encoder 余弦：
  - 云端：通义 `gte-rerank` / Cohere / Jina；
  - 本地：**`bge-reranker-v2-m3`**（多语、中文强）由独立 **Python `doc-processor` 子项目**提供（§10，FastAPI + BAAI `FlagEmbedding`），可本机进程运行或容器化；Spring Boot 经 HTTP 调用重排 top-20。**已确认采用本地方案**：数据不出域、零调用成本。
- **Stage 3 精排（Fine-rank）**：
  - **MMR**（最大边际相关）去冗余，避免返回同一政策的 4 个近似 chunk；
  - **metadata 加权**：`block_type=table`/`atomic` 或匹配 L2 `last_policy_checked` 的 chunk 轻微提权（呼应 §8）；
  - **版本/时效**：同主题取最新版本；
  - **Token 预算选优**（§8.8.2）：rerank 后按分数取 top-N，累加至上限（~1500）停。
- **混合检索（BM25 + 向量）**：SKU/订单号/政策编码等精确串用 BM25（`pg_search`）补稠密召回盲区，RFF（Reciprocal Rank Fusion）融合。
- **降级**：reranker 不可用时，以 score 阈值 + MMR 作轻量精排，并在架构文档标注"生产必补 cross-encoder reranker"。
- 延迟：仅对 top-20 重排，开销小，可异步。

### 9.6 流水线总览
```
上传 → MinerU(layout+OCR, §9.2) → 清洗(§9.1) → [图表/表格→Qwen-VL/OCR(§9.3)]
     → 柔性分块(§9.4, atomic保护+层级) → 通义embedding(1024) → PgVector(tenant过滤)
提问 → QueryRewrite(§3) → 稠密召回top20 + BM25(§9.5) → Rerank(rerank-service: bge-reranker-v2-m3) → MMR/加权/预算选优 → 注入Prompt(§1)
```
**本地依赖子项目**：**`doc-processor`（Python 独立工程，§10）** 提供 layout+OCR / 清洗 / 重排三类核心处理，本机进程运行（连 `localhost:8000`），自带 `Dockerfile` 可容器化；`postgres-pgvector` 本机（`D:\PostgreSQL`）；多模态原图存本地资产目录（MinIO 由本地文件替代）。Qwen-VL 走 DashScope 公有云视觉接口（仅传图表截图，不含业务 PII）。

---

## 10. Python 文档处理子项目（`doc-processor/`）

> 设计目标（用户明确）：当前本地环境建一个 **Python 独立工程**，专责 **OCR 文字识别、文本重排（rerank）、数据清洗** 等核心处理；Spring Boot 通过**清晰接口**调用，两项目**解耦合理、便于扩展与维护**；同时**保留 Docker 兼容性**作为未来服务器部署方向。

### 10.1 职责边界（与 Java 解耦）
| 能力 | 归属 | 说明 |
|---|---|---|
| 文档 layout 解析 + OCR | **Python `doc-processor`** | MinerU（内置 PaddleOCR）输出带 `block_type`+阅读顺序的区块 |
| 非结构化清洗 §9.1 | **Python `doc-processor`** | `DocumentCleaner`：NFKC / 页眉页脚剔除 / SimHash 去重 / 质量评分 / PII 预扫 |
| 文本重排（rerank）§9.5 | **Python `doc-processor`** | `bge-reranker-v2-m3`（FlagEmbedding）对 query+documents 重排序 |
| 多模态图表摘要 §9.3 | 可放 Python 或 Java 直调 DashScope | 建议放 `doc-processor` 统一管 OCR/VLM 资产，Java 只收摘要文本 |
| 柔性分块 §9.4 | **Java** | 消费 Python 返回的区块做结构感知切块（Java 控 metadata 与入库） |
| embedding / 入库 / 检索 / 对话 / 工具 / 护栏 | **Java** | 不跨进程 |

> 原则：**重计算 + 模型推理**下沉 Python，**业务编排 + 检索 + 对话**留 Java。接口只传"结构化文本/区块/重排序"，不传模型内部状态。

### 10.2 接口契约（REST，版本化 `/api/v1`）
所有响应统一 `{ "ok": bool, "data": ..., "error": ... }`；超时 Java 侧设 30s（OCR/rerank 可能慢）。
- `POST /api/v1/parse` — 上传文件（multipart）→ 返回清洗后区块
  - 入：`file`, `options:{ocr:true, clean:true}`
  - 出：`{ blocks:[{block_type, text, bbox, page, reading_order, token_count}], clean_score, flags }`
- `POST /api/v1/clean` — 纯文本清洗
  - 入：`{ text }` → 出：`{ cleaned_text, clean_score, removed_flags:[...] }`
- `POST /api/v1/rerank` — 重排
  - 入：`{ query, documents:[{id, text}], top_n }`
  - 出：`{ ranked:[{id, score}] }`（按 score 降序）
- `POST /api/v1/caption` — 图表摘要（可选）
  - 入：`{ image_base64 或 image_url, prompt? }` → 出：`{ caption }`（经 Qwen-VL）

### 10.3 工程结构（建议）
```
doc-processor/
├─ Dockerfile              # 未来容器化：python:3.11-slim + torch + paddle + FlagEmbedding，构建步用 `uv sync`
├─ pyproject.toml          # 依赖与脚本声明（替代 requirements.txt），用 **uv** 管理环境与锁文件
├─ uv.lock                 # uv 生成的锁定文件，可复现构建
├─ .python-version         # 固定 Python 版本（如 3.11）
├─ app/
│  ├─ main.py              # FastAPI 入口，挂载 4 个路由
│  ├─ routers/parse.py     # MinerU + DocumentCleaner 编排
│  ├─ routers/clean.py     # 清洗链
│  ├─ routers/rerank.py    # bge-reranker-v2-m3 懒加载单例
│  ├─ routers/caption.py   # Qwen-VL 调用（DashScope）
│  └─ models.py            # Pydantic 请求/响应模型
└─ tests/                  # 接口契约测试（与 Java 侧 EvalRunner 解耦可独立跑）
```
- **模型懒加载**：`bge-reranker-v2-m3` / MinerU 在首次请求时加载并缓存单例，避免每次冷启。
- **降级**：Python 子项目不可达时，Java 回退 Tika 纯文本 + 进程内 BM25+MMR（§9.5 降级），**不阻断主流程**；`doc-processor.url` 不可达即触发降级并记录告警。

### 10.4 Docker 兼容性（未来，不阻断当前）
- `doc-processor/Dockerfile` 已随工程提供；`docker build -t doc-processor .` 即服务。
- 未来 Spring Boot 亦可加 `Dockerfile` + `docker-compose.yml`（含 `app` + `doc-processor` + `postgres-pgvector`），一条命令起整套。
- **当前开发期**：两者均本机原生（`doc-processor` 用 `uv run uvicorn app.main:app`，Java 连 `localhost:8000`），无需 Docker 即可全链路联调；依赖管理与锁文件统一走 `uv`（Astral），比 venv+pip 更快、可复现。

### 10.5 解耦要点（面试加分）
- **契约驱动**：REST + Pydantic 模型即接口文档；Java 侧用 `RestClient` 调，URL 可配（dev/prod 切换）。
- **故障隔离**：Python 子项目崩了不影响 Java 对话主链路（降级到本地轻量处理）。
- **水平扩展**：未来高并发只需扩 `doc-processor` 实例（无状态、可多副本），Java 侧加负载均衡即可。

---

## 附：更新后的构建顺序
1. 脚手架（Spring Boot + Spring AI + docker-compose pgvector + 通义/DeepSeek model bean + PgVectorStore）
2. RAG 链路（Tika → 切块 → 通义 embedding → 入库 → QuestionAnswerAdvisor）
3. Advisor 链显式化（Memory → QueryRewrite → RAG → ToolConfirm → OutputGuardrail → Observation）
4. 记忆 + SSE 流式
5. 确认护栏（待确认/改参/驳回/取消 + 超时 Reaper + 幂等键 + 双执行锁 + 结果回灌）
6. 前端三屏（聊天 SSE + 确认台 + KB 上传）
7. PII 双重脱敏 + 评估集 EvalRunner
8. 多租户 tenant_id 接缝 + README（面试讲设计）
9. 检索引用溯源（citation 标记 + 点击查看原文抽屉）
10. 三层上下文 / 结构化会话状态（SessionStateService 增量提取 + 冲突消解 + ContextAssembler 装配模板 + 状态机与主动澄清）
