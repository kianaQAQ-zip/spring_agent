# M1 验证报告 — 脚手架与基础设施

**日期**：2026-08-25
**结论**：✅ 编译通过 + 单元测试 3/3 全绿（上下文加载、/chat/health、/actuator/health 端到端）

---

## 验证结果

| 项 | 结果 |
|---|---|
| `mvn clean compile` | ✅ EXIT=0 |
| `mvn test`（3 用例） | ✅ Tests run: 3, Failures: 0, Errors: 0 |
| 多 ChatModel Bean 装配无歧义 | ✅ |
| pgvector 自动配置冲突 | ✅ 已排除 |
| Web 端点 /chat/health | ✅ 返回 `{"status":"UP"}` |
| 健康检查 /actuator/health | ✅ 返回 `{"status":"UP"}` |

---

## 构建期修复的关键问题（Spring AI 1.0.0 GA 真实 API 对齐）

1. **依赖名错误**：`spring-ai-advisors-vector`（1.0.0 不存在，404）→ 改用 `spring-ai-rag`。
2. **OpenAI starter 强绑 key**：`spring-ai-starter-model-openai` 拉 `spring-ai-autoconfigure-model-openai`，强制要求 `spring.ai.openai.api-key` 并注册一堆用不上的 `OpenAi*Model` → 改核心模块 `spring-ai-openai`（本项目模型全自管）。
3. **`OpenAiApi` 包名**：实际在 `org.springframework.ai.openai.api.OpenAiApi`。
4. **`OpenAiEmbeddingModel` 无 `builder()`**：改用构造器 `new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options)`。
5. **`PgDistanceType` 常量**：是 `COSINE_DISTANCE`（非 `COSINE`）。
6. **多 `ChatModel` 歧义**：显式声明 `@Bean ChatClient.Builder` 并用 `@Qualifier("qwenChatModel")` 锁主模型。
7. **pgvector Bean 撞名**：`spring.autoconfigure.exclude` 排除 `PgVectorStoreAutoConfiguration`；并显式 `.initializeSchema(false)` 让 DDL 由 `db/init.sql` 接管。

---

## 本机运行方式（需在你机器上执行）

```bash
# 1) 先建库 + 扩展 + 6 张表（PG 已装 D:\PostgreSQL，库 ecom_agent）
psql -U postgres -d ecom_agent -f src/main/resources/db/init.sql

# 2) 注入 API Key（不要落库）
export DASHSCOPE_API_KEY=sk-xxx
export DEEPSEEK_API_KEY=sk-yyy        # 可选，降级用

# 3) 用项目内 Maven 启动（JDK 23 在 /d/JDK23）
export JAVA_HOME=/d/JDK23
bash .tooling/mvn.sh spring-boot:run
# 或打 jar: bash .tooling/mvn.sh clean package && java -jar target/spring-agent-0.1.0.jar

# 4) 探活
curl http://localhost:8080/chat/health
curl http://localhost:8080/actuator/health
```

> 沙箱环境无本机 PG 与真实 Key，故测试改用 H2 内存库 + 空 key 验证 Bean 装配与 Web 层；上述真实联调步骤需在你本机完成。

---

## M1 交付清单

- [x] Spring Boot 3.5.3 + Spring AI 1.0.0 工程骨架
- [x] 多模型 Bean（qwen-plus / qwen-turbo / text-embedding-v3 / deepseek）经百炼 DashScope 接口
- [x] PgVectorStore（dim=1024, COSINE_DISTANCE, tenant_id 列, 不自建 schema）
- [x] 统一响应 / 全局异常 / PII 脱敏骨架
- [x] db/init.sql（6 张表 + vector 扩展）
- [x] /chat/health + /actuator/health 跑通并验证
- [ ] 真实本机 PG 联调（待你执行上面的运行方式）
