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
</script>

<template>
  <div class="kb">
    <h3>知识库上传</h3>

    <el-upload drag :http-request="handleUpload" :show-file-list="false"
               accept=".pdf,.txt,.doc,.docx,.html" :disabled="uploading">
      <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
      <div class="el-upload__text">拖拽文件到此处，或 <em>点击上传</em></div>
      <template #tip>
        <div class="el-upload__tip">
          支持 PDF / Word / 文本，经 doc-processor 解析 → 柔性分块 → 向量化入库（不可达时 Tika 兜底）
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
</template>

<style scoped>
.kb { padding: 16px; max-width: 720px; }
h3 { margin: 8px 0 16px; font-size: 17px; color: #303133; }
.result { margin-top: 20px; }
.flags { color: #e6a23c; font-size: 13px; margin-top: 10px; }
</style>
