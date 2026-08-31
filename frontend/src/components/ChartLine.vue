<script setup>
import { computed } from 'vue'

const props = defineProps({
  data: { type: Array, default: () => [] },   // [{ label, value }]
  color: { type: String, default: '#0066cc' }
})

const W = 640
const H = 180
const padLeft = 40
const padBottom = 26
const plotH = H - padBottom - 8

const pts = computed(() => {
  const items = props.data || []
  if (!items.length) return { max: 1, points: [] }
  const max = Math.max(...items.map((d) => Number(d.value) || 0), 1)
  const n = items.length
  const step = n <= 1 ? 1 : (W - padLeft - 12) / (n - 1)
  const points = items.map((d, i) => ({
    x: Math.round(padLeft + i * step),
    y: Math.round(8 + plotH - ((Number(d.value) || 0) / max) * plotH),
    label: d.label,
    value: Number(d.value) || 0
  }))
  return { max, points }
})

const path = computed(() => {
  const p = pts.value.points
  if (!p.length) return ''
  return p.map((pt, i) => `${i === 0 ? 'M' : 'L'} ${pt.x} ${pt.y}`).join(' ')
})

const area = computed(() => {
  const p = pts.value.points
  if (!p.length) return ''
  const d = p.map((pt, i) => `${i === 0 ? 'M' : 'L'} ${pt.x} ${pt.y}`).join(' ')
  return `${d} L ${p[p.length - 1].x} ${8 + plotH} L ${p[0].x} ${8 + plotH} Z`
})
</script>

<template>
  <div class="chart-line">
    <svg :viewBox="`0 0 ${W} ${H}`" width="100%" role="img">
      <line v-for="i in 4" :key="'g' + i"
        :x1="padLeft" :x2="W - 4" :y1="8 + (plotH / 4) * i" :y2="8 + (plotH / 4) * i"
        stroke="var(--border)" stroke-width="0.5" stroke-dasharray="3 3" />
      <path v-if="area" :d="area" :fill="color" opacity="0.12" />
      <path v-if="path" :d="path" fill="none" :stroke="color" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
      <g v-for="(p, i) in pts.points" :key="'p' + i">
        <circle :cx="p.x" :cy="p.y" r="3.5" :fill="color" />
        <text v-if="pts.points.length <= 20" :x="p.x" :y="p.y - 8" text-anchor="middle"
          font-size="10" fill="var(--text-secondary)">{{ p.value }}</text>
      </g>
      <text v-for="(p, i) in pts.points.filter((_, idx) => (idx % Math.ceil(pts.points.length / 12)) === 0)"
        :key="'l' + i" :x="p.x" :y="H - 8" text-anchor="middle"
        font-size="11" fill="var(--text-tertiary)">{{ p.label }}</text>
    </svg>
    <p v-if="!pts.points.length" class="empty">暂无数据</p>
  </div>
</template>

<style scoped>
.chart-line { width: 100%; }
.empty { text-align: center; color: var(--text-tertiary); font-size: 13px; padding: 40px 0; }
</style>
