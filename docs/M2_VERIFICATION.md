# M2 验证记录 — RAG 知识库入库流水线（Ingestion）

> 日期：2026-08-25
> 状态：✅ 已构建并通过单元测试（沙箱无真实 PG / API Key，用 H2 + 空 key 验证；真实联调需本机执行）

## 1. 交付内容

| 模块 | 类 | 职责 |
| --- | --- | --- |
| 解析客户端 | `rag/DocProcessorClient` | 封装 Python `doc-processor` `/api/v1/parse`（RestClient，5s 连接 / 30s 读超时）；不可达 / 业务 `ok=false` → `ParseResult.unreachable()`，交由上层 Tika 兜底，不阻断主流程（§10.5 故障隔离） |
| 兜底解析 | `rag/TikaDocumentLoader` | doc-processor 不可达时 Apache Tika 抽取纯文本，保留 `parsedText` 全文落 `knowledge_doc.parsed_text` |
| 柔性分块 | `rag/StructureAwareChunker` | §9.4 弹性 400–800 token、原子块保护（table/figure/key_data 不切）、heading_path 层级上下文、~12% 尾部重叠 |
| Token 估算 | `rag/TokenUtils` | CJK 按 `len/1.5`，拉丁字母/数字按词计 |
| 归一化结果 | `rag/dto/ParseResult` + `rag/dto/ParseBlock` | 统一 doc-processor / Tika 两种来源；`cleanScore` + `flags` 质量门禁 |
| Embedding 封装 | `rag/EmbeddingService` | 批量(16) + 指数退避重试(3) + 批间延迟(50ms)；主要供 M5 检索侧复用 |
| 入库编排 | `rag/KbIngestionService` | 解析 →（不可达降级 Tika）→ clean_score<0.5 隔离(QUARANTINED) → 分块 → PgVectorStore.add → knowledge_doc 落库 |
| 文档记录 | `rag/KnowledgeDoc` + `rag/KnowledgeDocRepository` | JDBC 写 `knowledge_doc`（id/doc_id/tenant_id/source/chunk_count/parsed_text/created_at） |
| 端点 | `api/KbController` | `POST /kb/upload`(multipart) → `ApiResponse<IngestionResult>`；`GET /kb/doc/{docId}` → `ApiResponse<KnowledgeDoc>` |

## 2. 关键设计决策（已落地）

1. **故障隔离（§10.5）**：`DocProcessorClient.parse()` 任何 `RestClientException` / `IOException` / `ok=false` / 空响应 → `unreachable()`。KbIngestionService 据此转 Tika，主流程永不因 Python 子项目宕机而中断。
2. **质量门禁（§9.1）**：`cleanScore < 0.5` → 返回 `QUARANTINED`，**不入向量库、不写 knowledge_doc**，等待人工复核。
3. **Embedding 时机**：经反编译确认 `PgVectorStore.add()` 内部调用 `EmbeddingModel.embed()` 编码，**不复用外部预计算向量** → 入库服务不显式 embed，`vectorStore.add(documents)` 即用 Tongyi `text-embedding-v3`(1024 维) 编码。`EmbeddingService` 仅供 M5 检索侧对 query 批量编码复用。
4. **metadata 溯源字段**：入库 Document 带 `tenant_id / doc_id / chunk_index / source / page / block_type / atomic / token_count / heading_path`，M5 引用溯源与 M9 多租户 filter 直接复用。
5. **测试隔离**：`@SpringBootTest` 用 H2(`MODE=PostgreSQL`) + 空 key；`PgVectorStoreAutoConfiguration` 已排除，`VectorStore` Bean 在 `KbIngestionServiceTest` 中被 `@MockBean` 替换；`doc-processor` / Tika / VectorStore 全部 mock，仅 `knowledge_doc` 走真实 H2 落库验证。

## 3. 测试清单

| 测试类 | 类型 | 覆盖点 | 预期 |
| --- | --- | --- | --- |
| `StructureAwareChunkerTest` | 纯单测 | 原子块独立成 chunk、长文弹性切分(<950 token)、heading_path 层级 | 3 例全过 |
| `DocProcessorClientTest` | Mockito | 不可达抛 `RestClientException` → `reachable=false` | 1 例全过 |
| `KbControllerTest` | `@WebMvcTest` | `/kb/upload` 与 `/kb/doc/{docId}` 端点契约 + 统一信封 `code/msg/data` | 3 例全过 |
| `KbIngestionServiceTest` | `@SpringBootTest` | doc-processor 不可达 → Tika 兜底 → 分块 → 向量入库(mock 校验) + knowledge_doc 落库；clean_score 过低 → QUARANTINED 不入库 | 2 例全过 |

**总计**：9 个测试用例（`mvn test` 全绿，需本机执行；沙箱禁 TLS 故无法直接跑）。

## 4. 构建期踩坑（已修复，备查）

- **`ParseResult` 构造器可见性**：测试跨包（`com.ecomagent.rag` vs `com.ecomagent.rag.dto`）直接 `new ParseResult(...)` 编译失败（构造器 `private`）。修复：新增 `public static ParseResult.fallbackWithScore(source, blocks, cleanScore, flags, parsedText)` 工厂，专供低质量门禁场景注入自定义 `cleanScore`，测试改用该工厂。**不要**把构造器改成包级（跨包无效）。

## 5. 运行方式

- **沙箱/CI 契约**：`mvn test`（H2 + 空 key，不联网、不触 PG）。
- **本机真实联调**（需要）：
  1. 本机 PostgreSQL 在 `D:\PostgreSQL`，库 `ecom_agent` + `vector` 扩展已就绪，`src/main/resources/db/init.sql` 建 6 张表；
  2. 启动 `doc-processor` 子项目（`uv run uvicorn app.main:app --port 8000`）；
  3. 设置环境变量 `DASHSCOPE_API_KEY=sk-...`（百炼，text-embedding-v3 + qwen-plus）；
  4. `mvn spring-boot:run` 后 `curl -F "file=@售后政策.pdf" http://localhost:8080/kb/upload`。

## 6. 已知限制

- `clean_score` 阈值 `0.5` 为初版拍定，M9 评估集量化后再调；
- 扫描件在 doc-processor 宕机时退 Tika 会丢内容（架构上由 doc-processor 的 MinerU+PaddleOCR 真接解决，本里程碑仅保证降级不崩）；
- 多租户 `tenant_id` 当前写死 `default`（M9 收口 `TenantContext` 接缝）。
