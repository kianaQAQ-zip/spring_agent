# 电商客服 Agent（Spring Boot 3.5 + Spring AI 1.0 + PgVector）

企业级电商客服 Agent：**知识库解析 → 向量入库 → 多轮对话 + 引用溯源 + 工具调用 + 人工确认护栏（HITL）+ 输出护栏**，前后端完整闭环。

## 架构

```
Vue3 前端（frontend/）            Spring Boot 后端（src/）              Python 子项目（doc-processor/）
  ├ 客户聊天窗（SSE）  ──►  ChatService 编排链                          ├ MinerU OCR
  ├ 坐席确认台（轮询） ──►  ConfirmationService（HITL）                 ├ bge-reranker 重排
  └ 知识库上传         ──►  KbIngestionService（解析→分块→入库）  ◄──  └ DocumentCleaner 清洗
                              ├ RetrievalPipeline（混合召回→重排→MMR）
                              ├ SessionState（L2 状态机 + QueryRewrite）
                              └ OutputGuardrail（事实一致性 + PII 脱敏）
```

**技术栈**：Spring Boot 3.5.3 · Spring AI 1.0.0 GA · PgVector（PostgreSQL 17）· Apache Tika · 通义 qwen-plus/qwen-turbo/text-embedding-v3 + DeepSeek-V3 · Vue3 + Vite + ElementPlus · FastAPI（doc-processor）

## 里程碑

M1 脚手架 → M1.5 doc-processor → M2 RAG 入库 → M3 对话+流式 → M4 工具+HITL → M5 引用溯源+重排 → M6 状态机+QueryRewrite → M7 输出护栏+PII → M8a 聊天窗+确认台 → M8b 上传页+源抽屉 → M9 可观测+评估+多租户

## 本机起栈（零改动复现）

### 0. 前置

- JDK 17+、Maven（项目内 `.tooling/maven/apache-maven-3.9.9`，见 `.tooling/mvn.sh`）
- 本机 PostgreSQL 17 + pgvector 扩展（`D:\PostgreSQL`，库 `ecom_agent`）
- Python 3.11+（`uv` 管理 doc-processor）
- Node 18+（前端）

### 1. 建库建表

```sql
-- 连 D:\PostgreSQL 的 ecom_agent 库执行
\i src/main/resources/db/init.sql
```

### 2. 配置 API Key（环境变量，不落库）

```bash
export DASHSCOPE_API_KEY="sk-..."      # 阿里云百炼（qwen + text-embedding-v3）
export DEEPSEEK_API_KEY="sk-..."       # DeepSeek（降级 LLM，可选）
```

### 3. 起 doc-processor（可选，OCR/重排需 GPU/模型）

```bash
cd doc-processor
uv sync --extra ml --extra pdf
uv run uvicorn app.main:app --port 8000
```

> 不起 doc-processor 也能跑：解析自动降级 Tika，重排降级 MMR。

### 4. 起后端

```bash
mvn spring-boot:run          # 或 ./.tooling/mvn.sh spring-boot:run
# 端口 8080
```

### 5. 起前端

```bash
cd frontend
npm install
npm run dev
# 端口 5173，Vite 已代理 /chat /kb /confirm 到 8080
```

### 6. 跑 demo

1. 打开 `http://localhost:5173` →「知识库上传」拖入售后政策 PDF；
2. 切「客户聊天窗」问「七天无理由退货怎么算」→ 流式回答带 `[1][2]` 引用，点击看原文；
3. 问「我要退 ORD-1001 的耳机」→ 回复「已提交，等待坐席确认」；
4. 切「坐席确认台」→ 2s 内看到退款卡 → 改金额后确认 → 审计历史显示「已执行」。

## 跑评估

```bash
mvn test -Dtest=EvalRunnerTest      # 判分逻辑单测
# 生产对接真实 Agent 后，EvalRunner.run() 输出 JSON/HTML 报告（可作 CI 门禁）
```

## 测试

```bash
mvn test      # 全量单测（H2 内存库 + mock 模型，无需真实 PG/Key）
```

## 目录结构

```
src/main/java/com/ecomagent/
  agent/    对话编排 + 状态机 + QueryRewrite + 确认护栏 + 输出护栏 + 成本追踪
  rag/      入库流水线 + 混合召回 + 重排 + MMR + 引用
  tools/    订单/退款/改地址/发券工具
  api/      ChatController / KbController / ConfirmController
  common/   ApiResponse / PiiMaskUtil / TenantContext / TenantInterceptor
  config/   多模型 Bean / VectorStore / ChatClient / Web 配置
  eval/     评估集 + EvalRunner + 成本计算
src/main/resources/
  db/init.sql        6 张表 DDL
  eval/eval-set.json 20 条评估集
  application.yml    配置（Key 走环境变量）
frontend/            Vue3 前端（聊天窗 + 确认台 + 上传页）
doc-processor/       Python 文档处理（OCR/清洗/重排）
docs/                架构 + 各里程碑验证记录
```
