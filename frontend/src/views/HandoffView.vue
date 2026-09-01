<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listHandoff, claimHandoff, closeHandoff } from '../api'

const REASON_LABELS = {
  USER_REQUEST: '用户要求转人工',
  NEGATIVE_EMOTION: '情绪负面',
  GUARDRAIL_FAIL: '事实校验失败',
  NO_HIT: '检索未命中'
}
const REASON_LEVEL = {
  USER_REQUEST: 'high',
  NEGATIVE_EMOTION: 'high',
  GUARDRAIL_FAIL: 'mid',
  NO_HIT: 'low'
}
const STATUS_LABELS = { open: '待受理', claimed: '处理中', closed: '已关闭' }

const tabs = [
  { value: '', label: '全部' },
  { value: 'open', label: '待受理' },
  { value: 'claimed', label: '处理中' },
  { value: 'closed', label: '已关闭' }
]

const activeTab = ref('')
const rows = ref([])
const loading = ref(false)
const error = ref('')
const detail = ref(null)   // 当前展开的工单（含 context）

async function load() {
  loading.value = true
  error.value = ''
  try {
    rows.value = await listHandoff(activeTab.value)
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function switchTab(v) { activeTab.value = v; load() }

async function claim(row) {
  try {
    await claimHandoff(row.id)
    ElMessage.success('已受理')
    load()
  } catch (e) { ElMessage.error(e.message || '受理失败') }
}

async function close(row) {
  try {
    await closeHandoff(row.id)
    ElMessage.success('已关闭')
    load()
  } catch (e) { ElMessage.error(e.message || '关闭失败') }
}

function toggleDetail(row) {
  detail.value = detail.value === row.id ? null : row.id
}

function parseContext(row) {
  try {
    const arr = JSON.parse(row.context || '[]')
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

function fmtTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }

onMounted(load)
</script>

<template>
  <div class="handoff">
    <header class="head">
      <h2>转人工工单</h2>
      <span class="sub">Agent 搞不定的会话，带着上下文交接给人工</span>
    </header>

    <div class="tabs">
      <button
        v-for="t in tabs" :key="t.value"
        class="tab" :class="{ active: activeTab === t.value }"
        @click="switchTab(t.value)"
      >{{ t.label }}</button>
    </div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="list">
      <div v-if="loading" class="loading">加载中…</div>
      <template v-else>
        <div v-for="row in rows" :key="row.id" class="ticket">
          <div class="ticket-head" @click="toggleDetail(row)">
            <span class="badge" :class="'lvl-' + (REASON_LEVEL[row.reason] || 'low')">
              {{ REASON_LABELS[row.reason] || row.reason }}
            </span>
            <span class="conv">{{ row.conversation_id }}</span>
            <span class="meta">{{ row.platform }} · {{ fmtTime(row.created_at) }}</span>
            <span class="status">{{ STATUS_LABELS[row.status] || row.status }}</span>
          </div>

          <div class="ticket-detail">{{ row.detail }}</div>

          <div v-if="detail === row.id" class="ticket-context">
            <div class="ctx-title">对话上下文（最近 10 条）</div>
            <div v-for="(m, i) in parseContext(row)" :key="i" class="ctx-msg" :class="m.role">
              <span class="ctx-role">{{ m.role === 'user' ? '用户' : '客服' }}：</span>{{ m.content }}
            </div>
            <div v-if="!parseContext(row).length" class="ctx-empty">无上下文快照</div>
          </div>

          <div class="ticket-actions">
            <el-button v-if="row.status === 'open'" type="primary" size="small" @click="claim(row)">受理</el-button>
            <el-button v-if="row.status !== 'closed'" size="small" @click="close(row)">关闭</el-button>
          </div>
        </div>
        <div v-if="!rows.length" class="empty">暂无工单</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.handoff { height: 100%; overflow-y: auto; padding: 24px 28px; }
.head { margin-bottom: 18px; }
.head h2 { margin: 0 0 4px; font-size: 18px; font-weight: 600; color: var(--text); }
.sub { font-size: 13px; color: var(--text-tertiary); }
.tabs { display: flex; gap: 8px; margin-bottom: 16px; }
.tab {
  padding: 6px 14px; border-radius: 999px; border: 1px solid var(--border);
  background: var(--bg-card); color: var(--text-secondary); cursor: pointer; font-size: 13px;
  transition: all 150ms ease;
}
.tab.active { background: var(--brand); color: #fff; border-color: var(--brand); }
.err { padding: 12px; border-radius: 8px; background: var(--bg-danger, #fdeceb); color: var(--danger); font-size: 13px; margin-bottom: 12px; }
.loading { padding: 60px 0; text-align: center; color: var(--text-tertiary); font-size: 13px; }
.empty { padding: 60px 0; text-align: center; color: var(--text-tertiary); font-size: 13px; }

.list { display: flex; flex-direction: column; gap: 12px; }
.ticket {
  padding: 14px 16px; border-radius: var(--radius-lg, 12px);
  background: var(--bg-card); border: 1px solid var(--border);
}
.ticket-head { display: flex; align-items: center; gap: 12px; cursor: pointer; }
.badge { padding: 2px 8px; border-radius: 999px; font-size: 11px; flex-shrink: 0; }
.lvl-high { background: #fdeceb; color: #e24b4a; }
.lvl-mid { background: #fdf3e0; color: #e6a23c; }
.lvl-low { background: var(--brand-soft); color: var(--brand); }
.conv { font-family: var(--font-mono, monospace); font-size: 12px; color: var(--text-secondary); }
.meta { font-size: 12px; color: var(--text-tertiary); }
.status { margin-left: auto; font-size: 12px; color: var(--text-tertiary); }
.ticket-detail { margin-top: 8px; font-size: 13px; color: var(--text-secondary); line-height: 1.6; }
.ticket-context {
  margin-top: 10px; padding: 12px; border-radius: 8px;
  background: var(--bg); border: 1px solid var(--border);
}
.ctx-title { font-size: 12px; font-weight: 600; color: var(--text); margin-bottom: 8px; }
.ctx-msg { font-size: 13px; color: var(--text); line-height: 1.6; margin-bottom: 6px; word-break: break-word; }
.ctx-role { color: var(--text-tertiary); font-size: 12px; }
.ctx-empty { font-size: 12px; color: var(--text-tertiary); }
.ticket-actions { margin-top: 10px; display: flex; gap: 8px; justify-content: flex-end; }
</style>
