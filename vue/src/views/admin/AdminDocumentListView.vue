<template>
  <section class="flex flex-col gap-[18px]">
    <!-- 上传 + 提示双栏 -->
    <div class="grid grid-cols-[1.05fr_0.95fr] gap-4 max-md:grid-cols-1">
      <Card class="p-5">
        <h3 class="m-0 text-base font-semibold text-foreground">上传资料并进入推荐流程</h3>

        <div class="mt-4 grid grid-cols-2 gap-3.5 max-[860px]:grid-cols-1">
          <div class="flex flex-col gap-2">
            <Label class="text-[13px] font-bold text-[var(--color-muted-strong)]">所属知识库</Label>
            <select
              v-model="uploadForm.knowledgeBaseId"
              class="h-9 rounded-md border border-input bg-background px-3 text-sm text-foreground disabled:bg-secondary"
              :disabled="loadingKnowledgeBases"
            >
              <option value="">请选择知识库</option>
              <option v-for="item in knowledgeBaseOptions" :key="item.id" :value="item.id">{{ item.baseName }}</option>
            </select>
          </div>
          <div v-for="f in uploadFields" :key="f.key" class="flex flex-col gap-2">
            <Label class="text-[13px] font-bold text-[var(--color-muted-strong)]">{{ f.label }}</Label>
            <Input v-model="uploadForm[f.key]" :type="f.type || 'text'" :placeholder="f.placeholder" class="h-9 text-sm" />
          </div>
          <div class="flex flex-col gap-2">
            <Label class="text-[13px] font-bold text-[var(--color-muted-strong)]">选择文件</Label>
            <input
              ref="fileInputRef"
              type="file"
              class="w-full rounded-md border border-input bg-background px-3 py-2 text-sm file:mr-3 file:rounded file:border-0 file:bg-primary/[0.08] file:px-3 file:py-1 file:text-xs file:font-medium file:text-primary"
              @change="handleFileChange"
            />
          </div>
        </div>

        <div class="mt-3.5 flex items-stretch justify-between gap-3 max-[860px]:flex-col">
          <div class="flex min-w-0 flex-1 flex-col justify-center rounded-md border border-border bg-secondary px-4 py-3.5">
            <span class="text-[13px] text-muted-foreground">支持 PDF / DOC / DOCX / XLSX / TXT / MD / HTML / PNG / JPG / BMP / GIF</span>
            <strong class="mt-2 block break-all text-[13px] text-foreground">{{ uploadForm.file ? uploadForm.file.name : '尚未选择文件' }}</strong>
          </div>
          <div class="flex flex-none items-center gap-3 self-center">
            <Button variant="ghost" size="sm" type="button" @click="clearSelectedFile">清空</Button>
            <Button size="sm" type="button" :disabled="uploading || !uploadForm.file" @click="submitUpload">
              {{ uploading ? '上传中...' : '上传并解析' }}
            </Button>
          </div>
        </div>
      </Card>

      <Card class="p-5">
        <h3 class="m-0 text-base font-semibold text-foreground">建议操作顺序</h3>
        <ul class="mt-3 flex flex-col gap-3 pl-[18px] text-sm leading-relaxed text-[var(--color-muted-strong)]">
          <li>先上传文档，系统会异步解析并生成推荐切块策略。</li>
          <li>点击任意文档，进入单独详情页查看解析结果、Chunk 和任务轨迹。</li>
          <li>在详情页确认策略并构建索引，列表页专注浏览和筛选。</li>
        </ul>
      </Card>
    </div>

    <!-- 通知条 -->
    <div
      v-if="pageNotice.message"
      class="rounded-md border px-[18px] py-3.5 text-sm font-semibold"
      :class="{
        'border-primary/10 bg-primary/[0.08] text-primary': pageNotice.type === 'info',
        'border-[var(--color-success)]/20 bg-[var(--color-success)]/10 text-[var(--color-success)]': pageNotice.type === 'success',
        'border-destructive/20 bg-destructive/10 text-destructive': pageNotice.type === 'danger'
      }"
    >
      {{ pageNotice.message }}
    </div>

    <!-- 文档列表卡片 -->
    <Card class="p-5">
      <div class="flex items-start justify-between gap-3 max-[860px]:flex-col max-[860px]:items-stretch">
        <div>
          <h3 class="m-0 text-base font-semibold text-foreground">文档列表</h3>
          <p class="mt-2 text-sm text-muted-foreground">共 {{ total }} 份文档，当前第 {{ currentPage }} 页。</p>
        </div>
        <div class="flex items-center gap-3">
          <Input
            v-model="keyword"
            class="h-9 w-[280px] text-sm max-[860px]:w-full"
            type="text"
            placeholder="搜索文档名称或原始文件名"
            @keydown.enter="submitSearch"
          />
          <Button variant="ghost" size="sm" type="button" @click="submitSearch">搜索</Button>
        </div>
      </div>

      <div class="mt-[18px] grid grid-cols-4 gap-2.5 max-[860px]:grid-cols-2">
        <div v-for="s in statCards" :key="s.label" class="rounded-md border border-border bg-card px-4 py-3.5">
          <span class="block text-xs text-muted-foreground">{{ s.label }}</span>
          <strong class="mt-2 block text-2xl leading-tight text-foreground">{{ s.value }}</strong>
        </div>
      </div>

      <div class="mt-3.5 min-h-[420px] overflow-hidden rounded-md border border-border">
        <div v-if="!listLoading && !documents.length" class="grid min-h-[260px] place-items-center text-center text-sm text-muted-foreground">
          还没有文档，先上传一份资料开始体验。
        </div>
        <div v-if="listLoading" class="grid min-h-[260px] place-items-center text-center text-sm text-muted-foreground">
          正在加载文档列表...
        </div>

        <div v-if="!listLoading && documents.length" class="overflow-x-auto">
          <table class="w-full min-w-[1080px] border-collapse text-sm">
            <thead>
              <tr class="bg-secondary">
                <th v-for="h in tableHeads" :key="h" class="border-b border-border px-4 py-3.5 text-left text-xs font-semibold text-muted-foreground whitespace-nowrap" :class="h === '操作' ? 'text-right' : ''">{{ h }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in documents"
                :key="item.documentId"
                class="border-b border-border transition-colors hover:bg-primary/[0.04] last:border-0"
              >
                <td class="p-4 align-top">
	                  <button class="w-full border-0 bg-transparent p-0 text-left" type="button" @click="openDocumentDetail(item.documentId)">
	                    <strong class="block text-base leading-snug text-foreground hover:text-primary">{{ item.documentName }}</strong>
	                    <span class="mt-1.5 block break-all text-xs text-muted-foreground">{{ item.originalFileName }}</span>
                      <span class="mt-1.5 inline-flex rounded-full bg-primary/[0.08] px-2.5 py-1 text-xs font-medium text-primary">{{ item.knowledgeBaseName || '未绑定知识库' }}</span>
	                  </button>
	                </td>
                <td class="p-4 align-top">
                  <Badge variant="secondary" class="rounded-full">{{ item.fileTypeName || '-' }}</Badge>
                </td>
                <td class="p-4 align-top text-sm font-semibold text-foreground">{{ formatFileSize(item.fileSize) }}</td>
                <td class="p-4 align-top text-sm font-semibold text-foreground">{{ formatDateTime(item.editTime) }}</td>
                <td class="p-4 align-top"><AdminStatusBadge :label="item.parseStatusName" :code="item.parseStatus" type="parse" /></td>
                <td class="p-4 align-top"><AdminStatusBadge :label="item.strategyStatusName" :code="item.strategyStatus" type="strategy" /></td>
                <td class="p-4 align-top"><AdminStatusBadge :label="item.indexStatusName" :code="item.indexStatus" type="index" /></td>
                <td class="p-4 align-top text-right">
                  <div class="inline-flex items-center justify-end gap-2.5">
                    <button
                      class="inline-flex min-w-[88px] items-center justify-center rounded-md border border-primary/[0.12] bg-primary/[0.08] px-3.5 py-2 text-xs font-medium text-primary hover:text-[var(--color-primary-strong)] disabled:cursor-not-allowed disabled:opacity-55"
                      type="button"
                      @click="openDocumentDetail(item.documentId)"
                    >查看详情</button>
                    <button
                      class="inline-flex min-w-[72px] items-center justify-center rounded-md border border-destructive/[0.12] bg-destructive/[0.08] px-3.5 py-2 text-xs font-medium text-destructive hover:enabled:text-[var(--color-primary-strong)] disabled:cursor-not-allowed disabled:opacity-55"
                      type="button"
                      :disabled="!canDeleteDocument(item)"
                      :title="buildDeleteTitle(item)"
                      @click="deleteDocument(item)"
                    >{{ isDeletingDocument(item.documentId) ? '删除中...' : '删除' }}</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-if="documents.length" class="mt-[18px] flex items-center justify-between border-t border-border pt-[18px] max-[860px]:flex-col max-[860px]:items-stretch max-[860px]:gap-3">
        <Button variant="outline" size="sm" type="button" :disabled="currentPage <= 1 || listLoading" @click="changePage(currentPage - 1)">上一页</Button>
        <div class="text-center">
          <strong class="block text-sm text-foreground">第 {{ currentPage }} / {{ totalPages }} 页</strong>
          <span class="mt-1.5 block text-xs text-muted-foreground">共 {{ total }} 条文档</span>
        </div>
        <Button variant="outline" size="sm" type="button" :disabled="currentPage >= totalPages || listLoading" @click="changePage(currentPage + 1)">下一页</Button>
      </div>
    </Card>
  </section>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { APIError, manageApi } from '../../api/api'
import AdminStatusBadge from '../../components/admin/AdminStatusBadge.vue'
import { formatDateTime, formatFileSize, hasCode } from '../../utils/manageFormat'
import { useConfirm } from '@/composables/useConfirm'
const { confirm } = useConfirm()
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'

const router = useRouter()
const OPERATOR_ID = '10001'
const DEFAULT_PAGE_SIZE = 12

const uploadForm = reactive({
  documentName: '',
  knowledgeBaseId: '',
  file: null
})

const uploadFields = [
  { key: 'documentName', label: '文档名称', placeholder: '不填则使用原始文件名' }
]

const tableHeads = ['文档', '类型', '大小', '更新时间', '解析', '策略', '索引', '操作']

const fileInputRef = ref(null)
const uploading = ref(false)
const loadingKnowledgeBases = ref(false)
const listLoading = ref(false)
const keyword = ref('')
const documents = ref([])
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const deletingDocumentId = ref('')
const pageNotice = reactive({ type: 'info', message: '' })
const knowledgeBaseOptions = ref([])

const totalPages = computed(() => Math.max(1, Math.ceil((total.value || 0) / pageSize.value)))
const visibleParseReadyCount = computed(() => documents.value.filter((item) => hasCode(item.parseStatus, 3)).length)
const visibleStrategyReadyCount = computed(() => documents.value.filter((item) => hasCode(item.strategyStatus, 3)).length)
const visibleIndexReadyCount = computed(() => documents.value.filter((item) => hasCode(item.indexStatus, 3)).length)

const statCards = computed(() => [
  { label: '当前页文档', value: documents.value.length },
  { label: '解析完成', value: visibleParseReadyCount.value },
  { label: '策略确认', value: visibleStrategyReadyCount.value },
  { label: '索引可用', value: visibleIndexReadyCount.value }
])

function showNotice(message, type = 'info') { pageNotice.type = type; pageNotice.message = message }
function clearNotice() { pageNotice.message = '' }

function handleFileChange(event) { uploadForm.file = event.target.files?.[0] || null }

function clearSelectedFile() {
  uploadForm.file = null
  uploadForm.documentName = ''
  if (fileInputRef.value) fileInputRef.value.value = ''
}

async function loadKnowledgeBases() {
  loadingKnowledgeBases.value = true
  try {
    const data = await manageApi.listKnowledgeBases()
    knowledgeBaseOptions.value = Array.isArray(data) ? data : []
    if (!uploadForm.knowledgeBaseId && knowledgeBaseOptions.value.length === 1) {
      uploadForm.knowledgeBaseId = knowledgeBaseOptions.value[0].id
    }
  } catch (error) {
    showNotice(normalizeError(error, '加载知识库失败'), 'danger')
  } finally {
    loadingKnowledgeBases.value = false
  }
}

async function loadDocuments(page = currentPage.value) {
  listLoading.value = true
  try {
    const data = await manageApi.queryDocumentPage({ pageNo: page, pageSize: pageSize.value, keyword: keyword.value.trim() })
    documents.value = Array.isArray(data?.records) ? data.records : []
    currentPage.value = Number(data?.pageNo || page)
    pageSize.value = Number(data?.pageSize || pageSize.value)
    total.value = Number(data?.total || 0)
  } catch (error) {
    console.error('加载文档列表失败', error)
    showNotice(normalizeError(error, '加载文档列表失败'), 'danger')
    documents.value = []
  } finally {
    listLoading.value = false
  }
}

function submitSearch() { currentPage.value = 1; loadDocuments(1) }

function changePage(page) {
  if (page < 1 || page > totalPages.value || page === currentPage.value) return
  loadDocuments(page)
}

function openDocumentDetail(documentId, query = {}) {
  router.push({ name: 'AdminDocumentDetail', params: { documentId: String(documentId) }, query })
}

function isDeletingDocument(documentId) { return String(deletingDocumentId.value || '') === String(documentId || '') }

function hasRunningDocumentTask(item) {
  return hasCode(item?.latestTaskStatus, 1) || hasCode(item?.latestTaskStatus, 2)
    || hasCode(item?.parseStatus, 2) || hasCode(item?.indexStatus, 2)
}

function canDeleteDocument(item) {
  return !!item?.documentId && !listLoading.value && !deletingDocumentId.value && !hasRunningDocumentTask(item)
}

function buildDeleteTitle(item) {
  if (hasRunningDocumentTask(item)) return '请等待当前任务完成后再删除'
  if (deletingDocumentId.value) return '当前有文档正在删除'
  return '删除文档以及关联的索引、存储文件'
}

async function submitUpload() {
  if (!uploadForm.file) { showNotice('请先选择要上传的文档。', 'danger'); return }
  if (!uploadForm.knowledgeBaseId) { showNotice('请先选择文档所属知识库。', 'danger'); return }
  uploading.value = true; clearNotice()
  try {
    const result = await manageApi.uploadDocument({
      file: uploadForm.file,
      documentName: uploadForm.documentName.trim(),
      operatorId: OPERATOR_ID,
      knowledgeBaseId: uploadForm.knowledgeBaseId
    })
    clearSelectedFile()
    showNotice(`文档已上传，任务 ${result.taskId} 已进入解析与策略推荐队列。`, 'success')
    await loadDocuments(1)
    openDocumentDetail(result.documentId, {
      parseTaskId: String(result.taskId || ''),
      showParseProgress: '1'
    })
  } catch (error) {
    console.error('上传文档失败', error); showNotice(normalizeError(error, '上传文档失败'), 'danger')
  } finally {
    uploading.value = false
  }
}

async function deleteDocument(item) {
  if (!item?.documentId) return
  if (hasRunningDocumentTask(item)) { showNotice('当前文档存在进行中的任务，请等待任务完成后再删除。', 'danger'); return }
  const documentId = String(item.documentId)
  const documentName = item.documentName || item.originalFileName || documentId
  if (!await confirm(`确认删除文档《${documentName}》吗？\n\n将同时删除 MySQL 记录、向量库数据和 MinIO 存储文件，删除后不可恢复。`, '确认删除')) return
  deletingDocumentId.value = documentId; clearNotice()
  try {
    await manageApi.deleteDocument({ documentId })
    const nextPage = documents.value.length === 1 && currentPage.value > 1 ? currentPage.value - 1 : currentPage.value
    await loadDocuments(nextPage)
    showNotice(`文档《${documentName}》已删除，关联数据已同步清理。`, 'success')
  } catch (error) {
    console.error('删除文档失败', error); showNotice(normalizeError(error, '删除文档失败'), 'danger')
  } finally {
    deletingDocumentId.value = ''
  }
}

function normalizeError(error, fallbackMessage) {
  if (error instanceof APIError && error.message) return error.message
  if (error instanceof Error && error.message) return error.message
  return fallbackMessage
}

onMounted(() => {
  loadKnowledgeBases()
  loadDocuments()
})
</script>
