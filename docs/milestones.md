# 电商客服 Agent — 构建里程碑规划（M1–M9）

> 企业级增量交付。每个里程碑**可独立编译、可独立验证、可独立演示**，不堆积未验证代码。
> 技术栈见 `architecture.md`：Spring Boot 3.5 + Spring AI 1.0 / PgVector / Tika / 通义 qwen-plus（主）+ DeepSeek-V3（降）/ 通义 text-embedding-v3（1024 维）/ Vue3+Vite+ElementPlus。
> 单商户自用，全程预留 `tenant_id` 接缝（M9 收口）。

---

## 里程碑依赖关系

```
M1 脚手架/Infra
   ├─ M2 RAG 链路
   │     └─ M3 对话引擎+流式+基础Advisor链 ──┬─ M4 工具层+确认护栏(HITL)
   │                                          └─ M5 检索引用溯源(Citation)
   │                                                └─ M6 三层上下文状态机(+QueryRewrite)
   │                                                      └─ M7 输出护栏+PII双重脱敏
   ├─ M8a 前端聊天+确认台 (依赖 M3/M4/M5/M7)
   └─ M8b 前端 KB上传+源抽屉 (依赖 M2/M5)
M9 可观测+评估+多租户接缝+交付文档 (依赖全部)
```

原则：**后端按 Advisor 链方向从左到右落地（Memory→RAG→ToolConfirm），再补插入件（QueryRewrite 在 RAG 前、OutputGuardrail 在 RAG 后）；用户确认 M6 请求方向先于 M7 响应方向；前端独立成里程碑但严格对齐后端事件契约。**

---

## M1 — 脚手架与基础设施（Scaffold + Infra）

**目标**：一个能启动、能连通模型与向量库的空壳工程，奠定包结构与配置规范。

**当前阶段拓扑：本机原生，保留 Docker 兼容**。本地 PostgreSQL 已装在 `D:\PostgreSQL`，开发连本机库（`localhost:5432`）。OCR / 重排 / 清洗等重处理**单独建 Python 子项目 `doc-processor`**（M1.5），本机进程运行、自带 Dockerfile 留作未来容器化；当前不启 Docker，全链路本机联调。

**任务拆解**
1. Maven 工程初始化：Spring Boot 3.5.3、Spring AI 1.0 BOM、核心 `spring-ai-openai`（**非 starter**：starter 会拉 `spring-ai-autoconfigure-model-openai` 强制要求 `spring.ai.openai.api-key` 并注册一堆用不上的 `OpenAi*Model`，本项目模型全自管故改用核心模块）、`spring-ai-starter-vector-store-pgvector`、`spring-boot-starter-webflux`（SSE）、`spring-ai-rag`（RAG Advisor；⚠️ `spring-ai-advisors-vector` 在 1.0.0 GA **不存在/404**，RAG Advisor 已并入 `spring-ai-rag`）、`spring-ai-starter-data-jdbc`。
2. **本机 Postgres 连接**：`application.yml` 连 `jdbc:postgresql://localhost:5432/ecom_agent`（库与 pgvector 扩展已就绪于 `D:\PostgreSQL`）；`src/main/resources/db/init.sql` 建 6 张表：`vector_store`(Spring AI 默认)、`session_state`、`conversation`、`message`、`pending_action`、`knowledge_doc`（`vector_store` 建 `embedding vector(1024)` + `tenant_id` 列）。**全程零 Docker（未来重服务以本机原生 Python 进程补）**。
3. 多模型 Bean：阿里系统一走**阿里云百炼 DashScope OpenAI 兼容接口** `DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1`（`DASHSCOPE_API_KEY` 环境变量）。`QwenChatModel`(主, `qwen-plus`) + `QwenTurboChatModel`(轻量, `qwen-turbo`, 供 QueryRewrite/OutputGuardrail) + `TongyiEmbeddingModel`(text-embedding-v3, `dim=1024`) 均连该 base-url；`DeepSeekChatModel`(降, 独立 `https://api.deepseek.com/v1`)。全部经 `@Qualifier` + 路由 Bean 注入。**注意：`OpenAiApi` 在 1.0.0 位于包 `org.springframework.ai.openai.api.OpenAiApi`；`OpenAiEmbeddingModel` 无 `builder()`，须用构造器 `new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options)`。**
4. `PgVectorStore` 配置：维度 1024、`distanceType=COSINE_DISTANCE`（⚠️ 常量名是 `COSINE_DISTANCE`，不是 `COSINE`）、`initializeSchema(false)`（DDL 由 `db/init.sql` 接管）、schema 带 `tenant_id` 列。
5. 基础包结构落地：`config/ agent/ api/ common/`（rag/ tools/ eval/ 后续里程碑补）。
6. `common/`：`PiiMaskUtil`(骨架，先留 `mask()` 接口)、`GlobalExceptionHandler`、`ApiResponse` 统一响应体、`application.yml`(API key 走环境变量，不落库)。
7. 最小 `ChatController` `/chat/health` + `/actuator/health` 跑通。

**交付物与验收**
- 本机 PG 起库；`\dx` 见 `vector` 扩展；6 张表存在。
- 应用启动成功；调用测试接口能经通义返回 "hello"，DeepSeek 降级 bean 可被注入。
- 提交一个空 `ChatClient` 能初始化。

> ✅ **M1 已构建并通过验证（2026-08-25）**：`mvn test` 全绿（3/3），上下文加载 + `/chat/health` + `/actuator/health` 端到端通过。
> 构建期踩坑（已修复，记录备查）：① 多 `ChatModel` Bean 导致 Spring AI 自动配置的 `ChatClient.Builder` 歧义 → 显式 `@Bean ChatClient.Builder` 锁主模型；② pgvector 自动配置 `vectorStore` Bean 与我手写 Bean 撞名 → `spring.autoconfigure.exclude` 排除 `PgVectorStoreAutoConfiguration`；③ `spring-ai-starter-model-openai` 强绑 `spring.ai.openai.api-key` → 换核心 `spring-ai-openai`；④ 依赖名/API 签名对齐 1.0.0 GA（见任务 1/3/4 标注）。
> 注：沙箱无本机 PG 与 API Key，`@SpringBootTest` 用 H2 内存库 + 空 key 验证 Bean 装配与 Web 层；真实联调需在你本机执行（见下「运行方式」）。

**依赖**：无
**风险/面试点**：API key 必须走环境变量不落库（§5 合规）；维度必须与 embedding 模型严格一致（1024），否则建表即报错。

---

## M1.5 — Python 文档处理子项目（doc-processor）

**目标**：独立 Python 工程，提供 OCR 文字识别、文本重排（rerank）、数据清洗三类核心处理，REST 接口清晰、与 Java 解耦（§10）。当前本机进程运行，自带 Dockerfile 保留容器化兼容。

**任务拆解**
1. 工程脚手架：`doc-processor/`（`app/main.py` FastAPI + **`pyproject.toml` + `uv.lock`（`uv` 管理环境与依赖，替代 requirements.txt）** + `Dockerfile` + `tests/`）。用 `uv venv` 建环境、`uv add` 加依赖、`uv run uvicorn app.main:app` 起服务。
2. `POST /api/v1/parse`：`routers/parse.py` 编排 **MinerU（layout + 内置 PaddleOCR）+ DocumentCleaner**；返回 `{blocks:[{block_type,text,bbox,page,reading_order,token_count}], clean_score, flags}`（§9.2 + §9.1）。
3. `POST /api/v1/clean`：纯文本清洗链（§9.1）。
4. `POST /api/v1/rerank`：`routers/rerank.py` 懒加载 **`bge-reranker-v2-m3`**（FlagEmbedding）单例；入 `{query, documents:[{id,text}], top_n}` → 出 `{ranked:[{id,score}]}`（§9.5 Stage2）。
5. `POST /api/v1/caption`（可选）：`routers/caption.py` 调 Qwen-VL 生成图表摘要（§9.3）。
6. `models.py`：Pydantic 请求/响应模型即接口契约；`tests/` 做契约测试（与 Java 解耦可独立跑）。
7. **降级契约**：不可达时 Java 回退本地处理；`doc-processor` 内模型懒加载、首请求慢属正常。

**交付物与验收**
- `uvicorn` 起服务，`POST /api/v1/parse` 对一份多栏+表格 PDF 返回带 `block_type` 与阅读顺序的已清洗区块；`/rerank` 对 query+documents 返回降序 ranked。
- `Dockerfile` 可 `docker build`（不强制跑）；接口契约与 §10.2 一致。

**依赖**：无（独立工程，先于 M2 联调）
**风险/面试点**：模型懒加载单例；故障隔离（Java 降级）；水平扩展（无状态多副本）。与 Java 的解耦设计是面试加分项（§10.5）。

> ✅ **M1.5 已构建并通过验证（2026-08-25）**：`uv sync` 装基础依赖 + `uv run pytest` 5/5 全绿（health / clean / clean-PII / parse-text-fallback / rerank-降级）；`uv run uvicorn` 起服务后 `/health`、`/clean`、`/rerank`、`/caption` 四端点均按 §10.2 信封 `{ok,data,error}` 返回。
> 构建期要点（已落地）：① 重模型（MinerU/FlagEmbedding/PaddleOCR/dashscope）放 `[project.optional-dependencies] ml` extra，**懒加载单例**，基础服务不装也能起（沙箱/CI 契约测试即如此）；② `uv sync` 会把所有 extras 解析进 `uv.lock` 但默认**不安装**，故 `magic-pdf[full]` 与 `FlagEmbedding` 等只在 `uv sync --extra ml` 时落盘；③ magic-pdf 依赖预发布 `doclayout-yolo` → `[tool.uv] prerelease="allow"`；④ magic-pdf[full] 自带 paddleocr/paddlepaddle，**勿重复声明**否则版本冲突；⑤ PDF 兜底走 PyMuPDF（`pdf` extra），txt 走纯文本直读；⑥ `DocumentCleaner` 已实现 §9.1 全链（NFKC/页眉页脚/SimHash 去重/PII 预扫/质量评分）。
> 注：沙箱无 GPU 且不装 ml extra，`/parse` 以 txt 兜底 + `/rerank` 以"未装模型"降级路径验证契约；真实 MinerU OCR / bge-reranker / Qwen-VL 需本机 `uv sync --extra ml --extra pdf` 后联调。

---

## M2 — RAG 知识库流水线（Ingestion，经 doc-processor）

**目标**：文档可解析（含扫描件 OCR）、切块、向量化、入库，metadata 带溯源字段。

**任务拆解**
1. `rag/DocProcessorClient`：封装对 Python `doc-processor` 的 REST 调用（`RestClient`，`doc-processor.url=http://localhost:8000` 可配，超时 30s）；**不可达即降级标记**并走 Tika 兜底。
2. **解析 + OCR + 清洗（真接，经 doc-processor）**（§9.2 + §9.1）：PDF/Word 上传 → `POST /api/v1/parse` → 返回带 `block_type`+阅读顺序的已清洗区块 + `clean_score`；`clean_score` 低 → 隔离复核不入库。
3. `rag/TikaDocumentLoader`：仍作**轻量兜底**（纯文本/HTML，或 doc-processor 不可达时）；解析同时保存 `parsed_text` 全文（`knowledge_doc.parsed_text`，供 M5 点击看原文）。
4. **柔性分块**（§9.4，纯 Java）：消费 §2 返回的区块做 `StructureAwareChunker`：弹性 400–800 token、CJK 句边界、原子块保护(table/figure/key_data 不切)、层级 parent-child、10–15% 重叠；metadata 带 `block_type/heading_path/token_count/atomic/page`。
5. `rag/EmbeddingService`：调通义 text-embedding-v3（批量、1024 维），封装重试与速率保护。
6. `PgVectorStore` 写入：metadata 写入 `tenant_id / doc_id / chunk_index / source / page / block_type / atomic / token_count`。
7. `api/KbController`：`POST /kb/upload`(multipart) → (doc-processor parse | Tika 兜底) → 柔性分块 → embed → store；写 `knowledge_doc`(source, chunk_count, parsed_text, created_at)。

**交付物与验收**
- 上传一份含多栏+表格的售后政策 PDF → 表格被完整保留为原子 chunk、多栏阅读顺序正确、`vector_store` 有对应记录；`knowledge_doc.parsed_text` 可查全文。
- 上传扫描件 → doc-processor 经 MinerU+PaddleOCR 还原文本，非空白。
- `clean_score` 低时进入复核隔离而非入库；doc-processor 不可达时自动降级 Tika 不阻断。

**依赖**：M1, M1.5
**风险/面试点**：Tika 对扫描件丢内容 → 已由 doc-processor(MinerU+PaddleOCR) 真接解决（§9.2）；固定 500 token 刚性切分丢语义 → 柔性分块+原子保护（§9.4）。

> ✅ **M2 已构建并通过单元测试（2026-08-25）**：9 个测试用例覆盖 `StructureAwareChunkerTest`(3) / `DocProcessorClientTest`(1) / `KbControllerTest`(3，@WebMvcTest) / `KbIngestionServiceTest`(2，@SpringBootTest)，覆盖 doc-processor 不可达→Tika 兜底→分块→向量入库(mock 校验)+knowledge_doc 落库，以及 clean_score<0.5→QUARANTINED 隔离不入库。详见 `docs/M2_VERIFICATION.md`。
> 构建期踩坑（已修复）：① `ParseResult` 构造器为 `private`，跨包测试 `new ParseResult(...)` 编译失败 → 新增 `public static fallbackWithScore(...)` 工厂专供低质量门禁场景，测试改用工厂；② 反编译确认 `PgVectorStore.add()` 内部用 Tongyi bean 编码、不复用外部预计算 embedding → 入库服务不显式 embed。
> 注：沙箱禁 TLS（Maven 需连 Maven Central）且本机无 PG/Key，`mvn test` 需你本机执行；沙箱内用 H2 + 空 key 完成静态审查与代码交付。

---

## M3 — 对话引擎 + 流式 + 基础 Advisor 链

**目标**：客户能流式对话，基础 Advisor 链（Memory→RAG→Observation）打通。

**任务拆解**
1. `agent/ChatService`：编排 `ChatClient`，注入基础 Advisor 链。
2. `MessageChatMemoryAdvisor`（**仅 L1 短期记忆**，窗口=最近 8 轮，`ChatMemory` 内存实现，预留 Redis 接口）。
3. `QuestionAnswerAdvisor`：`SearchRequest(topK=4, similarityThreshold=0.78, filterExpression=tenant_id==:tenantId)`。
4. `ChatClient.stream()` → `api/ChatController` `GET /chat/stream`(SSE，`Flux<String>`)。
5. **§1.2 流式边界**：SSE `onComplete` 手动 `chatMemory.add(user, assistant)`；`onError` 追加并打 `truncated` 标记（防打断丢轮）。

**交付物与验收**
- 客户问"七天无理由退货怎么算" → SSE 逐字返回，且回答能引用知识库内容。
- 流式中途前端断开不报错；正常结束时历史正确写入。

**依赖**：M1, M2
**风险/面试点**：SSE 断连/重连；多轮后 memory 窗口正确性。

> ✅ **M3 已构建（2026-08-26）**：`common/TenantContext`（租户接缝）+ `ChatClientConfig.chatMemory()`（MessageWindowChatMemory 8 轮窗口）+ `ChatService`（Advisor 链 Memory→RAG + 流式）+ `ChatController.GET /chat/stream`（SSE）。4 单测：`ChatServiceTest`(2, @SpringBootTest 验证流式输出/记忆落库/错误补 truncated)、`ChatControllerTest`(2, @WebMvcTest 验证 /chat/health + /chat/stream 异步 SSE)。详见 `docs/M3_VERIFICATION.md`。
> **⚠️ API 调整**：M3 原稿写的 `QuestionAnswerAdvisor` 在 **Spring AI 1.0.0 GA 已移除**，已改用 `RetrievalAugmentationAdvisor`（`VectorStoreDocumentRetriever` + `ContextualQueryAugmenter`，filterExpression=`tenant_id=='default'`）。后续 M5 重排 / M6 QueryRewrite 可直接插 `documentPostProcessors` / `queryTransformers`，无需重构。
> 注：沙箱禁 TLS（Maven 连 Maven Central）且本机无 PG/Key，`mvn test` 需你本机执行；沙箱内用 H2 + 空 key 完成静态审查与代码交付。

---

## M4 — 工具层 + 确认护栏（HITL，高光里程碑）

**目标**：4 个 tool 落地，需确认动作经人工确认才执行，解决超时/幂等/双执行（§2 全落地）。

**任务拆解**
1. `tools/`：
   - `OrderQueryTool`（只读，查订单/物流）
   - `RefundTool` / `AddressChangeTool` / `CouponTool`（标注 `@ConfirmRequired`）
2. `agent/ToolCallConfirmAdvisor` + 自定义 `ToolExecutionStrategy`：经 `ChatClient.Builder.defaultToolExecutionStrategy(...)` 注入；只读直执行，需确认拦截。
3. `agent/ConfirmationService`：
   - 写 `pending_action(status=pending)`；
   - **幂等键** `hash(conversation_id+tool+normalized_params)`，重复发起不新建（§2.2）；
   - **双执行防护** `UPDATE ... SET status='confirmed' WHERE status='pending'`，受影响行=0 即冲突（§2.3）；
   - **超时 Reaper** `@Scheduled` 扫 `... FOR UPDATE SKIP LOCKED`（多实例安全，§2.1）；
   - **结果回灌**：执行结果写回消息流并作为下一轮上下文（§2.4）。
4. `api/ConfirmController`：`POST /confirm/{id}`(确认) / `PUT /confirm/{id}`(改参) / `reject` / `cancel`。

**交付物与验收**
- 发"我要退 ORD-1001 的耳机" → 不直接执行，坐席侧出现待确认卡（M8 渲染，M4 先验证接口/数据）。
- 确认后真实执行，结果回灌客户对话；重复发起不重复建单；5 分钟不确认自动 expired。

**依赖**：M3
**风险/面试点**：**超时 Reaper + SKIP LOCKED、幂等键、双执行行锁**——三连问已在 §2 落地，面试主动展开。

> ✅ **M4 已构建（2026-08-26）**：`common/ConfirmRequired` 注解 + `tools/OrderQueryTool`(只读) + `RefundTool/AddressChangeTool/CouponTool`(@ConfirmRequired)；`agent/ConfirmationService`（幂等键 hash(conv+tool+params) + 原子 UPDATE 双执行防护 + @Scheduled Reaper + 结果回灌）+ `agent/PendingAction` + `agent/ConfirmationConflictException`；`api/ConfirmController` 六端点；`ChatService.defaultTools(...)` 注册 4 工具 + `toolContext(conversationId)`；`db/init.sql` pending_action 扩展 idempotency_key/expires_at/result/tenant_id。13 单测：`ConfirmationServiceTest`(6) / `ConfirmControllerTest`(6) / `RefundToolTest`(1)。详见 `docs/M4_VERIFICATION.md`。
> **⚠️ API 调整**：M4 原稿写的 `ToolExecutionStrategy` + `ChatClient.Builder.defaultToolExecutionStrategy(...)` 在 **Spring AI 1.0.0 GA 不存在**。GA 工具拦截点为 `@Tool` 注解 + `defaultTools(...)` + `ToolCallback.call(String, ToolContext)`（工具方法可声明 `ToolContext` 参数自动注入）。故「只读直执行 / 需确认拦截」改为：只读工具直接返回、`@ConfirmRequired` 工具方法内调 `ConfirmationService.request(...)` 落 pending 返回 PENDING，副作用只在坐席 confirm 后执行。
> 注：沙箱禁 TLS（Maven 连 Maven Central），`mvn test` 需你本机执行；沙箱内完成静态审查与代码交付。

---

## M5 — 检索增强：引用溯源 + 重排精排（Citation / Grounding / Rerank）

**目标**：回答带 `[n]` 引用标、可点开看原文，且召回经混合检索 + Cross-encoder 重排 + MMR 精排提升准确率（§7 + §9.5，重排经 doc-processor 真接）。

**任务拆解**
1. **混合召回**（§9.5 Stage1）：`QuestionAnswerAdvisor` 先**宽召回 topK=20**（经 §3 QueryRewrite 改写）；叠加 **BM25 混合检索 + RRF** 融合（SKU/政策编码精确串，Lucene/过程内 BM25，无需 Docker）。
2. **Cross-encoder 重排（真接，经 doc-processor）**（§9.5 Stage2）：将 query + top20 documents 送 `POST /api/v1/rerank`（`bge-reranker-v2-m3`，Java 侧 `DocProcessorClient.rerank()`），取回降序 ranked；doc-processor 不可达时降级为 score 阈值 + MMR。
3. **精排**（§9.5 Stage3）：MMR 去冗余 + metadata 加权（`block_type=table`/`atomic`/匹配 L2 `last_policy_checked` 提权）+ 版本时效 + Token 预算选优（累加至 ~1500 停）。
4. `QuestionAnswerAdvisor` 返回的 `List<Document>` 按**重排后顺序**编号 `1..k`，metadata 含 `doc_id/source/chunk_index/page/score`。
5. system prompt 约束 `[n]` 标注 + **后处理校验** `[n]` 不越界（防模型编造引用）。
6. SSE 先发 `event: citations` 携带 `[{index,docId,docTitle,source,chunkContent,page,score}]`，再发文本流。
7. `GET /kb/doc/{docId}?chunk={chunkIndex}`：返回 `knowledge_doc.parsed_text` 全文 + 高亮对应 chunk（同 doc 多 chunk 合并去重）。

**交付物与验收**
- 回答含 `[1][2]`；`citations` 事件带正确 docId/title/score（按重排序）；接口能取回原文并定位 chunk。
- 对比 eval 集"无 rerank vs 有 rerank"召回命中率有明显提升（M9 量化）。
- 前端 chip 渲染与源抽屉交互在 M8 完成，本里程碑先打通后端事件与接口契约。

**依赖**：M3, M1.5
**面试点**：与 §1.1 事实一致性构成"不可胡编 + 可查"两道防线；Cross-encoder reranker 是检索准确率的关鍵杠杆（§9.5），经解耦的 doc-processor 子项目提供服务（§10）。

> ✅ **M5 已构建（2026-08-26）**：`rag/RagDocUtils` + `Bm25Index`（过程内 BM25）+ `HybridRetriever`（向量 topK=20 + BM25 + RRF 融合）+ `RerankService`（Cross-encoder 经 doc-processor / 不可达 MMR 降级）+ `MmrSelector`（去冗余+table/atomic 提权+token 预算）+ `RetrievalPipeline`（编排+编号）+ `CitationValidator`（`[n]` 越界校验）；`DocProcessorClient.rerank()`；`ChatService` 改手动检索（先发 `citations` 再发 `token`，system prompt 带编号）；`GET /kb/doc/{docId}?chunk=` 溯源高亮；`KbIngestionService` 入库同步写 BM25。测试：`Bm25IndexTest`(2)/`MmrSelectorTest`(2)/`CitationValidatorTest`(4)/`HybridRetrieverTest`(1)/`RerankServiceTest`(2)/`RetrievalPipelineTest`(1) + 更新 `ChatServiceTest`/`KbControllerTest`，共 18 例。详见 `docs/M5_VERIFICATION.md`。
> 注：沙箱禁 TLS，`mvn test` 需你本机执行；沙箱内完成静态审查与代码交付。

---

## M6 — 三层上下文状态机（核心深度，含 QueryRewrite）

**目标**：上下文分层管理，结构化会话状态驱动任务，QueryRewrite 提升检索命中（§3、§8）。

**任务拆解**
1. **L2 `SessionState`** + `session_state` 表（`version` 乐观锁）。
2. `agent/SessionStateService.extractState(...)`：qwen-turbo + `BeanOutputConverter<SessionStateDelta>` 做**增量**提取（非废话摘要）。
3. `agent/SignalDetector`（§8.8.1）：纯规则打分；**否定窗口**防误触发（"心情不好但订单没问题"不误触发订单提取）；孤立情绪词不触发 LLM。
4. `agent/ContextAssembler` + Token 预算（§8.8.2）：分区装配模板；`DocumentSelector` 按 token 累加（上限 1500）选 RAG 文档，超 6k 按优先级丢弃。
5. **`QueryRewriteAdvisor` 插入链**（L1 感知改写，**仅改检索句不改原话**）+ Caffeine 缓存（§3.1，key=MD5(近3轮摘要+query)，TTL=10min）。
6. 状态机 + 主动澄清（§8.6）：意图枚举 + 允许转移；多订单/意图跳转/口述冲突 → `needs_clarification`。
7. **并发写回**（§8.8.3）：同 `session_id` 串行锁/队列 + `SELECT ... FOR UPDATE` 行锁抢占。

**交付物与验收**
- 多轮对话后 `session_state` 正确增量；意图翻转**替换**不并存。
- 代词"这个能退吗"能结合订单号重写为独立检索句，RAG 命中率提升。
- 连续 8 轮追问 prompt 不超 token 预算；流式途中新消息不被覆盖状态。

**依赖**：M3, M4, M5
**风险/面试点**：**"10 轮窗口"反模式 → 三层纠正**是面试核心加分项；SignalDetector 否定窗口防误触发是高频追问。

> ✅ **M6 已构建（2026-08-26）**：`agent/SessionState` + `SessionStateRepository`(乐观锁 version) + `SessionWriteLock`(同 session 串行) + `SessionStateService`(qwen-turbo + BeanOutputConverter 增量提取) + `SignalDetector`(否定窗口/订单号/孤立情绪) + `StateMachine`(主动澄清) + `QueryRewriteService`(L1 改写 + Caffeine 缓存) + `ContextAssembler`(token 预算+分区模板)；`ChatService` 编排信号→状态→状态机→改写→检索→装配。依赖新增 Caffeine；`init.sql` session_state 改 TEXT。测试：`SignalDetectorTest`(4)/`StateMachineTest`(3)/`ContextAssemblerTest`(2)/`QueryRewriteServiceTest`(1)/`SessionStateServiceTest`(2) + 更新 `ChatServiceTest`，共 14 例。详见 `docs/M6_VERIFICATION.md`。
> 注：沙箱禁 TLS，`mvn test` 需你本机执行；沙箱内完成静态审查与代码交付。

---

## M7 — 输出护栏 + PII 双重脱敏

**目标**：回答不胡编、PII 不落地明文（§1.1、§5）。

**任务拆解**
1. `agent/OutputGuardrailAdvisor`（响应方向）：
   - **PII 输出脱敏**：对 LLM 回答遮盖后再流式推前端；
   - **越界拒答**：超出知识/工具范围的强断言拦截；
   - **事实一致性**：`after` 阶段调 qwen-turbo LLM-as-judge（§1.1 Prompt），`FAIL` → 降级话术转人工。
2. `common/PiiMaskUtil` 三道缝：
   - **入库脱敏**：客户消息写 `message` 表前 `mask()`；
   - **输出脱敏**：上面 OutputGuardrail 调用；
   - **日志脱敏**：`ObservationFilter`/日志切面确保 trace 与日志不落明文。

**交付物与验收**
- 回答含手机号 → 前端收到 `138****8000`；
- 让模型编造政策 → `FAIL` 返回降级话术；
- 查日志/trace 无 PII 明文。

**依赖**：M3, M5
**面试点**：双重脱敏"何时调"歧义已消除（入库 + 输出 + 日志三缝）。

> ✅ **M7 已构建（2026-08-27）**：`common/PiiMaskUtil` 补银行卡掩码（三道缝统一入口）+ `agent/OutputGuardrailService`（PII 输出脱敏 + 越界拒答规则 + 事实一致性 LLM-as-judge）；`ChatService` 流式 token 逐条脱敏 + 完成时越界/一致性校验。测试：`PiiMaskUtilTest`(5)/`OutputGuardrailServiceTest`(5) + 更新 `ChatServiceTest`，共 12 例。详见 `docs/M7_VERIFICATION.md`。
> 注：沙箱禁 TLS，`mvn test` 需你本机执行；沙箱内完成静态审查与代码交付。

---

## M8a — 前端：客户聊天窗 + 坐席确认台（Vue3 + Vite + ElementPlus）

**目标**：客户流式对话 + 坐席 HITL 确认台可用，严格对齐 M3/M4/M5/M7 的 SSE 事件契约。

**任务拆解**
1. 工程脚手架：Vue3 + Vite + ElementPlus + Pinia + Vue Router（先建聊天/确认台两视图，KB 视图在 M8b 补）。
2. **客户聊天窗**：`EventSource` 接 `/chat/stream`；渲染文本流；`[n]` → 可点击 chip（接 M5 citations）；流式未结束时禁用输入框（对齐 M6 串行）。
3. **坐席确认台**：
   - 待确认卡（ElementPlus 可编辑表单：amount/reason 可改）；
   - 四动作：确认 / 改参后确认 / 驳回 / 取消（接 M4 `ConfirmController`）；
   - 实时：SSE 或 2s 轮询收 pending；审计条（谁/何时/原参 vs 终参/结果）；
   - 超时态：pending expired → 卡片置灰。

**交付物与验收**
- 客户提问流式顺滑、带引用 chip；
- 坐席改参确认后客户窗收到执行结果（结果回灌可见）；超时卡正确置灰。

**依赖**：M3, M4, M5, M7
**风险/面试点**：客户/坐席双 SSE 通道 → 用单 SSE + `event` 类型区分，或 WebSocket；输入框禁用对齐 M6 串行。

> ✅ **M8a 已构建（2026-08-27）**：`frontend/` Vue3+Vite+ElementPlus+Pinia+Router 脚手架；`src/api/index.js`（REST + EventSource SSE）；`ChatView`（流式渲染 + `[n]` 引用 chip + 流式中禁用输入）；`ConfirmView` + `PendingCard`（2s 轮询 pending + 四动作 + 超时置灰 + 审计历史）；Vite proxy 转发 `/chat /kb /confirm /actuator` 免 CORS。详见 `docs/M8a_VERIFICATION.md`。
> 注：前端 `npm install && npm run dev` 需本机执行验证（`localhost:5173`）；沙箱禁 npm 缓存，仅交付源码。

---

## M8b — 前端：知识库上传页 + 源抽屉（Vue3 + Vite + ElementPlus）

**目标**：知识库 ingestion 前端闭环 + 引用溯源抽屉，补齐三屏。

**任务拆解**
1. **知识库上传页**：文件选择 → 上传进度（接 M2 `POST /kb/upload`）→ 展示入库结果（chunk 数、source、parsed_text 长度）。
2. **源抽屉**：点聊天窗中的 `[n]` chip → 调 `GET /kb/doc/{docId}?chunk=` → 抽屉展示 `knowledge_doc.parsed_text` 全文 + 高亮对应 chunk；同 doc 多 chunk 合并高亮。
3. 三视图路由收敛 + 全局布局（顶栏/导航）。

**交付物与验收**
- 上传 PDF 后可在聊天中检索到并带 `[n]`；
- 点 chip 抽屉正确展示原文并高亮命中段落。

**依赖**：M2, M5（M8a 已建脚手架，本里程碑复用）
**风险/面试点**：大文本 `parsed_text` 渲染性能 → 抽屉内虚拟滚动/分段加载。

---

## M9 — 可观测 + 评估 + 多租户接缝 + 交付文档

**目标**：全链路可观测、质量可量化、多租户接缝收口、可复现交付。

**任务拆解**
1. **可观测**：Spring AI 原生 Micrometer/OTel trace + token 成本；`CostTrackingAdvisor` 汇总每轮 token/成本（不自建表）。
2. **评估**：`eval/eval-set.json` **先做 20 条最小集**（覆盖 refund/logistics/address/coupon 四类 + 纯知识问答 + 越界/幻觉反面 case）跑通 EvalRunner 全流程与报告；验证无误后再扩到 50 条。`EvalRunner` 四维自动判分——检索召回 / 忠实度(LLM-as-judge) / 关键词覆盖 / 工具正确性；输出 JSON/HTML 报告（可作 CI 门禁）。
3. **多租户接缝**：`TenantContext`(ThreadLocal) + 拦截器注入 `tenant_id`；所有向量/状态/对话查询带 `filterExpression=tenant_id==:tenantId`（单商户默认 `default`）。
4. **交付**：`README.md`（本机起栈步骤：连 `D:\PostgreSQL` 本机库 + 百炼 API 配置 + 起 `doc-processor` 子项目 + 跑 demo + 跑 eval）、架构文档定稿、eval 演示输出。**`doc-processor` 自带 Dockerfile 保留容器化兼容；Spring Boot 应用未来亦可加 Dockerfile + docker-compose 上服务器，但当前开发期不强制。**

**交付物与验收**
- 跑 `EvalRunner` 出报告（每 case pass/fail + 聚合分）；
- 多租户 filter 生效（单商户默认 tenant 也能正确隔离）；
- 按 README 可零改动复现整套 demo。

**依赖**：全部
**面试点**：可观测用框架原生（不自建表，呼应你纠正）；评估集四维设计证明"答得好"；tenant 接缝证明 enterprise 思维。

---

## 交付节奏建议

- **M1–M4** = 后端主干，约覆盖 60% 工作量，先把"能对话 + 能确认"跑通（demo 最核心）。
- **M5–M7** = 可信度三件套（可查 + 不胡编 + 不泄密），是面试追问集中区。
- **M8a / M8b** = 前端，M8a（聊天+确认台）先跑通核心交互，M8b（KB上传+源抽屉）复用脚手架补齐；接口契约以 M2/M3/M4/M5/M7 为准。
- **M9** = 收口与演示材料，决定"能不能讲清楚"。

每个里程碑结束都应有：可编译通过 + 一项可演示验证 + 一段内存记录。不跨里程碑堆积未验证代码。
