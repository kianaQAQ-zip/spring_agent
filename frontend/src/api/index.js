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
