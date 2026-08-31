<script setup>
import { ref, onMounted } from 'vue'
import { listConversations, conversationDetail } from '../api'

const PLATFORMS = [
  { code: '', label: '全部平台' },
  { code: 'taobao', label: '淘宝' },
  { code: 'jd', label: '京东' },
  { code: 'pdd', label: '拼多多' },
  { code: 'douyin', label: '抖音' },
  { code: 'kuaishou', label: '快手' },
  { code: 'wechat', label: '微信小店' },
  { code: 'official', label: '官方商城' },
  { code: 'unknown', label: '未标注' }
]

const platform = ref('')
const keyword = ref('')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const drawer = ref({ visible: false, conversationId: '', messages: [] })

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listConversations({
      platform: platform.value, keyword: keyword.value, page: page.value, size: size.value
    })
    rows.value = data.items || []
    total.value = data.total || 0
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function openDetail(row) {
  try {
    const data = await conversationDetail(row.conversationId)
    drawer.value = { visible: true, conversationId: row.conversationId, messages: data.messages || [] }
  } catch (e) {
    error.value = e.message || '加载详情失败'
  }
}

function search() { page.value = 1; load() }

onMounted(load)
</script>

<template>
  <div class="history">
    <header class="head">
      <h2>对话记录管理</h2>
      <span class="sub">按时间、平台、关键词追溯历史沟通</span>
    </header>

    <div class="filters">
      <el-select v-model="platform" class="f-platform" placeholder="平台" @change="search">
        <el-option v-for="p in PLATFORMS" :key="p.code" :label="p.label" :value="p.code" />
      </el-select>
      <el-input v-model="keyword" class="f-keyword" placeholder="搜索关键词…" clearable @keyup.enter="search" @clear="search" />
      <el-button type="primary" @click="search">检索</el-button>
    </div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="list">
      <div v-if="loading" class="loading">加载中…</div>
      <template v-else>
        <div v-for="row in rows" :key="row.conversationId" class="row" @click="openDetail(row)">
          <div class="row-main">
            <div class="row-title">{{ row.title || '（无标题）' }}</div>
            <div class="row-id">{{ row.conversationId }}</div>
          </div>
          <div class="row-meta">
            <span class="tag">{{ row.platform }}</span>
            <span class="count">{{ row.messageCount }} 条消息</span>
            <span class="time">{{ (row.updatedAt || '').replace('T', ' ').slice(0, 16) }}</span>
          </div>
        </div>
        <div v-if="!rows.length" class="empty">暂无会话记录，先去聊天窗聊几句</div>
      </template>
    </div>

    <div class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        @current-change="load"
      />
    </div>

    <el-drawer v-model="drawer.visible" :title="'会话 ' + drawer.conversationId" size="52%">
      <div v-for="(m, i) in drawer.messages" :key="i" class="msg" :class="m.role">
        <div class="msg-role">{{ m.role === 'user' ? '用户' : '客服' }}</div>
        <div class="msg-content">{{ m.content }}</div>
        <div class="msg-time">{{ (m.createdAt || '').replace('T', ' ').slice(0, 16) }}</div>
      </div>
      <el-empty v-if="!drawer.messages.length" description="无消息" :image-size="60" />
    </el-drawer>
  </div>
</template>

<style scoped>
.history { height: 100%; overflow-y: auto; padding: 24px 28px; }
.head { margin-bottom: 18px; }
.head h2 { margin: 0 0 4px; font-size: 18px; font-weight: 600; color: var(--text); }
.sub { font-size: 13px; color: var(--text-tertiary); }
.filters { display: flex; gap: 10px; margin-bottom: 16px; }
.f-platform { width: 140px; }
.f-keyword { width: 260px; }
.err { padding: 12px; border-radius: 8px; background: var(--bg-danger, #fdeceb); color: var(--danger); font-size: 13px; margin-bottom: 12px; }
.loading { padding: 60px 0; text-align: center; color: var(--text-tertiary); font-size: 13px; }
.empty { padding: 60px 0; text-align: center; color: var(--text-tertiary); font-size: 13px; }

.list { display: flex; flex-direction: column; gap: 10px; }
.row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px; border-radius: var(--radius-lg, 12px);
  background: var(--bg-card); border: 1px solid var(--border); cursor: pointer;
  transition: transform 160ms ease, box-shadow 160ms ease, border-color 160ms ease;
}
.row:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); border-color: var(--brand); }
.row-title { font-size: 14px; font-weight: 500; color: var(--text); margin-bottom: 4px; }
.row-id { font-size: 11px; color: var(--text-muted); font-family: var(--font-mono, monospace); }
.row-meta { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.tag {
  padding: 2px 8px; border-radius: 999px; font-size: 11px;
  background: var(--brand-soft); color: var(--brand);
}
.count { font-size: 12px; color: var(--text-secondary); }
.time { font-size: 12px; color: var(--text-tertiary); }
.pager { margin-top: 16px; display: flex; justify-content: flex-end; }

.msg { padding: 12px 14px; border-radius: 10px; margin-bottom: 12px; border: 1px solid var(--border); }
.msg.user { background: var(--brand-soft); }
.msg.assistant { background: var(--bg-card); }
.msg-role { font-size: 11px; color: var(--text-tertiary); margin-bottom: 4px; }
.msg-content { font-size: 14px; color: var(--text); line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.msg-time { font-size: 11px; color: var(--text-muted); margin-top: 6px; }
</style>
