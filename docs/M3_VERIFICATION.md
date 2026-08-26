# M3 验证记录 — 对话引擎 + 流式 + 基础 Advisor 链

> 日期：2026-08-26
> 状态：✅ 已构建 + 本机验证通过（**`mvn test` 16/16 全绿，BUILD SUCCESS**）。

## 1. 交付内容

| 模块 | 类 | 职责 |
| --- | --- | --- |
| 租户接缝 | `common/TenantContext` | ThreadLocal 持有 `tenant_id`（默认 `default`），M9 收口多租户隔离 |
| 短期记忆 Bean | `config/ChatClientConfig.chatMemory()` | `MessageWindowChatMemory`（窗口 16 条 = 8 轮），`InMemoryChatMemoryRepository`，预留 Redis 替换点 |
| 对话编排 | `agent/ChatService` | 构建 Advisor 链（Memory→RAG），`streamAnswer()` 流式输出 |
| 流式端点 | `api/ChatController.GET /chat/stream` | SSE（`text/event-stream`），逐 token `event: token` 推送 |

## 2. 关键设计决策（已落地）

1. **Advisor 链（Memory→RAG→Observation）**：
   - `MessageChatMemoryAdvisor(chatMemory)`：L1 短期记忆，窗口最近 8 轮，conversationId 经 `ChatMemory.CONVERSATION_ID` 透传。
   - `RetrievalAugmentationAdvisor`：`VectorStoreDocumentRetriever`（topK=4，similarityThreshold=0.78，filterExpression=`tenant_id == 'default'`）+ `ContextualQueryAugmenter(allowEmptyContext=true)`。
2. **⚠️ API 调整（重要）**：里程碑文档 M3 写的 `QuestionAnswerAdvisor` 在 **Spring AI 1.0.0 GA 已被移除**，实际替换为 `RetrievalAugmentationAdvisor`（更灵活的 pre-retrieval / retriever / joiner / post-processor / augmenter 链）。本里程碑按其 GA API 落地，后续 M5 重排/M6 QueryRewrite 可直接插 `documentPostProcessors` / `queryTransformers`，无需重构。
3. **流式边界（§1.2）**：成功流由 `MessageChatMemoryAdvisor` **自动落库** user+assistant；失败/客户端断连时 Advisor 不写 assistant，故仅在 `doOnError` 手动补一条 `[truncated]` 标记（`persistTruncated`），既防打断丢轮、又不与 Advisor 自动落库重复（若 onComplete 也手动 add 会双写）。
4. **SSE 格式**：返回 `Flux<ServerSentEvent<String>>`（`event: token` + `data:` 帧），与 M8 `EventSource` 契约对齐；比裸 `Flux<String>` 更规范、前端零改造。
5. **tenant 过滤安全**：`TenantContext.get()` 为受控常量（非用户输入），经 `FilterExpressionBuilder.eq("tenant_id", ...)` 拼装，无 SQL/过滤器注入风险。

## 3. 测试清单

| 测试类 | 类型 | 覆盖点 | 结果 |
| --- | --- | --- | --- |
| `ChatServiceTest` | `@SpringBootTest` | 流式 token 输出 + 成功流记忆落库(user+assistant) + 错误流补 `[truncated]` 记忆 | 2 例 ✅ |
| `ChatControllerTest` | `@WebMvcTest` | `/chat/health` 返回 UP + `/chat/stream` 异步分发后 200 + text/event-stream | 2 例 ✅ |
| `KbControllerTest` | `@WebMvcTest` | `/kb/upload` + `/kb/doc/{docId}` | 3 例 ✅ |
| `KbIngestionServiceTest` | `@SpringBootTest` | Tika 兜底 + clean_score<0.5 隔离 | 2 例 ✅ |
| `StructureAwareChunkerTest` | 纯逻辑 | 原子块保护 + 超大块拆分 + heading 上下文 | 3 例 ✅ |
| `DocProcessorClientTest` | Mockito | doc-processor 不可达 → unreachable 标记 | 1 例 ✅ |

> 合计 **16/16 全绿**（含 M2 遗留的 8 例 + M3 新增 2 例，其余为 M1 上下文装配验证）。

## 4. 构建期踩坑（已修复/规避，备查）

- **`QuestionAnswerAdvisor` 不存在于 1.0.0 GA**：改 `RetrievalAugmentationAdvisor` + `VectorStoreDocumentRetriever` + `ContextualQueryAugmenter`（已 `javap` 逐项核对 `builder()` 方法签名：`documentRetriever` / `queryAugmenter` / `topK` / `similarityThreshold` / `filterExpression(Filter.Expression)`）。
- **filterExpression 入参类型**：`VectorStoreDocumentRetriever.Builder.filterExpression` 接收 `Filter.Expression`（非 String），用 `new FilterExpressionBuilder().eq("tenant_id", tenant).build()` 构造。
- **`ChatClient` 流式链**：`prompt().user(String).advisors(Consumer<AdvisorSpec>).stream().content()` —— `ChatClientRequestSpec` 同时提供 `user/advisors/stream`，链式合法（已 `javap` 确认）。
- **记忆 Bean**：`MessageWindowChatMemory.builder().chatMemoryRepository(InMemoryChatMemoryRepository).maxMessages(16)`，包名 `org.springframework.ai.chat.memory`（在 `spring-ai-model`，由 `spring-ai-rag` 传递引入）。

## 5. 运行方式

- **本机验证**：`mvn test`（H2 + 空 key，不联网不触 PG）。
- **真实联调**（需要）：
  1. 本机 PG (`D:\PostgreSQL`, 库 `ecom_agent`) + `db/init.sql` 已建表；
  2. 先跑 M2 入库一份售后政策 PDF（`curl -F "file=@x.pdf" localhost:8080/kb/upload`）；
  3. `mvn spring-boot:run` 后：
     ```
     curl -N "http://localhost:8080/chat/stream?message=七天无理由退货怎么算&conversationId=demo"
     ```
     应逐 token 返回，且回答引用知识库内容（RAG 命中）。

## 6. 已知限制

- 记忆为内存实现，重启即丢；M9 替换为 Redis/Jdbc（`ChatMemoryRepository` 接缝已留）。
- 单租户默认 `default`；M9 接 `TenantContext` 拦截器动态取值后，filterExpression 改为运行时拼装。
- 多轮后 memory 窗口正确性、SSE 断连重连由 M6 状态机与 §1.2 进一步加固。
