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

// ---- 客户聊天 SSE ----
// 后端契约：先 event: citations（JSON 数组），再 event: token（文本块）
export function streamChat(conversationId, message, { onCitations, onToken, onDone }) {
  const url = `/chat/stream?conversationId=${encodeURIComponent(conversationId)}&message=${encodeURIComponent(message)}`
  const es = new EventSource(url)
  es.addEventListener('citations', (e) => {
    try {
      onCitations && onCitations(JSON.parse(e.data))
    } catch (err) {
      onCitations && onCitations([])
    }
  })
  es.addEventListener('token', (e) => onToken && onToken(e.data))
  es.onerror = () => {
    es.close()
    onDone && onDone()
  }
  return es
}
