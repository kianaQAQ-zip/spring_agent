<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadDoc } from '../api'

const uploading = ref(false)
const result = ref(null)

async function handleUpload(options) {
  uploading.value = true
  result.value = null
  try {
    result.value = await uploadDoc(options.file)
    ElMessage.success('入库完成')
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

const statusType = (s) =>
  s === 'INGESTED' ? 'success' : s === 'QUARANTINED' ? 'warning' : 'info'

const FORMATS = ['PDF', 'Word', 'Excel', 'Markdown', 'TXT']
</script>

<template>
  <div class="kb">
    <header class="topbar">
      <div class="topbar-left">
        <el-icon :size="16" color="#0066cc"><FolderOpened /></el-icon>
        <span class="topbar-title">知识库上传</span>
      </div>
    </header>

    <div class="body">
      <div class="formats">
        <span class="formats-label">支持格式：</span>
        <span v-for="f in FORMATS" :key="f" class="fmt-chip">{{ f }}</span>
      </div>

      <el-upload drag :http-request="handleUpload" :show-file-list="false"
                 accept=".pdf,.txt,.md,.markdown,.docx,.xlsx" :disabled="uploading">
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处，或 <em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">
            经 doc-processor 解析（保留标题层级/表格/工作表结构）→ 柔性分块 → 向量化入库；doc-processor 不可达时降级 Tika 纯文本兜底
          </div>
        </template>
      </el-upload>

      <el-card v-if="result" class="result" shadow="never">
        <template #header>
          <span>入库结果</span>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(result.status)">{{ result.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="文档 ID">{{ result.docId }}</el-descriptions-item>
          <el-descriptions-item label="源文件">{{ result.source }}</el-descriptions-item>
          <el-descriptions-item label="分块数">{{ result.chunkCount }}</el-descriptions-item>
          <el-descriptions-item label="清洗评分">{{ result.cleanScore }}</el-descriptions-item>
        </el-descriptions>
        <p v-if="result.flags && result.flags.length" class="flags">
          标记：{{ result.flags.join(', ') }}
        </p>
        <el-alert v-if="result.status === 'QUARANTINED'" type="warning" :closable="false"
                  title="清洗评分过低，已隔离复核，未入库" />
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.kb { display: flex; flex-direction: column; height: 100%; }
.topbar {
  display: flex; align-items: center;
  padding: 12px 20px; flex-shrink: 0; border-bottom: 1px solid var(--border-light);
}
.topbar-left { display: flex; align-items: center; gap: 8px; }
.topbar-title { font-size: 15px; font-weight: 600; color: var(--text); }
.body { flex: 1; overflow-y: auto; padding: 24px 20px; max-width: 720px; }
.formats { display: flex; align-items: center; gap: 6px; margin-bottom: 16px; flex-wrap: wrap; }
.formats-label { font-size: 13px; color: var(--text-tertiary); }
.fmt-chip {
  padding: 3px 10px; border-radius: 12px; font-size: 12px;
  background: var(--brand-soft); color: var(--brand);
}
.result { margin-top: 20px; }
.flags { color: var(--warning); font-size: 13px; margin-top: 10px; }
</style>
