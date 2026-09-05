<script setup>
import { ref, nextTick, computed, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { streamChat, getDocChunk, conversationDetail } from '../api'
import { useChatStore } from '../stores/chat'
import { renderMarkdown, exportAsMarkdown, exportAsWord, exportAsExcel, hasTable } from '../utils/markdown'

const store = useChatStore()
const input = ref('')
const messages = ref([])          // { id, role, content, citations, streaming, time }
const streaming = ref(false)
const allCitations = ref([])      // 累积的引用（引用面板用）
const showCitations = ref(false)
const listRef = ref(null)
const esRef = ref(null)

// 后端静默降级的能力（state-extract / query-rewrite / guardrail / chat / embedding）。
// 非空 = 智能体已部分失效，界面必须如实告知——不能让用户以为还在跟完整 Agent 聊。
const degraded = ref([])
const showDegraded = ref(false)

const DEGRADATION_LABELS = {
  'state-extract': '订单意图识别（查单、退款等工具不可用）',
  'query-rewrite': '查询理解优化（检索命中率下降）',
  guardrail: '回答事实校验（护栏已关闭）',
  chat: '对话生成',
  embedding: '知识库向量检索',
  persistence: '会话落库（历史记录可能丢失）',
  'action-exec': '写操作执行（退款/改地址/发券可能未真正生效）'
}

// 平台维度（Q2 人工标注）：客服在对话前选定，随会话落库，供按平台统计
const PLATFORMS = [
  { code: 'taobao', label: '淘宝' },
  { code: 'jd', label: '京东' },
  { code: 'pdd', label: '拼多多' },
  { code: 'douyin', label: '抖音' },
  { code: 'kuaishou', label: '快手' },
  { code: 'wechat', label: '微信小店' },
  { code: 'official', label: '官方商城' },
  { code: 'unknown', label: '未标注' }
]
const platform = ref(localStorage.getItem('ecom.platform') || 'unknown')

// 引用 popover
const popover = ref(null)         // { citation, x, y }
// 导出菜单
const exportMenu = ref(null)      // { id, content, x, y }
// 源文档阅读器抽屉（highlight=被引用段落，full=原文全文，highlightedFull=高亮后的原文 HTML）
const sourceDrawer = ref({ visible: false, title: '', highlight: '', full: '', highlightedFull: '' })

const SUGGESTED = [
  '七天无理由退货怎么算？',
  '我要退 ORD-1001 的耳机',
  '运费多少，偏远地区呢？',
  '优惠券能叠加使用吗？'
]

// ---- 智能滚动：用户上滑离开底部时暂停自动滚动，回到底部（或点按钮）恢复 ----
const autoScroll = ref(true)
const textareaRef = ref(null)
const composing = ref(false)      // 中文输入法组词中，Enter 不应发送
const lastQuestion = ref('')

function onListScroll() {
  const el = listRef.value
  if (!el) return
  autoScroll.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollToBottom(force = false) {
  if (!force && !autoScroll.value) return
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

function jumpToBottom() {
  autoScroll.value = true
  scrollToBottom(true)
}

// ---- 输入框高度自适应（P0 bug 修复：多行内容显示不全）----
function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  const max = 200
  el.style.height = Math.min(el.scrollHeight, max) + 'px'
  el.style.overflowY = el.scrollHeight > max ? 'auto' : 'hidden'
}

function focusInput() {
  nextTick(() => textareaRef.value && textareaRef.value.focus())
}

// Enter 发送 / Shift+Enter 换行 / 输入法组词中不发送
function onEnterKey() {
  if (!composing.value) send()
}

// ---- 后端错误 → 用户能看懂的提示（含 403 配额超限等场景）----
function friendlyError(msg) {
  const m = (msg || '').toLowerCase()
  if (m.includes('403') || m.includes('配额') || m.includes('quota') || m.includes('频率') || m.includes('limit')) {
    return '模型调用配额超限：当前套餐额度不足或触发限流。请到阿里云百炼控制台确认额度后重试，也可以切换其他模型继续使用。'
  }
  if (m.includes('401') || m.includes('unauthorized') || m.includes('api key')) {
    return 'API Key 无效或未配置：请检查后端环境变量 DASHSCOPE_API_KEY 是否正确。'
  }
  if (m.includes('timeout') || m.includes('超时')) {
    return '请求超时：模型响应过慢或网络不稳定，请稍后重试。'
  }
  if (m.includes('failed to fetch') || m.includes('网络')) {
    return '无法连接到服务：请确认后端已启动（8080 端口）后重试。'
  }
  return msg || '服务暂时不可用，请稍后重试。'
}

function now() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function send(text) {
  const q = (text ?? input.value).trim()
  if (!q || streaming.value) return
  input.value = ''
  lastQuestion.value = q
  messages.value.push({ id: Date.now(), role: 'user', content: q, time: now() })
  const assistantMsg = { id: Date.now() + 1, role: 'assistant', content: '', citations: [], streaming: true, time: now(), error: false }
  messages.value.push(assistantMsg)
  streaming.value = true
  scrollToBottom(true)

  esRef.value = streamChat(store.conversationId, q, {
    platform: platform.value,
    onCitations: (list) => {
      assistantMsg.citations = list
      const map = new Map(allCitations.value.map((c) => [c.index, c]))
      ;(list || []).forEach((c) => map.set(c.index, c))
      allCitations.value = Array.from(map.values()).sort((a, b) => a.index - b.index)
    },
    onDegraded: (list) => { degraded.value = list || [] },
    onToken: (token) => { assistantMsg.content += token; scrollToBottom() },
    onError: (msg) => {
      assistantMsg.error = true
      assistantMsg.content = friendlyError(msg)
      ElMessage.error('请求失败，详情见对话提示')
    },
    onDone: () => {
      assistantMsg.streaming = false
      streaming.value = false
      if (!assistantMsg.content) assistantMsg.content = '（无内容）'
      focusInput()
    }
  })
}

// 错误后重试：移除失败的对问答对，重新发送
function retry() {
  if (streaming.value || !lastQuestion.value) return
  // 尾部应是 [user, assistant(error)]
  const tail = messages.value.slice(-2)
  if (tail.length === 2 && tail[0].role === 'user' && tail[1].error) {
    messages.value.splice(-2, 2)
  }
  send(lastQuestion.value)
}

// 历史消息加载：进入页面时若当前会话已有落库记录，回放完整对话
async function loadHistory() {
  const cid = store.conversationId
  if (!cid) return
  try {
    const data = await conversationDetail(cid)
    const msgs = data.messages || []
    if (msgs.length) {
      messages.value = msgs.map((m, i) => ({
        id: Date.now() + i,
        role: m.role === 'user' ? 'user' : 'assistant',
        content: m.content || '',
        citations: [],
        streaming: false,
        error: false,
        time: (m.createdAt || '').slice(11, 16) || now()
      }))
      scrollToBottom(true)
    }
  } catch { /* 新会话本无历史，静默忽略 */ }
}

function newConversation() {
  if (streaming.value) return
  store.setConversationId('demo-' + Date.now())
  messages.value = []
  allCitations.value = []
  showCitations.value = false
  degraded.value = []
  autoScroll.value = true
  focusInput()
}

function changePlatform(code) {
  platform.value = code
  localStorage.setItem('ecom.platform', code)
}
// 渲染 AI 回答：markdown → HTML，并把正文引用标号 [n] 替换为可点击上标（data-ref）
function renderWithRefs(content) {
  let html = renderMarkdown(content || '')
  // [n] → 可点击上标；点击经全局事件代理（onDocClick）跳到对应源文档
  html = html.replace(/\[(\d+)\]/g, '<sup class="ref-inline" data-ref="$1">[$1]</sup>')
  return html
}

// 从内容里提取引用徽章（去重、保序）
function citationBadges(content, citations) {
  const map = new Map()
  ;(citations || allCitations.value).forEach((c) => map.set(c.index, c))
  const seen = new Set()
  const badges = []
  const re = /\[(\d+)\]/g
  let m
  while ((m = re.exec(content)) !== null) {
    const idx = parseInt(m[1], 10)
    if (seen.has(idx)) continue
    seen.add(idx)
    if (map.has(idx)) badges.push(map.get(idx))
  }
  return badges
}

function showSource(citation, e) {
  // popover 定位
  const rect = e && e.currentTarget ? e.currentTarget.getBoundingClientRect() : null
  if (rect) {
    let x = rect.left
    let y = rect.bottom + 8
    if (x + 360 > window.innerWidth) x = window.innerWidth - 370
    if (x < 10) x = 10
    if (y + 220 > window.innerHeight) y = rect.top - 230
    popover.value = { citation, x, y }
  } else {
    popover.value = null
  }
}

function closePopover() {
  popover.value = null
}

function viewFullText(citation) {
  closePopover()
  const c = citation
  const fallback = () => {
    sourceDrawer.value = {
      visible: true,
      title: `${c.source || '知识库来源'}${c.page ? ` · 第 ${c.page} 页` : ''}`,
      highlight: c.chunkContent || '',
      full: '',
      highlightedFull: ''
    }
  }
  if (c.chunkIndex == null || !c.docId) { fallback(); return }
  getDocChunk(c.docId, c.chunkIndex)
    .then((data) => {
      const full = data.parsedText || ''
      const chunk = data.chunkContent || c.chunkContent || ''
      sourceDrawer.value = {
        visible: true,
        title: `${data.source || c.source}${c.page ? ` · 第 ${c.page} 页` : ''}`,
        highlight: chunk,
        full,
        highlightedFull: highlightInFull(full, chunk)
      }
      // 阅读器打开后，滚动定位到被引用段落
      nextTick(() => {
        const el = document.querySelector('.full-content .hl-chunk')
        if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      })
    })
    .catch(fallback)
}

// 在原文全文里高亮命中段落：转义 HTML 后用 <mark> 包裹首个匹配片段
function highlightInFull(full, chunk) {
  if (!full || !chunk) return escapeHtml(full)
  const key = chunk.trim().slice(0, 60)
  if (!key) return escapeHtml(full)
  const idx = full.indexOf(key)
  if (idx < 0) return escapeHtml(full)
  const before = escapeHtml(full.slice(0, idx))
  const mid = escapeHtml(full.slice(idx, idx + key.length))
  const after = escapeHtml(full.slice(idx + key.length))
  return before + '<mark class="hl-chunk">' + mid + '</mark>' + after
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// 导出菜单
function openExport(e, msg) {
  const rect = e.currentTarget.getBoundingClientRect()
  let x = rect.right - 140
  let y = rect.bottom + 4
  if (x < 10) x = 10
  if (y + 150 > window.innerHeight) y = rect.top - 160
  exportMenu.value = { id: msg.id, content: msg.content, x, y }
}
function closeExport() { exportMenu.value = null }

function doExport(format) {
  const content = exportMenu.value.content
  if (format === 'markdown') exportAsMarkdown(content)
  else if (format === 'word') exportAsWord(content)
  else if (format === 'excel') exportAsExcel(content)
  ElMessage.success('已导出')
  exportMenu.value = null
}

// 点击外部关闭 popover/导出菜单；正文引用标号 data-ref 点击 → 打开源文档阅读器
function onDocClick(e) {
  const refEl = e.target.closest('[data-ref]')
  if (refEl) {
    const idx = parseInt(refEl.dataset.ref, 10)
    const c = allCitations.value.find((x) => x.index === idx)
    if (c) { viewFullText(c); return }
  }
  if (!e.target.closest('[data-cite]') && !e.target.closest('[data-popover]')) closePopover()
  if (!e.target.closest('[data-export-menu]') && !e.target.closest('[data-export-btn]')) closeExport()
}
onMounted(() => {
  document.addEventListener('click', onDocClick)
  loadHistory()
  autoResize()
  focusInput()
})
onUnmounted(() => document.removeEventListener('click', onDocClick))

// 输入内容变化 → 重算高度（P0 bug：多行内容显示不全）
watch(input, () => nextTick(autoResize))

const hasMessages = computed(() => messages.value.length > 0)
</script>

<template>
  <div class="chat">
    <!-- 主区 -->
    <div class="main">
      <header class="topbar">
        <div class="topbar-left">
          <el-icon :size="16" color="#0066cc"><ChatDotRound /></el-icon>
          <span class="topbar-title">客户聊天窗</span>

          <el-select
            v-model="platform"
            class="platform-select"
            size="small"
            @change="changePlatform"
          >
            <el-option v-for="p in PLATFORMS" :key="p.code" :label="p.label" :value="p.code" />
          </el-select>

          <div
            v-if="degraded.length"
            class="degraded-wrap"
            @mouseenter="showDegraded = true"
            @mouseleave="showDegraded = false"
          >
            <button class="degraded-chip" data-degraded type="button">
              <span class="degraded-dot"></span>
              基础问答模式
            </button>
            <transition name="fade">
              <div v-if="showDegraded" class="degraded-tip" data-degraded>
                <p class="tip-title">以下智能能力暂时不可用</p>
                <ul>
                  <li v-for="d in degraded" :key="d">{{ DEGRADATION_LABELS[d] || d }}</li>
                </ul>
                <p class="tip-foot">当前仅提供知识库检索问答，回答可能不够精准。</p>
              </div>
            </transition>
          </div>
        </div>
        <button class="topbar-btn" title="新会话" @click="newConversation">
          <el-icon :size="16"><Plus /></el-icon>
        </button>
      </header>

      <div ref="listRef" class="list" @scroll="onListScroll">
        <!-- 空状态 -->
        <div v-if="!hasMessages && !streaming" class="empty">
          <div class="empty-logo">
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
            </svg>
          </div>
          <p class="empty-title">有什么可以帮助您的？</p>
          <p class="empty-hint">基于售后知识库，我可以回答退货、退款、物流、改地址、优惠券等问题</p>
          <div class="suggestions">
            <button v-for="(q, i) in SUGGESTED" :key="i" class="chip" @click="send(q)">{{ q }}</button>
          </div>
        </div>

        <!-- 消息流 -->
        <div v-else class="chat-inner">
          <div v-for="m in messages" :key="m.id" class="msg-row" :class="m.role">
            <!-- 用户 -->
            <template v-if="m.role === 'user'">
              <div class="msg-body">
                <div class="bubble bubble-user">{{ m.content }}</div>
                <div class="time time-user">{{ m.time }}</div>
              </div>
              <div class="avatar avatar-user">U</div>
            </template>

            <!-- AI -->
            <template v-else>
              <div class="avatar avatar-ai">AI</div>
              <div class="msg-body">
                <div class="bubble bubble-ai" :class="{ 'bubble-error': m.error }">
                  <div v-if="m.error" class="error-head">
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" /><line x1="12" y1="9" x2="12" y2="13" /><line x1="12" y1="17" x2="12.01" y2="17" />
                    </svg>
                    <span>请求失败</span>
                    <button class="retry-btn" @click="retry">重试</button>
                  </div>
                  <div class="markdown-body" v-html="renderWithRefs(m.content)"></div>
                  <span v-if="m.streaming" class="cursor"></span>
                  <div v-if="citationBadges(m.content, m.citations).length" class="cite-row">
                    <span class="cite-label">参考来源：</span>
                    <button
                      v-for="c in citationBadges(m.content, m.citations)"
                      :key="c.index"
                      class="cite-badge"
                      data-cite
                      @click="showSource(c, $event)"
                    >{{ c.index }}</button>
                  </div>
                  <button class="export-btn" data-export-btn @click="openExport($event, m)" title="导出回答">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" />
                    </svg>
                  </button>
                </div>
                <div class="time time-ai">{{ m.time }}</div>
              </div>
            </template>
          </div>

          <!-- 流式等待 -->
          <div v-if="streaming && !messages[messages.length - 1]?.content" class="msg-row assistant">
            <div class="avatar avatar-ai">AI</div>
            <div class="msg-body">
              <div class="bubble bubble-ai dots">
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 回到底部：用户上滑离开底部时出现 -->
      <transition name="fade">
        <button v-if="!autoScroll && hasMessages" class="jump-btn" title="回到底部" @click="jumpToBottom">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </button>
      </transition>

      <!-- 输入区 -->
      <div class="input-area">
        <div class="input-inner">
          <div class="textarea-wrap">
            <textarea
              ref="textareaRef"
              v-model="input"
              class="textarea"
              rows="1"
              placeholder="输入您的问题，Enter 发送，Shift+Enter 换行"
              :disabled="streaming"
              @keydown.enter.exact.prevent="onEnterKey"
              @compositionstart="composing = true"
              @compositionend="composing = false"
            ></textarea>
          </div>
          <button
            v-if="allCitations.length"
            class="cite-toggle"
            :class="{ active: showCitations }"
            title="参考来源"
            @click="showCitations = !showCitations"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M4 19.5A2.5 2.5 0 016.5 17H20" /><path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z" /><line x1="8" y1="7" x2="16" y2="7" /><line x1="8" y1="11" x2="14" y2="11" />
            </svg>
          </button>
          <button class="send-btn" :class="{ disabled: !input.trim() || streaming }" :disabled="!input.trim() || streaming" @click="send()">
            <div v-if="streaming" class="spinner"></div>
            <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="22" y1="2" x2="11" y2="13" /><polygon points="22 2 15 22 11 13 2 9 22 2" />
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 引用面板 -->
    <aside v-if="showCitations && allCitations.length" class="cite-panel">
      <div class="cite-panel-head">
        <h3>参考来源</h3>
        <button class="close-btn" @click="showCitations = false">
          <el-icon :size="16"><Close /></el-icon>
        </button>
      </div>
      <div class="cite-panel-list">
        <div v-for="c in allCitations" :key="c.index" class="cite-card" @click="viewFullText(c)">
          <div class="cite-card-doc">
            <span class="cite-card-index">{{ c.index }}</span>
            {{ c.source || '知识库来源' }}
          </div>
          <div class="cite-card-snippet">{{ c.chunkContent || '（无内容）' }}</div>
        </div>
      </div>
    </aside>
  </div>

  <!-- 引用 popover -->
  <div v-if="popover" class="popover-overlay"></div>
  <div v-if="popover" class="popover" data-popover :style="{ top: popover.y + 'px', left: popover.x + 'px' }">
    <div class="popover-doc">[{{ popover.citation.index }}] {{ popover.citation.source || '知识库来源' }}</div>
    <div class="popover-snippet">{{ popover.citation.chunkContent || '（无内容）' }}</div>
    <button class="popover-full" @click="viewFullText(popover.citation)">查看原文全文 →</button>
  </div>

  <!-- 导出菜单 -->
  <div v-if="exportMenu" class="export-menu" data-export-menu :style="{ top: exportMenu.y + 'px', left: exportMenu.x + 'px' }">
    <button @click="doExport('markdown')">导出 Markdown</button>
    <button @click="doExport('word')">导出 Word</button>
    <button v-if="hasTable(exportMenu.content)" @click="doExport('excel')">导出 Excel</button>
  </div>

  <!-- 源文档阅读器抽屉 -->
  <el-drawer v-model="sourceDrawer.visible" :title="sourceDrawer.title" size="52%">
    <div class="reader">
      <div v-if="sourceDrawer.highlight" class="highlight">
        <div class="hl-label">被引用段落</div>
        <div class="hl-content">{{ sourceDrawer.highlight }}</div>
      </div>
      <div v-if="sourceDrawer.full" class="full">
        <div class="hl-label">文档原文（高亮处为引用位置）</div>
        <div class="full-content" v-html="sourceDrawer.highlightedFull || sourceDrawer.full"></div>
      </div>
      <el-empty v-if="!sourceDrawer.highlight && !sourceDrawer.full" description="无可用原文" :image-size="60" />
    </div>
  </el-drawer>
</template>

<style scoped>
.chat { display: flex; height: 100%; }

/* 主区 */
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; position: relative; }
.topbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 20px; flex-shrink: 0;
  border-bottom: 1px solid var(--border-light);
}
.topbar-left { display: flex; align-items: center; gap: 8px; }
.topbar-title { font-size: 15px; font-weight: 600; color: var(--text); }
.topbar-btn {
  width: 32px; height: 32px; border-radius: 8px; border: none;
  background: transparent; cursor: pointer; color: var(--text-tertiary);
  display: flex; align-items: center; justify-content: center;
  transition: background 150ms ease, color 150ms ease;
}
.topbar-btn:hover { background: var(--bg-hover); color: var(--text); }

/* 平台选择器：静默标注渠道，不抢视觉 */
.platform-select { width: 118px; margin-left: 6px; }
.platform-select :deep(.el-select__wrapper) {
  background: var(--bg-input);
  border-radius: 8px;
  box-shadow: none;
  border: 1px solid var(--border-input);
}
.platform-select :deep(.el-select__wrapper.is-focused) {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px rgba(0, 102, 204, 0.12);
}

/* 降级提示：琥珀色 pill，安静不打扰，hover 才展开说明 */
.degraded-wrap { position: relative; display: inline-flex; }
.degraded-chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 3px 10px; border-radius: 999px;
  background: color-mix(in srgb, var(--warning) 14%, transparent);
  border: 1px solid color-mix(in srgb, var(--warning) 30%, transparent);
  color: var(--warning);
  font-size: 12px; cursor: help; font-family: inherit; line-height: 1.5;
  animation: chipIn 280ms cubic-bezier(0.16, 1, 0.3, 1);
  transition: background 150ms ease;
}
.degraded-chip:hover { background: color-mix(in srgb, var(--warning) 22%, transparent); }
.degraded-dot { width: 5px; height: 5px; border-radius: 50%; background: var(--warning); flex-shrink: 0; }
@keyframes chipIn { from { opacity: 0; transform: translateY(-3px); } to { opacity: 1; transform: none; } }

.degraded-tip {
  position: absolute; top: calc(100% + 8px); left: 0; z-index: 20;
  width: 300px; padding: 12px 14px;
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 10px; box-shadow: var(--shadow-md);
}
.tip-title { margin: 0 0 8px; font-size: 12px; font-weight: 600; color: var(--text); }
.degraded-tip ul { margin: 0 0 8px; padding-left: 16px; }
.degraded-tip li { font-size: 12px; line-height: 1.7; color: var(--text-secondary); }
.tip-foot { margin: 0; font-size: 11px; line-height: 1.6; color: var(--text-tertiary); }

.fade-enter-active, .fade-leave-active { transition: opacity 160ms ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.list { flex: 1; overflow-y: auto; padding: 24px 20px; }

/* 空状态 */
.empty {
  height: 100%; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 12px;
  max-width: 520px; margin: 0 auto; padding: 40px;
}
.empty-logo {
  width: 56px; height: 56px; border-radius: 14px;
  background: var(--brand-gradient);
  display: flex; align-items: center; justify-content: center;
  box-shadow: var(--shadow-brand);
}
.empty-title { font-size: 18px; font-weight: 600; color: var(--text); margin: 0; }
.empty-hint { font-size: 13px; color: var(--text-tertiary); margin: 0; text-align: center; line-height: 1.6; }
.suggestions { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 8px; max-width: 480px; }
.chip {
  padding: 8px 16px; font-size: 13px; color: var(--text-secondary);
  background: var(--bg); border: 1px solid var(--border); border-radius: 20px;
  cursor: pointer; white-space: nowrap;
  transition: all 150ms ease;
}
.chip:hover { background: var(--brand-soft); border-color: var(--brand); color: var(--brand); }

/* 消息 */
.chat-inner { max-width: var(--chat-max-w); margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
.msg-row { display: flex; align-items: flex-start; }
.msg-row.user { justify-content: flex-end; }
.msg-row.assistant { justify-content: flex-start; }
.avatar {
  width: 30px; height: 30px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 600;
}
.avatar-user { background: var(--brand-soft); color: var(--brand); margin-left: 10px; }
.avatar-ai { background: var(--brand-gradient); color: #fff; margin-right: 10px; }
.msg-body { display: flex; flex-direction: column; gap: 4px; min-width: 0; max-width: 86%; }
.bubble { padding: 10px 14px; font-size: 14px; line-height: 1.7; word-break: break-word; position: relative; }
.bubble-user { background: var(--brand-soft); color: var(--text); border-radius: 12px; white-space: pre-wrap; }
.bubble-ai { background: transparent; color: var(--text); border-radius: 12px; }
.time { font-size: 11px; color: var(--text-muted); padding: 0 4px; }
.time-user { text-align: right; }
.time-ai { text-align: left; }

/* 流式光标 */
.cursor {
  display: inline-block; width: 2px; height: 1.1em;
  background: var(--brand); margin-left: 2px; vertical-align: text-bottom;
  animation: blink 0.5s infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

/* 三点加载 */
.dots { display: flex; align-items: center; gap: 6px; }
.dot { width: 6px; height: 6px; border-radius: 50%; background: var(--text-muted); animation: pulse 0.8s infinite; }
.dot:nth-child(2) { animation-delay: 0.15s; }
.dot:nth-child(3) { animation-delay: 0.3s; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }

/* 引用徽章 */
.cite-row { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; margin-top: 8px; }
.cite-label { font-size: 11px; color: var(--text-muted); margin-right: 4px; }
.cite-badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 18px; height: 18px; padding: 0 4px;
  border-radius: 50%; background: var(--brand); color: #fff;
  font-size: 10px; font-weight: 600; cursor: pointer; border: none; line-height: 1;
  margin: 0 2px; vertical-align: middle;
  transition: transform 150ms ease, box-shadow 150ms ease;
}
.cite-badge:hover { transform: scale(1.1); box-shadow: 0 2px 8px rgba(0, 102, 204, 0.4); }

/* 正文引用标号（v-html 注入，scoped 下需 :deep 才能命中） */
:deep(.ref-inline) {
  display: inline-block;
  min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 8px; background: var(--brand-soft); color: var(--brand);
  font-size: 11px; font-weight: 600; line-height: 16px; text-align: center;
  cursor: pointer; margin: 0 1px;
  transition: background 150ms ease, color 150ms ease, transform 150ms ease;
}
:deep(.ref-inline:hover) { background: var(--brand); color: #fff; transform: translateY(-1px); }

/* 文档阅读器命中段落高亮 */
:deep(.hl-chunk) {
  background: #ffe58f; color: #5c4400; padding: 1px 3px; border-radius: 3px;
  box-shadow: 0 0 0 2px rgba(255, 214, 102, 0.5);
}

/* 导出按钮（hover 显示） */
.export-btn {
  position: absolute; top: 4px; right: 4px;
  width: 28px; height: 28px; border-radius: 6px; border: none;
  background: transparent; cursor: pointer; color: var(--text-tertiary);
  display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: opacity 150ms ease, background 150ms ease, color 150ms ease;
}
.msg-row:hover .export-btn { opacity: 1; }
.export-btn:hover { background: var(--bg-hover); color: var(--text); }

/* 输入区 */
.input-area { flex-shrink: 0; border-top: 1px solid var(--border-light); padding: 12px 20px 16px; }
.input-inner { max-width: var(--chat-max-w); margin: 0 auto; display: flex; align-items: flex-end; gap: 10px; }
.textarea-wrap { flex: 1; position: relative; }
.textarea {
  width: 100%; min-height: 44px; max-height: 200px;
  padding: 10px 16px; border: 1px solid var(--border-input); border-radius: 12px;
  font-size: 14px; line-height: 1.5; color: var(--text); background: var(--bg-input);
  resize: none; outline: none; font-family: inherit;
  transition: border-color 200ms ease, box-shadow 200ms ease;
}
.textarea:focus { border-color: var(--brand); box-shadow: 0 0 0 3px rgba(0, 102, 204, 0.12); }

/* 回到底部悬浮按钮 */
.jump-btn {
  position: absolute; left: 50%; transform: translateX(-50%);
  bottom: 130px; z-index: 15;
  width: 36px; height: 36px; border-radius: 50%;
  border: 1px solid var(--border); background: var(--bg-card);
  color: var(--text-secondary); cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  box-shadow: var(--shadow-md);
  transition: all 150ms ease;
}
.jump-btn:hover { color: var(--brand); border-color: var(--brand); }

/* 错误气泡：浅红底 + 警告头 + 重试 */
.bubble-error { background: color-mix(in srgb, #e24b4a 8%, transparent); border: 1px solid color-mix(in srgb, #e24b4a 28%, transparent); }
.error-head { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; color: #e24b4a; font-size: 13px; font-weight: 600; }
.retry-btn {
  margin-left: auto; padding: 2px 12px; border-radius: 999px;
  border: 1px solid color-mix(in srgb, #e24b4a 40%, transparent);
  background: transparent; color: #e24b4a; font-size: 12px; cursor: pointer;
  transition: all 150ms ease;
}
.retry-btn:hover { background: #e24b4a; color: #fff; }
.cite-toggle {
  width: 40px; height: 40px; border-radius: 50%; border: 1px solid var(--border-input);
  background: var(--bg-card); cursor: pointer; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  color: var(--text-tertiary); transition: all 150ms ease;
}
.cite-toggle.active { background: var(--brand-soft); color: var(--brand); border-color: var(--brand); }
.send-btn {
  width: 40px; height: 40px; border-radius: 50%; border: none;
  background: var(--brand); cursor: pointer; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  transition: opacity 200ms ease, background 200ms ease, transform 150ms ease;
}
.send-btn:not(.disabled):active { transform: scale(0.92); }
.send-btn.disabled { opacity: 0.4; cursor: not-allowed; }
.send-btn:hover:not(.disabled) { background: #0052a3; }
.spinner { width: 18px; height: 18px; border: 2px solid #fff; border-top-color: transparent; border-radius: 50%; animation: spin 0.6s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* 引用面板 */
.cite-panel {
  width: var(--citation-w); flex-shrink: 0; background: var(--bg-card);
  border-left: 1px solid var(--border); display: flex; flex-direction: column; overflow: hidden;
}
.cite-panel-head {
  padding: 16px; border-bottom: 1px solid var(--border);
  display: flex; align-items: center; justify-content: space-between;
}
.cite-panel-head h3 { font-size: 16px; font-weight: 600; color: var(--text); margin: 0; }
.close-btn {
  width: 28px; height: 28px; border-radius: 6px; border: none; background: none;
  cursor: pointer; color: var(--text-tertiary);
  display: flex; align-items: center; justify-content: center;
  transition: background 150ms ease, color 150ms ease;
}
.close-btn:hover { background: var(--bg-hover); color: var(--text); }
.cite-panel-list { flex: 1; overflow-y: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; }
.cite-card {
  padding: 12px; border-radius: var(--radius-sm); border: 1px solid var(--border);
  background: var(--bg-card); cursor: pointer;
  transition: transform 150ms ease, box-shadow 150ms ease;
}
.cite-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.cite-card-doc { font-size: 13px; font-weight: 600; color: var(--text); margin-bottom: 4px; display: flex; align-items: center; }
.cite-card-index {
  display: inline-flex; align-items: center; justify-content: center;
  width: 20px; height: 20px; border-radius: 50%; background: var(--brand-soft);
  color: var(--brand); font-size: 11px; font-weight: 600; margin-right: 8px; flex-shrink: 0;
}
.cite-card-snippet { font-size: 12px; color: var(--text-secondary); line-height: 1.5; }

/* popover */
.popover-overlay { position: fixed; inset: 0; z-index: 1000; }
.popover {
  position: fixed; z-index: 1001; background: var(--bg-card); border: 1px solid var(--border);
  border-radius: 10px; box-shadow: var(--shadow-lg); padding: 14px;
  max-width: 360px; min-width: 260px;
  animation: pop 150ms ease;
}
@keyframes pop { from { opacity: 0; transform: translateY(-4px); } to { opacity: 1; transform: translateY(0); } }
.popover-doc { font-size: 13px; font-weight: 600; color: var(--text); margin-bottom: 8px; }
.popover-snippet { font-size: 13px; color: var(--text-secondary); line-height: 1.5; max-height: 140px; overflow-y: auto; }
.popover-full { margin-top: 10px; border: none; background: none; color: var(--brand); font-size: 13px; cursor: pointer; padding: 0; }

/* 导出菜单 */
.export-menu {
  position: fixed; z-index: 1002; background: var(--bg-card);
  border-radius: 10px; box-shadow: var(--shadow-lg); padding: 4px;
  min-width: 140px; border: 1px solid var(--border);
  animation: pop 150ms ease;
}
.export-menu button {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  font-size: 13px; color: var(--text); cursor: pointer; border-radius: 6px;
  border: none; background: none; width: 100%; text-align: left;
  transition: background 150ms ease;
}
.export-menu button:hover { background: var(--bg-hover); }

/* 源抽屉 */
.reader { display: flex; flex-direction: column; gap: 16px; }
.highlight { margin-bottom: 4px; }
.hl-label { font-size: 12px; color: var(--text-tertiary); margin-bottom: 6px; font-weight: 600; }
.hl-content { background: var(--brand-soft); border: 1px solid var(--border); border-radius: 6px; padding: 12px; white-space: pre-wrap; color: var(--text); }
.full-content { max-height: 60vh; overflow-y: auto; white-space: pre-wrap; font-size: 13px; color: var(--text-secondary); line-height: 1.8; }

/* 响应式：窄屏收紧留白，气泡占满可用宽度 */
@media (max-width: 640px) {
  .list { padding: 16px 10px; }
  .input-area { padding: 10px 10px 12px; }
  .msg-body { max-width: 94%; }
  .bubble { padding: 9px 12px; }
  .jump-btn { bottom: 120px; }
}
</style>
