<script setup>
import { ref, onMounted } from 'vue'
import { statsOverview, statsTrend, statsPlatform, statsHourly, statsIntent } from '../api'
import ChartLine from '../components/ChartLine.vue'
import ChartBar from '../components/ChartBar.vue'

const overview = ref({ conversations: 0, messages: 0, platforms: 0 })
const trend = ref([])
const platform = ref([])
const hourly = ref([])
const intent = ref([])
const loading = ref(true)
const error = ref('')

const INTENT_LABELS = {
  ORDER_QUERY: '订单查询', REFUND: '退款', ADDRESS_CHANGE: '改地址',
  COUPON: '优惠券', KNOWLEDGE_QA: '知识问答', CHITCHAT: '闲聊', UNKNOWN: '未知'
}

onMounted(async () => {
  try {
    const [ov, tr, pl, hr, it] = await Promise.all([
      statsOverview(), statsTrend(14), statsPlatform(), statsHourly(), statsIntent()
    ])
    overview.value = ov || overview.value
    trend.value = (tr || []).map((d) => ({ label: (d.day || '').slice(5), value: d.count }))
    platform.value = (pl || []).map((d) => ({ label: d.label || d.platform, value: d.count }))
    hourly.value = (hr || []).map((d) => ({ label: `${d.hour}时`, value: d.count }))
    intent.value = (it || []).map((d) => ({ label: INTENT_LABELS[d.intent] || d.intent, value: d.count }))
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="dashboard">
    <header class="head">
      <h2>咨询态势看板</h2>
      <span class="sub">按平台统计用户咨询量、时段分布与高频问题分类</span>
    </header>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="cards">
      <div class="card">
        <div class="card-label">累计会话</div>
        <div class="card-value">{{ overview.conversations }}</div>
      </div>
      <div class="card">
        <div class="card-label">消息总量</div>
        <div class="card-value">{{ overview.messages }}</div>
      </div>
      <div class="card">
        <div class="card-label">覆盖平台</div>
        <div class="card-value">{{ overview.platforms }}</div>
      </div>
      <div class="card">
        <div class="card-label">高频问题分类</div>
        <div class="card-value">{{ intent.length }}</div>
      </div>
    </div>

    <div v-if="loading" class="loading">加载中…</div>

    <template v-else>
      <section class="panel">
        <h3>咨询量趋势（近 14 天）</h3>
        <ChartLine :data="trend" color="#0066cc" />
      </section>

      <div class="grid">
        <section class="panel">
          <h3>平台分布</h3>
          <ChartBar :data="platform" color="#1d9e75" />
        </section>
        <section class="panel">
          <h3>时段分布</h3>
          <ChartBar :data="hourly" color="#e6a23c" />
        </section>
      </div>

      <section class="panel">
        <h3>高频问题分类（意图分布）</h3>
        <ChartBar :data="intent" color="#534ab7" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.dashboard { height: 100%; overflow-y: auto; padding: 24px 28px; }
.head { margin-bottom: 20px; }
.head h2 { margin: 0 0 4px; font-size: 18px; font-weight: 600; color: var(--text); }
.sub { font-size: 13px; color: var(--text-tertiary); }
.err { padding: 12px; border-radius: 8px; background: var(--bg-danger, #fdeceb); color: var(--danger); font-size: 13px; margin-bottom: 16px; }
.loading { padding: 60px 0; text-align: center; color: var(--text-tertiary); font-size: 13px; }

.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 22px; }
.card {
  padding: 18px; border-radius: var(--radius-lg, 12px);
  background: var(--bg-card); border: 1px solid var(--border);
  transition: transform 160ms ease, box-shadow 160ms ease;
}
.card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.card-label { font-size: 12px; color: var(--text-tertiary); margin-bottom: 8px; }
.card-value { font-size: 28px; font-weight: 600; color: var(--text); line-height: 1; }

.panel {
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: var(--radius-lg, 12px); padding: 18px 20px; margin-bottom: 18px;
}
.panel h3 { margin: 0 0 14px; font-size: 14px; font-weight: 600; color: var(--text); }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }

@media (max-width: 900px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  .grid { grid-template-columns: 1fr; }
}
</style>
