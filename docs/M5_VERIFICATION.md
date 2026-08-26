# M5 验证记录 — 引用溯源 + 重排精排（Citation / Grounding / Rerank）

> 日期：2026-08-26
> 状态：✅ 已构建（代码 + 单测已写）；`mvn test` 需本机执行验证。

## 1. 交付内容

| 模块 | 类 | 职责 |
| --- | --- | --- |
| 元数据工具 | `rag/RagDocUtils` | 元数据键常量 + `docKey(doc)=doc_id#chunk_index`（融合去重键） |
| 关键词检索 | `rag/Bm25Index` | 过程内 BM25（无需 Lucene/Docker），入库时建索引，命中 SKU/政策编码精确串 |
| 混合召回 | `rag/HybridRetriever` | 向量 topK=20 + BM25 + RRF(k=60) 融合去重（§9.5 Stage1） |
| 重排 | `rag/RerankService` | Cross-encoder（经 doc-processor `bge-reranker-v2-m3`）；不可达降级为排序伪分 |
| 精排 | `rag/MmrSelector` | MMR 去冗余 + `table/atomic` 提权 + token 预算（§9.5 Stage3） |
| 流水线 | `rag/RetrievalPipeline` | 混合召回 → 重排 → MMR → 编号，产出 `RetrievalResult` |
| 引用校验 | `rag/CitationValidator` | 抽取 `[n]`、越界检测（防模型编造引用） |
| 客户端 | `DocProcessorClient.rerank()` | 调 `/api/v1/rerank`，不可达返回空（降级信号） |
| 编排接入 | `agent/ChatService` | 改手动检索：先发 `event: citations` 再发 `event: token`，system prompt 带编号 + `[n]` 后校验 |
| 溯源接口 | `KbController` + `KbIngestionService.getChunk()` | `GET /kb/doc/{docId}?chunk=` 返回全文 + 定位 chunk |
| 入库增强 | `KbIngestionService` | 分块入库时同步写 BM25 索引 |

## 2. 关键设计决策

1. **检索改手动编排**：M3 用 `RetrievalAugmentationAdvisor`（检索+注入自动完成，但拿不到最终排名列表）。M5 改为 `RetrievalPipeline` 手动编排，先拿到重排后的 `List<Document>` 与 `List<Citation>`，从而能先发 `citations` SSE 事件、后发文本流，并对 `[n]` 做后校验。`MessageChatMemoryAdvisor` 仍保留在 Advisor 链里。
2. **混合召回**：向量（语义）与 BM25（关键词精确串）双路召回，RRF 融合去重——SKU/政策编码这类精确串在语义向量里常被稀释，BM25 补足。
3. **两级重排**：Stage2 Cross-encoder 经 doc-processor 真接（解耦、可水平扩展）；不可达时降级。Stage3 MMR 去冗余 + 结构化块（table/atomic）提权 + token 预算（~1500）防止上下文爆炸。
4. **引用契约**：`citations` 事件 `[{index,docId,source,chunkContent,page,score}]`，与前端（M8b）源抽屉对齐；`[n]` 后校验越界仅告警不阻断（不影响流式输出）。
5. **BM25 索引生命周期**：过程内 `@Component` 单例，`KbIngestionService` 入库时同步写；重启即重建（与 PgVector 持久化互补，文档注明限制）。

## 3. 测试清单

| 测试类 | 类型 | 覆盖点 | 用例数 |
| --- | --- | --- | --- |
| `Bm25IndexTest` | 纯逻辑 | 关键词命中精确串 / 空索引 | 2 |
| `MmrSelectorTest` | 纯逻辑 | token 预算 / 去冗余 | 2 |
| `CitationValidatorTest` | 纯逻辑 | 抽取/越界/合法判定/空文本 | 4 |
| `HybridRetrieverTest` | Mockito | RRF 融合去重 | 1 |
| `RerankServiceTest` | Mockito | Cross-encoder 命中 / 不可达降级 | 2 |
| `RetrievalPipelineTest` | Mockito | 编排 + 引用编号从 1 起 | 1 |
| `ChatServiceTest`（更新） | `@SpringBootTest` | citations 先发 + 流式 + 记忆/截断 | 2 |
| `KbControllerTest`（更新） | `@WebMvcTest` | 上传/全文/404 + chunk 高亮 | 4 |

> 新增/更新 18 例；连同 M1–M4 既有 29 例，全量约 47 例。`mvn test` 需本机执行。

## 4. 运行方式

- **本机验证**：`mvn test`。
- **真实联调**：
  1. 起 doc-processor（含 `ml` extra 的 bge-reranker）：`cd doc-processor && uv sync --extra ml --extra pdf && uv run uvicorn app.main:app --port 8000`；
  2. M2 入库一份售后政策 PDF；
  3. `curl -N "http://localhost:8080/chat/stream?message=七天无理由退货怎么算&conversationId=demo"` → 先收 `event: citations`，再逐 token 带 `[n]`；
  4. `curl "http://localhost:8080/kb/doc/{docId}?chunk=1"` 取原文+定位 chunk。

## 5. 已知限制

- BM25 为过程内内存索引，重启即失（重新入库重建）；生产可换 Lucene/Elasticsearch。
- Cross-encoder 依赖 doc-processor 的 `ml` extra；不可达时走 MMR 降级。
- `getChunk` 经向量库 metadata 过滤取 chunk 内容，PG 下 `chunk_index` 数值过滤语义需真实联调确认（必要时元数据统一为字符串）。
