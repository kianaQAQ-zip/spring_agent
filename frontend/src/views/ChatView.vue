<script setup>
import { ref, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { streamChat } from '../api'
import { useChatStore } from '../stores/chat'

const store = useChatStore()
const input = ref('')
const messages = ref([])          // { role, content, citations, streaming }
const streaming = ref(false)
const esRef = ref(null)
const listRef = ref(null)
const sourceDialog = ref({ visible: false, title: '', content: '' })

// 把 [n] 拆成文本段与引用段，便于渲染可点击 chip
function parseSegments(content) {
  const segs = []
  const re = /(\[\d+\])/g
  let last = 0
  let m
  while ((m = re.exec(content)) !== null) {
    if (m.index > last) segs.push({ type: 'text', text: content.slice(last, m.index) })
    segs.push({ type: 'cite', index: parseInt(m[1].slice(1, -1), 10) })
    last = m.index + m[0].length
  }
  if (last < content.length) segs.push({ type: 'text', text: content.slice(last) })
  return segs
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  const assistantMsg = { role: 'assistant', content: '', citations: [], streaming: true }
  messages.value.push(assistantMsg)
  streaming.value = true
  scrollToBottom()

  esRef.value = streamChat(store.conversationId, text, {
    onCitations: (list) => { assistantMsg.citations = list },
    onToken: (token) => { assistantMsg.content += token; scrollToBottom() },
    onDone: () => {
      assistantMsg.streaming = false
      streaming.value = false
      if (!assistantMsg.content) assistantMsg.content = '（无内容）'
    }
  })
}

function showSource(msg, index) {
  const c = (msg.citations || []).find((x) => x.index === index)
  if (!c) {
    ElMessage.warning('未找到该引用来源')
    return
  }
  sourceDialog.value = {
    visible: true,
    title: `${c.source || '知识库来源'}${c.page ? ` · 第 ${c.page} 页` : ''}`,
    content: c.chunkContent || '（无内容）'
  }
}

function newConversation() {
  if (streaming.value) return
  store.setConversationId('demo-' + Date.now())
  messages.value = []
}
</script>

<template>
  <div class="chat">
    <div class="toolbar">
      <span class="label">会话 ID</span>
      <el-input v-model="store.conversationId" size="small" style="width: 220px"
                @change="(v) => store.setConversationId(v)" />
      <el-button size="small" text type="primary" @click="newConversation">新会话</el-button>
    </div>

    <div ref="listRef" class="list">
      <el-empty v-if="messages.length === 0" description="开始提问吧，例如「七天无理由退货怎么算」" />
      <div v-for="(m, i) in messages" :key="i" class="row" :class="m.role">
        <div class="bubble">
          <template v-if="m.role === 'assistant'">
            <template v-for="(seg, j) in parseSegments(m.content)" :key="j">
              <el-tag v-if="seg.type === 'cite'" size="small" class="cite"
                      @click="showSource(m, seg.index)">[{{ seg.index }}]</el-tag>
              <span v-else>{{ seg.text }}</span>
            </template>
            <span v-if="m.streaming" class="cursor">▍</span>
          </template>
          <template v-else>{{ m.content }}</template>
        </div>
      </div>
    </div>

    <div class="inputbar">
      <el-input v-model="input" type="textarea" :rows="2" resize="none"
                placeholder="输入消息，回车发送"
                :disabled="streaming"
                @keydown.enter.exact.prevent="send" />
      <el-button type="primary" :loading="streaming" :disabled="!input.trim()" @click="send">
        {{ streaming ? '回复中…' : '发送' }}
      </el-button>
    </div>

    <el-dialog v-model="sourceDialog.visible" :title="sourceDialog.title" width="640px">
      <div class="source-content">{{ sourceDialog.content }}</div>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat { display: flex; flex-direction: column; height: 100%; }
.toolbar {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; border-bottom: 1px solid #ebeef5;
}
.label { font-size: 13px; color: #909399; }
.list { flex: 1; overflow-y: auto; padding: 16px; background: #f5f7fa; }
.row { display: flex; margin-bottom: 12px; }
.row.user { justify-content: flex-end; }
.row.assistant { justify-content: flex-start; }
.bubble {
  max-width: 72%; padding: 10px 14px; border-radius: 10px;
  font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-word;
}
.row.user .bubble { background: #409eff; color: #fff; }
.row.assistant .bubble { background: #fff; border: 1px solid #ebeef5; }
.cite { margin: 0 2px; cursor: pointer; }
.cursor { animation: blink 1s steps(1) infinite; color: #409eff; }
@keyframes blink { 50% { opacity: 0; } }
.inputbar { display: flex; gap: 10px; padding: 12px 16px; border-top: 1px solid #ebeef5; }
.source-content { max-height: 420px; overflow-y: auto; white-space: pre-wrap; }
</style>
