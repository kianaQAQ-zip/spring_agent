# M4 验证记录 — 工具层 + 确认护栏（HITL）

> 日期：2026-08-26
> 状态：✅ 已构建 + 本机验证通过（**`mvn test` 29/29 全绿，BUILD SUCCESS**）。

## 1. 交付内容

| 模块 | 类 | 职责 |
| --- | --- | --- |
| 只读工具 | `tools/OrderQueryTool` | 订单/物流查询，`@Tool` 只读直执行，不落 pending |
| 需确认工具 | `tools/RefundTool` / `AddressChangeTool` / `CouponTool` | `@ConfirmRequired`，模型调用时**不真执行**，落 pending 返回 PENDING_CONFIRMATION |
| 确认护栏核心 | `agent/ConfirmationService` | pending 生命周期：幂等发起 / 原子确认(双执行防护) / 驳回 / 取消 / 超时 Reaper / 结果回灌 |
| 数据模型 | `agent/PendingAction` | `pending_action` 表映射 record |
| 异常 | `agent/ConfirmationConflictException` | 重复确认/越权操作冲突 |
| 坐席接口 | `api/ConfirmController` | POST/PUT/GET `/confirm/...` 六端点 |
| 编排接入 | `agent/ChatService` | `defaultTools(...)` 注册 4 工具 + `toolContext(conversationId)` 透传会话 |
| 表结构 | `db/init.sql` | `pending_action` 增加 `idempotency_key/expires_at/result/tenant_id` + 唯一索引 |

## 2. 关键设计决策

1. **⚠️ GA API 调整（重要）**：里程碑原稿写的 `ToolExecutionStrategy` + `ChatClient.Builder.defaultToolExecutionStrategy(...)` 在 **Spring AI 1.0.0 GA 不存在**。GA 的工具拦截点是：
   - `@Tool` / `@ToolParam` 注解（`org.springframework.ai.tool.annotation`）；
   - `ChatClient.Builder.defaultTools(Object...)` 注册、`ChatClientRequestSpec.toolContext(Map)` 透传上下文；
   - `ToolCallback.call(String, ToolContext)`：工具方法可声明 `ToolContext` 参数，Spring AI 自动注入（已 javap 确认 `MethodToolCallback.buildMethodArguments(Map, ToolContext)`）。
   - 因此「只读直执行 / 需确认拦截」的语义落点改为：**工具方法自身**——只读工具直接返回数据；`@ConfirmRequired` 工具调 `ConfirmationService.request(...)` 落 pending 并返回 PENDING，副作用只在坐席 `confirm` 后执行。
2. **§2.2 幂等键**：`hash(conversation_id + tool + 归一化 params)`（SHA-256）；`(conversation_id, idempotency_key)` 唯一索引兜底，并发重复发起捕获 `DuplicateKeyException` 返回既有记录。
3. **§2.3 双执行防护**：确认走 `UPDATE ... SET status='confirmed' WHERE id=? AND status='pending'`，受影响行数=0 即已处理 → 抛 `ConfirmationConflictException`（HTTP 409）。
4. **§2.1 超时 Reaper**：`@Scheduled` 定时把 `expires_at < now()` 的 pending 翻为 expired；单条 UPDATE 原子幂等，生产多实例可升级为 `FOR UPDATE SKIP LOCKED` 抢占（代码注释已标注）。
5. **§2.4 结果回灌**：`confirm` 时执行动作（演示 mock）并把结果写 `result` 列，供下一轮上下文回灌；`GET /confirm/{id}` 可读回。
6. **会话上下文**：`toolContext(Map.of("conversationId", conversationId))` 透传，工具方法经 `ToolContext.getContext()` 取值，与 M3 的 `MessageChatMemoryAdvisor` 会话键一致。

## 3. 测试清单

| 测试类 | 类型 | 覆盖点 | 用例数 |
| --- | --- | --- | --- |
| `ConfirmationServiceTest` | `@SpringBootTest`(H2) | 幂等发起 / 确认回灌结果 / 双确认冲突 / 驳回 / 取消 / Reaper 过期 | 6 |
| `ConfirmControllerTest` | `@WebMvcTest` | 确认 200 / 冲突 409 / 改参确认 / 驳回 / 取消 / 查无 404 | 6 |
| `RefundToolTest` | Mockito | `@ConfirmRequired` 工具落 pending 不真执行、返回 PENDING_CONFIRMATION | 1 |

> 合计新增 13 例；连同 M1–M3 既有 16 例，全量 **29 例全部通过**（本机 `mvn test` 已确认）。

## 4. 运行方式

- **本机验证**：`mvn test`（H2 + 空 key，不联网不触 PG）。
- **真实联调**：
  1. 本机 PG `db/init.sql` 重建 `pending_action` 表（已扩展列）；
  2. `mvn spring-boot:run`；
  3. 模拟坐席确认：
     ```
     # 列出某会话的待确认
     curl "http://localhost:8080/confirm/pending?conversationId=demo"
     # 确认执行（可带改参）
     curl -X POST "http://localhost:8080/confirm/{id}" -H "Content-Type: application/json" \
          -d '{"operator":"agent01"}'
     ```
  4. 走对话：客户问「我要退 ORD-1001 的耳机」→ 模型调用 `refund` → 落 pending、回复「等待坐席确认」→ 坐席确认后结果回灌。

## 5. 已知限制

- 工具为 mock 实现（无真实订单/物流/券系统），`confirm` 的执行结果为演示 JSON。
- 结果回灌目前仅落 `result` 列 + 可查接口；注入下一轮上下文的装配在 M6 `ContextAssembler` 收口。
- Reaper 当前为单条 UPDATE（H2/PG 通用）；多实例抢占用 `FOR UPDATE SKIP LOCKED` 的升级点已留注释。
