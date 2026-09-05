<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  uploadDoc, listKbDocuments, getKbDocument, deleteKbDocument, reprocessKbDocument,
  listKbs, createKb, deleteKb, kbStats, kbRetrievalTest
} from '../api'

// ---- 知识库列表（左侧） ----
const kbs = ref([])
const currentKbId = ref('')   // '' = 全部

// ---- 统计卡 ----
const stats = ref({})

// ---- 文档表格 ----
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const statusFilter = ref('')
const sortBy = ref('createdAt')
const order = ref('desc')
const selection = ref([])     // 多选

// ---- 上传 ----
const uploading = ref(false)

// ---- 详情抽屉 ----
const detail = ref({ visible: false, loading: false, data: null })

// ---- 创建知识库 ----
const createVisible = ref(false)
const createForm = ref({ name: '', description: '' })

// ---- 检索测试 ----
const testVisible = ref(false)
const testForm = ref({ kbId: '', query: '', topK: 5 })
const testHits = ref([])
const testing = ref(false)

const STATUS_META = {
  INGESTED: { type: 'success', label: '已入库' },
  QUARANTINED: { type: 'warning', label: '已隔离' },
  FAILED: { type: 'danger', label: '失败' },
  PROCESSING: { type: 'info', label: '处理中' }
}
const statusMeta = (s) => STATUS_META[s] || { type: 'info', label: s || '—' }

function formatSize(bytes) {
  if (bytes == null) return '—'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(2) + ' MB'
}
function formatTime(t) { return (t || '').replace('T', ' ').slice(0, 19) }
function fmtScore(v) { return v == null ? '—' : Math.round(v * 100) + '%' }

// ---- 加载 ----
async function loadKbs() {
  try {
    kbs.value = await listKbs()
  } catch (e) {
    ElMessage.error(e.message || '加载知识库失败')
  }
}
async function loadStats() {
  try { stats.value = await kbStats() } catch { /* 统计失败静默 */ }
}
async function loadDocuments() {
  loading.value = true
  try {
    const data = await listKbDocuments({
      kbId: currentKbId.value, status: statusFilter.value, keyword: keyword.value,
      sort: sortBy.value, order: order.value, page: page.value, size: size.value
    })
    rows.value = data.items || []
    total.value = data.total || 0
  } catch (e) {
    ElMessage.error(e.message || '加载文档失败')
  } finally {
    loading.value = false
  }
}

function refreshAll() {
  loadKbs(); loadStats(); loadDocuments()
}

function selectKb(kbId) {
  currentKbId.value = kbId
  page.value = 1
  loadDocuments()
}

function search() { page.value = 1; loadDocuments() }

// ---- 上传 ----
async function handleUpload(options) {
  uploading.value = true
  try {
    const r = await uploadDoc(options.file)
    if (r.status === 'QUARANTINED') {
      ElMessage.warning(`已上传但被隔离（cleanScore=${r.cleanScore}），详见列表`)
    } else {
      ElMessage.success(`入库完成：${r.chunkCount} 个分块`)
    }
    refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

// ---- 详情 ----
async function openDetail(row) {
  detail.value = { visible: true, loading: true, data: null }
  try {
    detail.value.data = await getKbDocument(row.doc_id)
  } catch (e) {
    ElMessage.error(e.message || '加载详情失败')
  } finally {
    detail.value.loading = false
  }
}

// ---- 删除 ----
async function removeDoc(row) {
  try {
    await ElMessageBox.confirm(`确定删除文档「${row.source}」及其全部向量吗？`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await deleteKbDocument(row.doc_id)
    ElMessage.success('已删除')
    refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

async function batchDelete() {
  if (!selection.value.length) { ElMessage.warning('请先勾选要删除的文档'); return }
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selection.value.length} 个文档吗？`, '批量删除', { type: 'warning' })
  } catch { return }
  try {
    for (const row of selection.value) {
      await deleteKbDocument(row.doc_id)
    }
    ElMessage.success('批量删除完成')
    refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '批量删除失败')
  }
}

// ---- 重新处理 ----
async function reprocessDoc(row) {
  try {
    const r = await reprocessKbDocument(row.doc_id)
    ElMessage.success(`重新处理完成：${r.chunkCount} 个分块`)
    refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '重新处理失败')
  }
}

// ---- 知识库 CRUD ----
async function submitCreateKb() {
  if (!createForm.value.name.trim()) { ElMessage.warning('请输入知识库名称'); return }
  try {
    await createKb(createForm.value.name, createForm.value.description)
    ElMessage.success('知识库已创建')
    createVisible.value = false
    createForm.value = { name: '', description: '' }
    loadKbs()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  }
}

async function removeKb(kb) {
  try {
    await ElMessageBox.confirm(`确定删除知识库「${kb.name}」及其全部文档吗？`, '删除知识库', { type: 'warning' })
  } catch { return }
  try {
    await deleteKb(kb.kb_id)
    ElMessage.success('已删除')
    if (currentKbId.value === kb.kb_id) currentKbId.value = ''
    refreshAll()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

// ---- 检索测试 ----
function openTest() {
  testForm.value.kbId = currentKbId.value
  testForm.value.query = ''
  testHits.value = []
  testVisible.value = true
}
async function runTest() {
  if (!testForm.value.query.trim()) { ElMessage.warning('请输入测试问题'); return }
  testing.value = true
  try {
    testHits.value = await kbRetrievalTest(testForm.value)
    if (!testHits.value.length) ElMessage.info('未召回任何片段（知识库可能为空或覆盖不足）')
  } catch (e) {
    ElMessage.error(e.message || '检索测试失败')
  } finally {
    testing.value = false
  }
}

// ---- 下载原文（导出） ----
function exportDoc(row) {
  window.open(`/kb/doc/${encodeURIComponent(row.doc_id)}/export?format=md`, '_blank')
}

onMounted(() => {
  loadKbs(); loadStats(); loadDocuments()
})
</script>

<template>
  <div class="kb">
    <!-- 顶部操作栏 -->
    <header class="topbar">
      <div class="topbar-left">
        <el-icon :size="16" color="#0066cc"><FolderOpened /></el-icon>
        <span class="topbar-title">知识库管理</span>
      </div>
      <div class="topbar-actions">
        <el-button @click="openTest"><el-icon :size="14"><Search /></el-icon>检索测试</el-button>
        <el-button @click="createVisible = true"><el-icon :size="14"><Plus /></el-icon>新建知识库</el-button>
        <el-button type="primary" :loading="uploading" @click="$refs.uploadRef.open()">
          <el-icon :size="14"><Upload /></el-icon>上传文档
        </el-button>
      </div>
    </header>

    <div class="layout">
      <!-- 左侧知识库列表 -->
      <aside class="side">
        <div class="side-head">
          <span>知识库</span>
          <button class="side-add" title="新建知识库" @click="createVisible = true">
            <el-icon :size="14"><Plus /></el-icon>
          </button>
        </div>
        <div class="kb-list">
          <div class="kb-item" :class="{ active: currentKbId === '' }" @click="selectKb('')">
            <div class="kb-name">全部文档</div>
            <div class="kb-meta">{{ stats.total_docs ?? 0 }} 个文档</div>
          </div>
          <div v-for="kb in kbs" :key="kb.kb_id" class="kb-item" :class="{ active: currentKbId === kb.kb_id }">
            <div class="kb-main" @click="selectKb(kb.kb_id)">
              <div class="kb-name">{{ kb.name }}</div>
              <div class="kb-meta">{{ kb.doc_count }} 文档 · {{ kb.total_chunks }} chunks</div>
            </div>
            <button v-if="kb.kb_id !== 'default'" class="kb-del" title="删除知识库" @click="removeKb(kb)">
              <el-icon :size="13"><Delete /></el-icon>
            </button>
          </div>
        </div>
        <el-upload ref="uploadRef" :http-request="handleUpload" :show-file-list="false"
                   accept=".pdf,.txt,.md,.markdown,.docx,.xlsx" style="display:none">
          <span />
        </el-upload>
      </aside>

      <!-- 右侧主区 -->
      <main class="main">
        <!-- 统计卡 -->
        <div class="stat-cards">
          <div class="stat"><div class="stat-v">{{ stats.total_docs ?? 0 }}</div><div class="stat-l">总文档数</div></div>
          <div class="stat"><div class="stat-v">{{ stats.total_chunks ?? 0 }}</div><div class="stat-l">总 chunks</div></div>
          <div class="stat"><div class="stat-v">{{ stats.vector_rows ?? 0 }}</div><div class="stat-l">向量条数</div></div>
          <div class="stat"><div class="stat-v">{{ stats.kb_count ?? 0 }}</div><div class="stat-l">知识库数</div></div>
          <div class="stat"><div class="stat-v">{{ stats.month_new ?? 0 }}</div><div class="stat-l">本月新增</div></div>
          <div class="stat warn"><div class="stat-v">{{ stats.quarantined ?? 0 }}</div><div class="stat-l">隔离/失败</div></div>
        </div>

        <!-- 筛选栏 -->
        <div class="toolbar">
          <el-input v-model="keyword" placeholder="按文档名称搜索…" clearable class="kw" @keyup.enter="search" @clear="search">
            <template #prefix><el-icon :size="14"><Search /></el-icon></template>
          </el-input>
          <el-select v-model="statusFilter" placeholder="全部状态" clearable class="sel" @change="search">
            <el-option label="已入库" value="INGESTED" />
            <el-option label="已隔离" value="QUARANTINED" />
          </el-select>
          <el-select v-model="sortBy" class="sel-sort" @change="search">
            <el-option label="按上传时间" value="createdAt" />
            <el-option label="按分块数" value="chunkCount" />
            <el-option label="按名称" value="source" />
          </el-select>
          <el-button @click="order = order === 'desc' ? 'asc' : 'desc'; search()">
            {{ order === 'desc' ? '降序 ↓' : '升序 ↑' }}
          </el-button>
          <div class="spacer" />
          <el-button type="danger" plain :disabled="!selection.length" @click="batchDelete">
            批量删除{{ selection.length ? `（${selection.length}）` : '' }}
          </el-button>
        </div>

        <!-- 文档表格 -->
        <el-table :data="rows" v-loading="loading" class="table" @selection-change="selection = $event" row-key="doc_id" height="100%">
          <el-table-column type="selection" width="44" />
          <el-table-column label="文档名称" min-width="200">
            <template #default="{ row }">
              <a class="doc-link" @click="openDetail(row)">{{ row.source }}</a>
            </template>
          </el-table-column>
          <el-table-column label="文档 ID" min-width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.doc_id }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="kb_id" label="知识库" width="110" />
          <el-table-column label="上传时间" width="160">
            <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
          </el-table-column>
          <el-table-column label="大小" width="90">
            <template #default="{ row }">{{ formatSize(row.file_size) }}</template>
          </el-table-column>
          <el-table-column label="分块" width="70" prop="chunk_count" align="center" />
          <el-table-column label="清洗分" width="80" align="center">
            <template #default="{ row }">{{ fmtScore(row.clean_score) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="statusMeta(row.status).type" size="small">{{ statusMeta(row.status).label }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
              <el-button link type="primary" size="small" @click="exportDoc(row)">导出</el-button>
              <el-button link size="small" @click="reprocessDoc(row)">重处理</el-button>
              <el-button link type="danger" size="small" @click="removeDoc(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无文档，点击右上角「上传文档」开始" :image-size="80" />
          </template>
        </el-table>

        <!-- 分页 -->
        <div class="pager">
          <el-pagination v-model:current-page="page" :page-size="size" :total="total"
                         layout="prev, pager, next, total" @current-change="loadDocuments" />
        </div>
      </main>
    </div>

    <!-- 文档详情抽屉 -->
    <el-drawer v-model="detail.visible" title="文档详情" size="48%">
      <div v-loading="detail.loading">
        <template v-if="detail.data">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="文档 ID"><span class="mono">{{ detail.data.doc_id }}</span></el-descriptions-item>
            <el-descriptions-item label="源文件">{{ detail.data.source }}</el-descriptions-item>
            <el-descriptions-item label="知识库">{{ detail.data.kb_id }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statusMeta(detail.data.status).type" size="small">{{ statusMeta(detail.data.status).label }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="分块数">{{ detail.data.chunk_count }}</el-descriptions-item>
            <el-descriptions-item label="清洗分">{{ fmtScore(detail.data.clean_score) }}</el-descriptions-item>
            <el-descriptions-item label="文件大小">{{ formatSize(detail.data.file_size) }}</el-descriptions-item>
            <el-descriptions-item label="原文长度">{{ detail.data.text_length }} 字符</el-descriptions-item>
            <el-descriptions-item label="上传时间">{{ formatTime(detail.data.created_at) }}</el-descriptions-item>
          </el-descriptions>

          <h4 class="chunk-title">分段内容预览（{{ detail.data.chunks?.length || 0 }} 段）</h4>
          <div class="chunk-list">
            <div v-for="c in detail.data.chunks" :key="c.id" class="chunk">
              <div class="chunk-meta">
                <span class="chunk-idx">#{{ c.chunk_index }}</span>
                <span v-if="c.block_type" class="chunk-type">{{ c.block_type }}</span>
                <span v-if="c.heading_path" class="chunk-heading">{{ c.heading_path }}</span>
                <span class="chunk-tokens">{{ c.token_count }} tokens</span>
              </div>
              <div class="chunk-content">{{ c.content }}</div>
            </div>
          </div>
        </template>
        <el-empty v-if="!detail.data && !detail.loading" description="无数据" :image-size="60" />
      </div>
    </el-drawer>

    <!-- 新建知识库对话框 -->
    <el-dialog v-model="createVisible" title="新建知识库" width="460px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="如：产品知识库 / 售后政策库" maxlength="64" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" type="textarea" :rows="3" placeholder="（可选）知识库用途说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreateKb">创建</el-button>
      </template>
    </el-dialog>

    <!-- 检索测试对话框 -->
    <el-dialog v-model="testVisible" title="检索测试（零成本，不走 LLM）" width="620px">
      <div class="test-row">
        <el-input v-model="testForm.query" placeholder="输入测试问题，如：七天无理由退货怎么算？" @keyup.enter="runTest" />
        <el-button type="primary" :loading="testing" @click="runTest">测试</el-button>
      </div>
      <div v-if="testHits.length" class="test-hits">
        <div v-for="(h, i) in testHits" :key="i" class="hit">
          <div class="hit-meta">
            <span class="hit-rank">#{{ i + 1 }}</span>
            <span class="hit-src">{{ h.source }}</span>
            <span v-if="h.headingPath" class="hit-heading">{{ h.headingPath }}</span>
          </div>
          <div class="hit-snippet">{{ h.snippet }}</div>
        </div>
      </div>
      <el-empty v-else-if="!testing" description="输入问题后点击「测试」查看召回片段" :image-size="60" />
    </el-dialog>
  </div>
</template>

<style scoped>
.kb { display: flex; flex-direction: column; height: 100%; }

.topbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 20px; flex-shrink: 0; border-bottom: 1px solid var(--border-light);
}
.topbar-left { display: flex; align-items: center; gap: 8px; }
.topbar-title { font-size: 15px; font-weight: 600; color: var(--text); }
.topbar-actions { display: flex; gap: 8px; }

.layout { flex: 1; display: flex; min-height: 0; }

/* 左侧知识库列表 */
.side {
  width: 220px; flex-shrink: 0; border-right: 1px solid var(--border);
  display: flex; flex-direction: column; background: var(--bg-card);
}
.side-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px 8px; font-size: 12px; font-weight: 600;
  color: var(--text-tertiary); text-transform: uppercase; letter-spacing: 0.5px;
}
.side-add {
  width: 22px; height: 22px; border-radius: 6px; border: none; background: transparent;
  cursor: pointer; color: var(--text-tertiary); display: flex; align-items: center; justify-content: center;
}
.side-add:hover { background: var(--bg-hover); color: var(--text); }
.kb-list { flex: 1; overflow-y: auto; padding: 4px 8px; display: flex; flex-direction: column; gap: 2px; }
.kb-item {
  display: flex; align-items: center; gap: 6px; padding: 9px 12px; border-radius: 8px;
  cursor: pointer; transition: background 120ms ease;
}
.kb-item:hover { background: var(--bg-hover); }
.kb-item.active { background: var(--brand-soft); }
.kb-main { flex: 1; min-width: 0; }
.kb-name { font-size: 13px; font-weight: 500; color: var(--text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.kb-item.active .kb-name { color: var(--brand); font-weight: 600; }
.kb-meta { font-size: 11px; color: var(--text-muted); margin-top: 2px; }
.kb-del {
  width: 22px; height: 22px; border-radius: 6px; border: none; background: transparent;
  cursor: pointer; color: var(--text-muted); display: flex; align-items: center; justify-content: center;
  opacity: 0; transition: opacity 120ms ease;
}
.kb-item:hover .kb-del { opacity: 1; }
.kb-del:hover { color: var(--danger); background: var(--bg-hover); }

/* 主区 */
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; padding: 16px 20px; }

.stat-cards { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-bottom: 14px; }
.stat {
  padding: 12px 14px; border-radius: 10px; background: var(--bg-card);
  border: 1px solid var(--border);
}
.stat-v { font-size: 20px; font-weight: 600; color: var(--text); line-height: 1.2; }
.stat-l { font-size: 11px; color: var(--text-tertiary); margin-top: 3px; }
.stat.warn .stat-v { color: var(--warning); }

.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.kw { width: 220px; }
.sel { width: 120px; }
.sel-sort { width: 130px; }
.spacer { flex: 1; }

.table { flex: 1; min-height: 0; }
.doc-link { color: var(--brand); cursor: pointer; font-weight: 500; }
.doc-link:hover { text-decoration: underline; }
.mono { font-family: var(--font-mono, monospace); font-size: 12px; color: var(--text-secondary); }

.pager { display: flex; justify-content: flex-end; padding-top: 12px; }

/* 详情 chunk */
.chunk-title { margin: 18px 0 10px; font-size: 14px; font-weight: 600; color: var(--text); }
.chunk-list { display: flex; flex-direction: column; gap: 10px; }
.chunk { padding: 12px; border-radius: 8px; border: 1px solid var(--border); background: var(--bg); }
.chunk-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.chunk-idx { font-size: 11px; font-weight: 600; color: var(--brand); }
.chunk-type {
  font-size: 10px; padding: 1px 6px; border-radius: 4px;
  background: var(--brand-soft); color: var(--brand);
}
.chunk-heading { font-size: 11px; color: var(--text-secondary); }
.chunk-tokens { font-size: 10px; color: var(--text-muted); margin-left: auto; }
.chunk-content { font-size: 13px; line-height: 1.7; color: var(--text); white-space: pre-wrap; word-break: break-word; }

/* 检索测试 */
.test-row { display: flex; gap: 8px; margin-bottom: 14px; }
.test-hits { display: flex; flex-direction: column; gap: 10px; max-height: 50vh; overflow-y: auto; }
.hit { padding: 12px; border-radius: 8px; border: 1px solid var(--border); background: var(--bg); }
.hit-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.hit-rank { font-size: 11px; font-weight: 600; color: var(--brand); }
.hit-src { font-size: 12px; font-weight: 500; color: var(--text); }
.hit-heading { font-size: 11px; color: var(--text-secondary); }
.hit-snippet { font-size: 13px; color: var(--text-secondary); line-height: 1.6; }

@media (max-width: 900px) {
  .stat-cards { grid-template-columns: repeat(3, 1fr); }
  .side { width: 160px; }
}
</style>
