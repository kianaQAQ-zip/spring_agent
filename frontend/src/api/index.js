// 后端 API 封装（走 Vite 代理，同源无 CORS 问题）

async function request(url, options = {}) {
  const res = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  const body = await res.json().catch(() => ({}))
  if (body.code !== 0) {
    throw new Error(body.message || '请求失败')
  }
  return body.data
}

// ---- 健康 ----
export function health() {
  return request('/chat/health')
}

// ---- 统计看板 ----
export function statsOverview() { return request('/stats/overview') }
export function statsTrend(days = 14) { return request(`/stats/trend?days=${days}`) }
export function statsPlatform() { return request('/stats/platform') }
export function statsHourly() { return request('/stats/hourly') }
export function statsIntent() { return request('/stats/intent') }

// ---- 历史会话 ----
export function listConversations(params = {}) {
  const q = new URLSearchParams()
  if (params.platform) q.set('platform', params.platform)
  if (params.keyword) q.set('keyword', params.keyword)
  if (params.from) q.set('from', params.from)
  if (params.to) q.set('to', params.to)
  q.set('page', params.page || 1)
  q.set('size', params.size || 20)
  return request(`/conversations?${q.toString()}`)
}
export function conversationDetail(id) { return request(`/conversations/${encodeURIComponent(id)}`) }

// ---- RAG 评估台 ----
export function evalSummary() { return request('/eval/summary') }
export function evalTrend(days = 14) { return request(`/eval/trend?days=${days}`) }
export function evalGaps(days = 30, limit = 20) { return request(`/eval/gaps?days=${days}&limit=${limit}`) }
export function evalGapSummary(days = 30) { return request(`/eval/gaps/summary?days=${days}`) }

// ---- 转人工工单 ----
export function listHandoff(status) {
  const q = status ? `?status=${encodeURIComponent(status)}` : ''
  return request(`/handoff${q}`)
}
export function claimHandoff(id, operator = 'agent') {
  return request(`/handoff/${encodeURIComponent(id)}/claim?operator=${encodeURIComponent(operator)}`, { method: 'POST' })
}
export function closeHandoff(id, operator = 'agent') {
  return request(`/handoff/${encodeURIComponent(id)}/close?operator=${encodeURIComponent(operator)}`, { method: 'POST' })
}

// ---- 报表导出 ----
export function exportConversations(params = {}) {
  const q = new URLSearchParams()
  if (params.platform) q.set('platform', params.platform)
  if (params.keyword) q.set('keyword', params.keyword)
  if (params.from) q.set('from', params.from)
  if (params.to) q.set('to', params.to)
  return `/export/conversations.csv?${q.toString()}`
}

// ---- 知识库 ----
export function uploadDoc(file) {
  const form = new FormData()
  form.append('file', file)
  return fetch('/kb/upload', { method: 'POST', body: form })
    .then((res) => res.json())
    .then((body) => {
      if (body.code !== 0) throw new Error(body.message || '上传失败')
      return body.data
    })
}

// ---- 坐席确认台 ----
export function listPending(conversationId) {
  return request(`/confirm/pending?conversationId=${encodeURIComponent(conversationId)}`)
}
export function confirmAction(id, body = {}) {
  return request(`/confirm/${id}`, { method: 'POST', body: JSON.stringify(body) })
}
export function modifyAndConfirm(id, body = {}) {
  return request(`/confirm/${id}`, { method: 'PUT', body: JSON.stringify(body) })
}
export function rejectAction(id, body = {}) {
  return request(`/confirm/${id}/reject`, { method: 'POST', body: JSON.stringify(body) })
}
export function cancelAction(id) {
  return request(`/confirm/${id}/cancel`, { method: 'POST' })
}

// ---- 溯源（M8b 源抽屉用，M8a 引用 chip 直接展示 citations.chunkContent） ----
export function getDocChunk(docId, chunkIndex) {
  return request(`/kb/doc/${docId}?chunk=${chunkIndex}`)
}

// ---- 知识库管理（M5 运营） ----
export function listKbDocuments(params = {}) {
  const q = new URLSearchParams()
  if (params.kbId) q.set('kbId', params.kbId)
  if (params.status) q.set('status', params.status)
  if (params.keyword) q.set('keyword', params.keyword)
  q.set('sort', params.sort || 'createdAt')
  q.set('order', params.order || 'desc')
  q.set('page', params.page || 1)
  q.set('size', params.size || 20)
  return request(`/kb/documents?${q.toString()}`)
}
export function getKbDocument(docId) {
  return request(`/kb/documents/${encodeURIComponent(docId)}`)
}
export function deleteKbDocument(docId) {
  return request(`/kb/documents/${encodeURIComponent(docId)}`, { method: 'DELETE' })
}
export function reprocessKbDocument(docId) {
  return request(`/kb/documents/${encodeURIComponent(docId)}/reprocess`, { method: 'POST' })
}
export function listKbs() { return request('/kb/list') }
export function createKb(name, description) {
  return request('/kb/create', { method: 'POST', body: JSON.stringify({ name, description }) })
}
export function deleteKb(kbId) {
  return request(`/kb/${encodeURIComponent(kbId)}`, { method: 'DELETE' })
}
export function kbStats() { return request('/kb/stats') }
export function kbRetrievalTest(body) {
  return request('/kb/retrieval-test', { method: 'POST', body: JSON.stringify(body) })
}

// ---- 客户聊天 SSE ----
// 后端契约：先 event: citations（{citations, degraded}），再 event: token，失败时 event: error
// degraded 非空 = 部分能力已静默失效（状态提取/查询改写/护栏），前端需如实告知用户
export function streamChat(conversationId, message, { platform, onCitations, onDegraded, onToken, onError, onDone }) {
  const p = encodeURIComponent(platform || 'unknown')
  const url = `/chat/stream?conversationId=${encodeURIComponent(conversationId)}&message=${encodeURIComponent(message)}&platform=${p}`
  const es = new EventSource(url)
  es.addEventListener('citations', (e) => {
    try {
      const payload = JSON.parse(e.data)
      // 兼容旧契约（纯数组）
      if (Array.isArray(payload)) {
        onCitations && onCitations(payload)
        return
      }
      onCitations && onCitations(payload.citations || [])
      onDegraded && onDegraded(payload.degraded || [])
    } catch (err) {
      onCitations && onCitations([])
    }
  })
  es.addEventListener('token', (e) => onToken && onToken(e.data))
  es.addEventListener('error', (e) => {
    try {
      const body = JSON.parse(e.data)
      onError && onError(body.message || '服务异常')
    } catch (_) {
      onError && onError('服务异常')
    }
    es.close()
    onDone && onDone()
  })
  es.onerror = () => {
    es.close()
    onDone && onDone()
  }
  return es
}
