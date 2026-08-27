# M8a 验证记录 — 前端：客户聊天窗 + 坐席确认台（Vue3 + Vite + ElementPlus）

> 日期：2026-08-27
> 状态：✅ 已构建（代码已写）；`npm install && npm run dev` 需本机执行验证。

## 1. 交付内容

| 模块 | 文件 | 职责 |
| --- | --- | --- |
| 脚手架 | `frontend/package.json` `vite.config.js` `index.html` `src/main.js` `src/App.vue` | Vue3 + Vite + ElementPlus + Pinia + Router |
| 路由 | `src/router/index.js` | `/chat` 客户聊天窗、`/confirm` 坐席确认台 |
| API 封装 | `src/api/index.js` | REST + SSE（`EventSource` 接 `/chat/stream`） |
| 共享状态 | `src/stores/chat.js` | conversationId（localStorage 持久化，双视图共享） |
| 客户聊天窗 | `src/views/ChatView.vue` | 流式渲染 + `[n]` 引用 chip + 流式中禁用输入 |
| 坐席确认台 | `src/views/ConfirmView.vue` + `src/components/PendingCard.vue` | 2s 轮询 pending + 四动作 + 超时置灰 + 审计历史 |

## 2. 关键设计决策

1. **严格对齐后端 SSE 契约**：`event: citations`（JSON 数组）先发，`event: token`（文本块）后发；前端用 `EventSource.addEventListener('citations'/'token')` 分别处理。
2. **同源代理免 CORS**：Vite `server.proxy` 把 `/chat` `/kb` `/confirm` `/actuator` 转发到 `localhost:8080`，`EventSource` 走同源无跨域问题。
3. **引用 chip**：把回答里的 `[n]` 用正则拆成文本段+引用段，`[n]` 渲染为可点击 `el-tag`，点击弹出该引用的 `chunkContent`（M8a 用 citations 自带内容，M8b 接 `/kb/doc/{docId}?chunk=` 源抽屉）。
4. **流式串行（M6 对齐）**：流式未结束（`streaming=true`）时禁用输入框与发送按钮，防止并发覆盖会话状态。
5. **坐席台**：2s 轮询 `GET /confirm/pending?conversationId=`；`status=pending` 渲染可编辑卡片（四动作：确认/改参后确认/驳回/取消），`status=expired` 置灰禁用，其余（confirmed/rejected/cancelled）进审计历史表（谁/何时/结果）。
6. **双视图共享会话**：conversationId 存 Pinia + localStorage，客户窗发起 → 坐席台看到同一会话的待确认。

## 3. 运行方式

前置：后端已 `mvn spring-boot:run`（`localhost:8080`）。

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 `http://localhost:5173`：
1. **客户聊天窗**：问「我要退 ORD-1001 的耳机」→ 模型调用退款工具 → 回复「已提交，等待坐席确认」。
2. **坐席确认台**：切到该 tab，2s 内看到退款待确认卡 → 改金额后「改参后确认」→ 历史表出现「已执行」。
3. 引用溯源：问「七天无理由退货怎么算」→ 回答带 `[1][2]`，点击 chip 弹出来源。

> 本机 npm 缓存目录被沙箱拦截时，先 `export npm_config_cache="D:/Code/java/Spring_agent/frontend/.npm-cache"` 再 `npm install`（见跨项目备忘）。

## 4. 已知限制

- 前端未做 M7 的 `event: guardrail` 事件展示（后端当前仅告警）；事实一致性 FAIL 的降级话术待 M8a 迭代或 M9 补。
- `[n]` 引用 chip 用 citations 自带 `chunkContent` 展示；M8b 再补 `/kb/doc/{docId}?chunk=` 的全文源抽屉。
- 坐席台按 conversationId 过滤（后端 `listByConversation` 契约）；多会话聚合视图待 M9。
- 未接 WebSocket，客户窗为单向 SSE + 坐席台轮询，满足 demo 需求。
