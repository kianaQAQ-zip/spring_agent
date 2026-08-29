<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction, modifyAndConfirm, rejectAction, cancelAction } from '../api'

const props = defineProps({ action: { type: Object, required: true } })
const emit = defineEmits(['changed'])

const operator = ref('agent01')
const busy = ref(false)
const params = ref(parseParams(props.action.paramsJson))
const isExpired = computed(() => props.action.status === 'expired')

function parseParams(json) {
  try { return JSON.parse(json || '{}') } catch { return {} }
}

function toolLabel(tool) {
  return { refund: '退款', changeAddress: '修改收货地址', issueCoupon: '发放优惠券' }[tool] || tool
}

async function run(fn, okMsg) {
  busy.value = true
  try {
    await fn()
    ElMessage.success(okMsg)
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    busy.value = false
  }
}

function doConfirm() {
  run(() => confirmAction(props.action.id, { operator: operator.value }), '已确认执行')
}
function doModifyConfirm() {
  run(() => modifyAndConfirm(props.action.id, { params: params.value, operator: operator.value }), '已改参并确认')
}
function doReject() {
  run(() => rejectAction(props.action.id, { operator: operator.value }), '已驳回')
}
function doCancel() {
  run(() => cancelAction(props.action.id), '已取消')
}
</script>

<template>
  <el-card class="pending-card" shadow="hover" :class="{ expired: isExpired }">
    <template #header>
      <div class="card-head">
        <el-tag :type="isExpired ? 'info' : 'warning'" size="small">{{ toolLabel(action.tool) }}</el-tag>
        <span v-if="isExpired" class="expired-tag">已超时过期</span>
        <span class="id">#{{ action.id.slice(0, 8) }}</span>
        <span class="time">发起于 {{ new Date(action.createdAt).toLocaleTimeString() }}</span>
      </div>
    </template>

    <div class="params">
      <div v-for="(v, k) in params" :key="k" class="param-row">
        <span class="key">{{ k }}</span>
        <el-input v-model="params[k]" size="small" style="width: 220px" :disabled="isExpired" />
      </div>
    </div>

    <div v-if="!isExpired" class="ops">
      <el-input v-model="operator" size="small" placeholder="坐席" style="width: 120px" />
      <el-button size="small" type="primary" :loading="busy" @click="doConfirm">确认</el-button>
      <el-button size="small" type="success" :loading="busy" @click="doModifyConfirm">改参后确认</el-button>
      <el-button size="small" type="danger" :loading="busy" @click="doReject">驳回</el-button>
      <el-button size="small" :loading="busy" @click="doCancel">取消</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.pending-card { margin-bottom: 12px; border-radius: var(--radius-md); }
.pending-card.expired { opacity: 0.55; }
.card-head { display: flex; align-items: center; gap: 10px; }
.expired-tag { color: var(--text-tertiary); font-size: 12px; }
.id { font-family: monospace; color: var(--text-tertiary); font-size: 13px; }
.time { color: var(--text-muted); font-size: 12px; }
.params { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; }
.param-row { display: flex; align-items: center; gap: 10px; }
.key { width: 90px; font-size: 13px; color: var(--text-secondary); }
.ops { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
</style>
