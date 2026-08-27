# M9 验证记录 — 可观测 + 评估 + 多租户接缝 + 交付文档

> 日期：2026-08-27
> 状态：✅ 已构建（代码 + 单测已写）；`mvn test` 需本机执行验证。

## 1. 交付内容

| 模块 | 类/文件 | 职责 |
| --- | --- | --- |
| 可观测 | `eval/CostCalculator` + `agent/CostTrackingAdvisor` | 每轮 token 用量/成本汇总（框架原生 Advisor，不自建表） |
| 评估 | `eval/EvalCase` `AgentAnswer` `CaseScore` `EvalReport` `EvalRunner` + `eval/eval-set.json` | 四维判分 + 20 条评估集 + JSON/HTML 报告 |
| 多租户 | `common/TenantInterceptor` + `config/WebConfig` | 请求头 `X-Tenant-Id` 注入 `TenantContext`，请求结束清理 |
| 交付 | `README.md` | 零改动复现整套 demo 的起栈步骤 |

## 2. 关键设计决策

1. **可观测用框架原生（不自建表）**：`CostTrackingAdvisor` 实现 Spring AI 原生 `CallAdvisor` + `StreamAdvisor`，从响应 metadata 的 `Usage` 读 token 数，经 `CostCalculator` 估成本后打日志；与 Spring AI 自带的 Micrometer/OTel observation 互补（observation 已由依赖自动配置）。
2. **评估四维判分**：关键词覆盖 / 意图正确 / 引用接地 / 忠实度，各 0~1，平均 ≥ 0.6 判 pass；输出聚合分 + 每用例明细，JSON/HTML 可作 CI 门禁。`Agent` 接口由调用方注入（生产接真实回答、测试 mock），判分逻辑纯函数化可单测。
3. **评估集 20 条最小集**：refund×5 / logistics×4 / address×3 / coupon×3 / knowledge×3 / out_of_scope×2（含越界反面 case），覆盖工具四类 + 纯知识 + 幻觉反面。
4. **多租户接缝收口**：`TenantInterceptor` 从 `X-Tenant-Id` 头注入 `TenantContext`（默认 `default`），所有向量/状态查询已带 `filterExpression=tenant_id==...`（M2/M3/M5 已就位，此处收口到 HTTP 入口）。
5. **交付可复现**：README 覆盖「建库 → 配 Key → 起 doc-processor → 起后端 → 起前端 → 跑 demo → 跑 eval」全流程；doc-processor 自带 Dockerfile 保留容器化兼容，当前开发期本机原生。

## 3. 测试清单

| 测试类 | 类型 | 覆盖点 | 用例数 |
| --- | --- | --- | --- |
| `CostCalculatorTest` | 纯逻辑 | 按模型/按 token 数量估成本 | 2 |
| `EvalRunnerTest` | 纯逻辑 | 四维满分 / 缺关键词降分 / 加载 20 条评估集 | 3 |

> 新增 5 例；连同 M1–M8 既有（约 66 例），全量约 71 例。`mvn test` 需本机执行。

## 4. 运行方式

- **本机验证**：`mvn test`。
- **跑评估**：生产对接真实 Agent 后 `EvalRunner.run(cases, agent)` 出 JSON/HTML 报告；单测已覆盖判分逻辑。
- **多租户**：请求带 `X-Tenant-Id: shopA` 头即切换租户（默认 `default`）。

## 5. 已知限制

- `CostTrackingAdvisor` 仅打日志，未接 Prometheus 等指标导出（可用 Spring AI observation + Micrometer 扩展，M9 后可选）。
- 评估集为最小 20 条，生产可按需扩到 50+；忠实度维在单测中用布尔注入，生产接 `OutputGuardrailService.judge`。
- 多租户隔离依赖各查询层已拼装的 filterExpression（M2/M3/M5 已实现），拦截器仅负责入口注入与清理。
