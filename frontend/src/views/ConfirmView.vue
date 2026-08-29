<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { listPending } from '../api'
import { useChatStore } from '../stores/chat'
import PendingCard from '../components/PendingCard.vue'

const store = useChatStore()
const actions = ref([])
const loading = ref(false)
let timer = null

const activeCards = computed(() =>
  actions.value.filter((a) => a.status === 'pending' || a.status === 'expired'))
const history = computed(() =>
  actions.value.filter((a) => !['pending', 'expired'].includes(a.status)))

async function refresh() {
  loading.value = true
  try {
    actions.value = await listPending(store.conversationId)
  } catch (e) {
    // 后端未起时静默，轮询持续
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  refresh()
  timer = setInterval(refresh, 2000)
})
onUnmounted(() => clearInterval(timer))

function toolLabel(tool) {
  return { refund: '退款', changeAddress: '修改收货地址', issueCoupon: '发放优惠券' }[tool] || tool
}

const statusLabel = {
  confirmed: '已确认', rejected: '已驳回', cancelled: '已取消'
}
function statusType(status) {
  return { confirmed: 'success', rejected: 'danger', cancelled: 'info' }[status] || 'info'
}

function formatResult(result) {
  try {
    const r = JSON.parse(result || '{}')
    if (r.status === 'EXECUTED') return `已执行 · ${toolLabel(r.tool)} · 操作员 ${r.operator || '-'}`
    return result
  } catch { return result }
}
</script>

<template>
  <div class="confirm">
    <header class="topbar">
      <div class="topbar-left">
        <el-icon :size="16" color="#0066cc"><Tickets /></el-icon>
        <span class="topbar-title">坐席确认台</span>
      </div>
      <div class="topbar-right">
        <span class="conv-id">会话 {{ store.conversationId.slice(0, 12) }}</span>
        <el-tag size="small" type="info" effect="plain">2 秒轮询</el-tag>
        <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </header>

    <div class="body">
      <section v-if="activeCards.length" class="section">
        <h3>待确认（{{ activeCards.length }}）</h3>
        <PendingCard v-for="a in activeCards" :key="a.id" :action="a" @changed="refresh" />
      </section>
      <el-empty v-else description="暂无待确认动作" :image-size="80" />

      <section v-if="history.length" class="section">
        <h3>审计历史</h3>
        <el-table :data="history" size="small" border>
          <el-table-column label="工具" width="130">
            <template #default="{ row }">{{ toolLabel(row.tool) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ statusLabel[row.status] || row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operator" label="操作员" width="100" />
          <el-table-column label="结果" min-width="220">
            <template #default="{ row }">{{ formatResult(row.result) }}</template>
          </el-table-column>
          <el-table-column label="时间" width="100">
            <template #default="{ row }">
              {{ row.executedAt ? new Date(row.executedAt).toLocaleTimeString() : '-' }}
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </div>
</template>

<style scoped>
.confirm { display: flex; flex-direction: column; height: 100%; }
.topbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 20px; flex-shrink: 0; border-bottom: 1px solid var(--border-light);
}
.topbar-left { display: flex; align-items: center; gap: 8px; }
.topbar-title { font-size: 15px; font-weight: 600; color: var(--text); }
.topbar-right { display: flex; align-items: center; gap: 10px; }
.conv-id { font-size: 12px; color: var(--text-tertiary); font-family: monospace; }
.body { flex: 1; overflow-y: auto; padding: 20px; }
.section { margin-bottom: 24px; }
h3 { margin: 8px 0 14px; font-size: 15px; color: var(--text); }
</style>
