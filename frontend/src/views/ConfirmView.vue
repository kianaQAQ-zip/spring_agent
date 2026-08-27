<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { listPending } from '../api'
import { useChatStore } from '../stores/chat'
import PendingCard from '../components/PendingCard.vue'

const store = useChatStore()
const actions = ref([])
const loading = ref(false)
let timer = null

// 待确认 + 已过期（置灰）显示为卡片；其余进审计历史
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
    <div class="toolbar">
      <span class="label">会话 ID</span>
      <el-input v-model="store.conversationId" size="small" style="width: 220px"
                @change="(v) => { store.setConversationId(v); refresh() }" />
      <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
      <el-tag size="small" type="info">2 秒轮询</el-tag>
    </div>

    <div class="body">
      <section v-if="activeCards.length">
        <h3>待确认（{{ activeCards.length }}）</h3>
        <PendingCard v-for="a in activeCards" :key="a.id" :action="a" @changed="refresh" />
      </section>
      <el-empty v-else description="暂无待确认动作" :image-size="80" />

      <section v-if="history.length">
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
.toolbar { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-bottom: 1px solid #ebeef5; }
.label { font-size: 13px; color: #909399; }
.body { flex: 1; overflow-y: auto; padding: 16px; }
h3 { margin: 8px 0 12px; font-size: 15px; color: #303133; }
</style>
