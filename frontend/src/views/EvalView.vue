<script setup>
import { ref, onMounted } from 'vue'
import { evalSummary, evalTrend } from '../api'
import ChartLine from '../components/ChartLine.vue'

const summary = ref({})
const trend = ref([])
const loading = ref(true)
const error = ref('')

const fmtPct = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? Math.round(n * 100) + '%' : '—'
}

onMounted(async () => {
  try {
    const [s, t] = await Promise.all([evalSummary(), evalTrend(14)])
    summary.value = s || {}
    trend.value = (t || []).map((d) => ({ label: (d.day || '').slice(5), value: d.conversations }))
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="eval">
    <header class="head">
      <h2>RAG 效果评估台</h2>
      <span class="sub">命中率 · 引用准确率 · 成本趋势 —— 全部来自真实对话，无假数据</span>
    </header>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="cards">
      <div class="card accent-blue">
        <div class="card-label">检索命中率</div>
        <div class="card-value">{{ fmtPct(summary.hit_rate) }}</div>
        <div class="card-hint">检索返回文档的对话占比</div>
      </div>
      <div class="card accent-red">
        <div class="card-label">平均越界引用</div>
        <div class="card-value">{{ summary.avg_out_of_range ?? '—' }}</div>
        <div class="card-hint">越界引用越多 = 模型越爱编造来源</div>
      </div>
      <div class="card accent-amber">
        <div class="card-label">平均引用数</div>
        <div class="card-value">{{ summary.avg_citations ?? '—' }}</div>
        <div class="card-hint">每次回答平均引用几条知识库</div>
      </div>
      <div class="card accent-green">
        <div class="card-label">累计成本</div>
        <div class="card-value">¥{{ Number(summary.total_cost || 0).toFixed(4) }}</div>
        <div class="card-hint">共 {{ summary.total_tokens || 0 }} token（估算）</div>
      </div>
    </div>

    <div v-if="loading" class="loading">加载中…</div>

    <section v-else class="panel">
      <h3>对话量趋势（近 14 天）</h3>
      <ChartLine :data="trend" color="#534ab7" />
    </section>
  </div>
</template>

<style scoped>
.eval { height: 100%; overflow-y: auto; padding: 24px 28px; }
.head { margin-bottom: 20px; }
.head h2 { margin: 0 0 4px; font-size: 18px; font-weight: 600; color: var(--text); }
.sub { font-size: 13px; color: var(--text-tertiary); }
.err { padding: 12px; border-radius: 8px; background: var(--bg-danger, #fdeceb); color: var(--danger); font-size: 13px; margin-bottom: 16px; }
.loading { padding: 60px 0; text-align: center; color: var(--text-tertiary); font-size: 13px; }

.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 22px; }
.card {
  padding: 18px; border-radius: var(--radius-lg, 12px);
  background: var(--bg-card); border: 1px solid var(--border); border-top: 3px solid var(--brand);
  transition: transform 160ms ease, box-shadow 160ms ease;
}
.card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.accent-blue { border-top-color: #0066cc; }
.accent-red { border-top-color: #e24b4a; }
.accent-amber { border-top-color: #e6a23c; }
.accent-green { border-top-color: #1d9e75; }
.card-label { font-size: 12px; color: var(--text-tertiary); margin-bottom: 8px; }
.card-value { font-size: 26px; font-weight: 600; color: var(--text); line-height: 1.1; margin-bottom: 6px; }
.card-hint { font-size: 11px; color: var(--text-muted); }

.panel {
  background: var(--bg-card); border: 1px solid var(--border);
  border-radius: var(--radius-lg, 12px); padding: 18px 20px;
}
.panel h3 { margin: 0 0 14px; font-size: 14px; font-weight: 600; color: var(--text); }

@media (max-width: 900px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
}
</style>
