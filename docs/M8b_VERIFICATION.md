# M8b 验证记录 — 前端：知识库上传页 + 引用源抽屉

> 日期：2026-08-27
> 状态：✅ 已构建（代码已写）；`npm run dev` 需本机执行验证。

## 1. 交付内容

| 模块 | 文件 | 职责 |
| --- | --- | --- |
| 知识库上传页 | `frontend/src/views/KbView.vue` | 拖拽上传 → `POST /kb/upload` → 展示 chunk 数/评分/隔离态 |
| 引用源抽屉 | `frontend/src/views/ChatView.vue`（增强） | 点 `[n]` chip → `GET /kb/doc/{docId}?chunk=` → 命中片段高亮 + 原文全文 |
| 路由收敛 | `frontend/src/router/index.js` + `App.vue` | 三视图（聊天/确认台/KB 上传）顶栏导航 |
| API | `frontend/src/api/index.js` | 新增 `uploadDoc`（multipart） |
| 后端小补 | `rag/Citation` + `rag/RetrievalPipeline` | Citation 增补 `chunkIndex` 字段，供源抽屉定位 chunk |

## 2. 关键设计决策

1. **引用溯源补全 chunkIndex**：M5 的 `Citation` 缺 `chunkIndex`，导致前端无法调 `/kb/doc/{docId}?chunk=` 定位。本里程碑在 `Citation` 增补 `chunkIndex`（从 `Document.metadata.chunk_index` 提取），SSE `citations` 事件随之携带。
2. **源抽屉三段式**：命中片段（高亮块）+ 原文全文（滚动区）；`chunkIndex` 缺失时降级为 citations 自带的 `chunkContent`，保证不空窗。
3. **大文本渲染**：`parsed_text` 全文放抽屉内 `max-height + overflow-y` 滚动，避免整页渲染卡顿（§M8b 面试点）。
4. **上传闭环**：`el-upload` 自定义 `http-request` 走 `FormData`，展示 `INGESTED`/`QUARANTINED` 双态与 `flags`，与 M2 质量门禁对齐。

## 3. 运行方式

```bash
cd frontend
npm install
npm run dev
```

浏览器 `http://localhost:5173`：
1. **知识库上传**：拖入售后政策 PDF → 显示「INGESTED + 分块数」。
2. **客户聊天窗**：问「七天无理由退货怎么算」→ 回答带 `[1][2]`。
3. 点 `[n]` chip → 右侧抽屉弹出「命中片段」高亮 + 「原文全文」。

## 4. 已知限制

- 命中片段高亮为「片段块 + 全文」两段式，未做全文内字符级 `<mark>` 高亮（chunk 内容与全文边界可能不完全重合）；如需精确高亮需后端返回 chunk 在全文中的偏移。
- 同 doc 多 chunk 合并去重在 M9 源抽屉聚合时补。
