<script setup>
import { computed } from 'vue'

const props = defineProps({
  data: { type: Array, default: () => [] },   // [{ label, value }]
  color: { type: String, default: '#0066cc' }
})

// 归一化后生成柱形坐标（SVG 坐标系：y 向下）
const bars = computed(() => {
  const items = props.data || []
  if (!items.length) return []
  const max = Math.max(...items.map((d) => Number(d.value) || 0), 1)
  return items.map((d) => ({
    label: d.label,
    value: Number(d.value) || 0,
    h: Math.round(((Number(d.value) || 0) / max) * 100)
  }))
})

const W = 640
const H = 180
const padLeft = 34
const padBottom = 26
const plotH = H - padBottom - 8

const chart = computed(() => {
  const list = bars.value
  if (!list.length) return []
  const n = list.length
  const slot = (W - padLeft - 12) / n
  const bw = Math.min(slot * 0.6, 46)
  return list.map((b, i) => {
    const x = padLeft + i * slot + (slot - bw) / 2
    const y = 8 + plotH - (b.h / 100) * plotH
    const h = (b.h / 100) * plotH
    return { ...b, x: Math.round(x), y: Math.round(y), h: Math.max(Math.round(h), b.value > 0 ? 3 : 0), bw: Math.round(bw), labelX: Math.round(padLeft + i * slot + slot / 2) }
  })
})
</script>

<template>
  <div class="chart-bar">
    <svg :viewBox="`0 0 ${W} ${H}`" width="100%" role="img">
      <line v-for="i in 4" :key="'g' + i"
        :x1="padLeft" :x2="W - 4" :y1="8 + (plotH / 4) * i" :y2="8 + (plotH / 4) * i"
        stroke="var(--border)" stroke-width="0.5" stroke-dasharray="3 3" />
      <g v-for="(b, i) in chart" :key="i">
        <rect :x="b.x" :y="b.y" :width="b.bw" :height="b.h" rx="4"
          :fill="color" :opacity="0.9" />
        <text :x="b.x + b.bw / 2" :y="b.y - 6" text-anchor="middle"
          font-size="11" fill="var(--text-secondary)">{{ b.value }}</text>
        <text :x="b.labelX" :y="H - 8" text-anchor="middle"
          font-size="11" fill="var(--text-tertiary)">{{ b.label }}</text>
      </g>
    </svg>
    <p v-if="!bars.length" class="empty">暂无数据</p>
  </div>
</template>

<style scoped>
.chart-bar { width: 100%; }
.empty { text-align: center; color: var(--text-tertiary); font-size: 13px; padding: 40px 0; }
</style>
