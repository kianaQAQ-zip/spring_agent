# M7 验证记录 — 输出护栏 + PII 双重脱敏

> 日期：2026-08-27
> 状态：✅ 已构建（代码 + 单测已写）；`mvn test` 需本机执行验证。

## 1. 交付内容

| 模块 | 类 | 职责 |
| --- | --- | --- |
| PII 脱敏 | `common/PiiMaskUtil` | 手机号/身份证/邮箱/银行卡正则掩码，三道缝统一入口 |
| 输出护栏 | `agent/OutputGuardrailService` | PII 输出脱敏 + 越界拒答 + 事实一致性（LLM-as-judge） |
| 编排接入 | `agent/ChatService` | 流式 token 逐条脱敏 + 完成时越界/一致性校验 |

## 2. 关键设计决策

1. **双重脱敏"何时调"已消除歧义（§5）**：三道缝统一走 `PiiMaskUtil.mask()`——
   - **输出缝**：`ChatService` 流式 token 逐条脱敏后推前端（`138****8000`）；
   - **入库缝**：`message` 表写库前调 `mask()`（表在 M9 接 DAO 时收口，工具已就绪）；
   - **日志缝**：纯正则掩码无副作用，可直接复用于日志/trace。
2. **越界拒答（§1.1）**：强断言词（绝对/肯定/100%/我确定…）且无 `[n]` 引用 → 判越界。
3. **事实一致性（§1.1）**：qwen-turbo LLM-as-judge，回答与检索上下文比对，`FAIL` → 降级话术转人工（裁判不可用放行不阻断）。
4. **流式脱敏**：逐 token 掩码，避免把原始 token 直接暴露给前端；跨 token 边界的 PII 已在文档标注为已知限制。

## 3. 测试清单

| 测试类 | 类型 | 覆盖点 | 用例数 |
| --- | --- | --- | --- |
| `PiiMaskUtilTest` | 纯逻辑 | 手机/身份证/邮箱/银行卡/空值 | 5 |
| `OutputGuardrailServiceTest` | Mockito | 输出脱敏 / 越界拒答 / 引用不误判 / judge PASS+FAIL | 5 |
| `ChatServiceTest`（更新） | `@SpringBootTest` | 流式 + 记忆/截断（新增 OutputGuardrailService mock） | 2 |

> 新增/更新 12 例；连同 M1–M6 既有 54 例，全量约 66 例。`mvn test` 需本机执行。

## 4. 运行方式

- **本机验证**：`mvn test`。
- **真实联调**：让模型回答中带一个手机号 → 前端 SSE 收到的是 `138****8000`；让模型编造政策 → 日志出现「事实一致性 FAIL」告警。

## 5. 已知限制

- 流式逐 token 脱敏对「跨 token 边界的 PII」可能漏掩（如手机号被拆成两个 token）；完整掩码需缓冲滑动窗口，M8 收口。
- 事实一致性 `FAIL` 当前仅告警，未在流中回传「转人工话术」事件（M8 前端事件化时补 `event: guardrail`）。
- `message` 表入库脱敏依赖 M9 接 DAO，工具已就绪。
