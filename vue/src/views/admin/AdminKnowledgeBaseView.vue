<template>
  <section class="flex flex-col gap-[18px]">
    <div class="flex items-start justify-between gap-4 rounded-lg border border-border bg-card p-5 shadow-sm max-[860px]:flex-col">
      <div>
        <h3 class="m-0 text-base font-semibold text-foreground">知识库管理</h3>
        <p class="mt-2 text-sm text-muted-foreground">知识库是聊天检索的硬边界，知识范围和主题只在知识库内部继续做软分类。</p>
      </div>
      <Button size="sm" type="button" :disabled="loading || actionLoading" @click="openCreateDrawer">新建知识库</Button>
    </div>

    <div
      v-if="notice.message"
      class="rounded-md border px-[18px] py-3.5 text-sm font-semibold"
      :class="notice.type === 'danger' ? 'border-destructive/20 bg-destructive/10 text-destructive' : 'border-primary/10 bg-primary/[0.08] text-primary'"
    >
      {{ notice.message }}
    </div>

    <Card class="p-5">
      <div class="flex items-center justify-between gap-3">
        <div>
          <h4 class="m-0 text-sm font-semibold text-foreground">知识库列表</h4>
          <p class="mt-1 text-sm text-muted-foreground">当前 {{ knowledgeBases.length }} 个知识库。</p>
        </div>
        <Button variant="ghost" size="sm" type="button" :disabled="loading" @click="loadKnowledgeBases">{{ loading ? '刷新中...' : '刷新' }}</Button>
      </div>

      <div class="mt-4 overflow-x-auto rounded-md border border-border">
        <table class="w-full min-w-[860px] border-collapse text-sm">
          <thead>
            <tr class="bg-secondary">
              <th v-for="head in tableHeads" :key="head" class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground" :class="head === '操作' ? 'text-right' : ''">{{ head }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!loading && !knowledgeBases.length">
              <td colspan="6" class="px-4 py-10 text-center text-sm text-muted-foreground">还没有知识库，先创建一个再上传文档。</td>
            </tr>
            <tr v-for="item in knowledgeBases" :key="item.id" class="border-b border-border last:border-0">
              <td class="px-4 py-3">
                <strong class="block text-foreground">{{ item.baseName }}</strong>
              </td>
              <td class="px-4 py-3 text-muted-foreground">{{ item.description || '-' }}</td>
              <td class="px-4 py-3 text-foreground">{{ item.documentCount || 0 }} / {{ item.retrievableDocumentCount || 0 }}</td>
              <td class="px-4 py-3">
                <span class="inline-flex rounded-full px-2.5 py-1 text-xs font-semibold" :class="String(item.isDefault) === '1' ? 'bg-primary/[0.08] text-primary' : 'bg-secondary text-muted-foreground'">{{ String(item.isDefault) === '1' ? '默认' : '普通' }}</span>
              </td>
              <td class="px-4 py-3 text-muted-foreground">{{ item.sortOrder || 0 }}</td>
              <td class="px-4 py-3 text-right">
                <div class="inline-flex gap-2">
                  <Button variant="ghost" size="sm" type="button" @click="openViewDrawer(item)">查看</Button>
                  <Button variant="ghost" size="sm" type="button" @click="openEditDrawer(item)">编辑</Button>
                  <Button variant="ghost" size="sm" type="button" :disabled="actionLoading" @click="deleteKnowledgeBase(item)">删除</Button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </Card>

    <transition name="drawer-fade">
      <div v-if="drawerVisible" class="fixed inset-0 z-50 bg-[rgba(15,23,42,0.3)]" @click="closeDrawer"></div>
    </transition>
    <transition name="drawer-slide">
      <aside v-if="drawerVisible" class="fixed bottom-0 right-0 top-0 z-[51] flex w-[760px] max-w-[94vw] flex-col bg-card shadow-[-4px_0_24px_rgba(15,23,42,0.12)] max-[860px]:w-screen max-[860px]:max-w-screen">
        <div class="flex items-start justify-between gap-4 border-b border-border px-6 py-5">
          <div>
            <h4 class="m-0 text-sm font-semibold text-foreground">{{ drawerTitle }}</h4>
            <p class="mt-1 text-xs text-muted-foreground">{{ drawerSubtitle }}</p>
          </div>
          <Button variant="ghost" size="sm" type="button" :disabled="actionLoading" @click="closeDrawer">关闭</Button>
        </div>

        <div class="flex-1 overflow-y-auto px-6 py-5">
          <div class="grid gap-4">
            <section class="config-section">
              <h5 class="config-title">基础信息</h5>
              <div class="mt-3 grid gap-3">
                <div class="grid gap-2">
                  <Label class="field-label">知识库名称</Label>
                  <Input v-model="form.baseName" class="h-9 text-sm" placeholder="例如 人事制度库" :disabled="drawerReadOnly" />
                </div>
                <div class="grid gap-2">
                  <Label class="field-label">描述</Label>
                  <textarea v-model="form.description" class="form-textarea min-h-[76px]" placeholder="知识库用途、边界或使用说明" :disabled="drawerReadOnly"></textarea>
                </div>
                <div class="grid grid-cols-2 gap-3 max-[520px]:grid-cols-1">
                  <div class="grid gap-2">
                    <Label class="field-label">排序</Label>
                    <Input v-model="form.sortOrder" type="number" class="h-9 text-sm" placeholder="0" :disabled="drawerReadOnly" />
                  </div>
                  <label class="mt-7 inline-flex min-h-9 items-center gap-2 text-sm text-foreground max-[520px]:mt-0" :class="{ 'opacity-70': drawerReadOnly }">
                    <input v-model="form.isDefault" type="checkbox" true-value="1" false-value="0" :disabled="drawerReadOnly" />
                    <span>默认知识库</span>
                  </label>
                </div>
                <div class="grid gap-2">
                  <Label class="field-label">向量模型</Label>
                  <Input v-model="form.embeddingModel" class="h-9 text-sm" placeholder="可空，默认沿用全局配置" :disabled="drawerReadOnly" />
                </div>
              </div>
            </section>

            <section class="config-group">
              <div class="config-group-header">
                <h5 class="config-title">问答运行时 RAG 参数</h5>
                <p class="config-hint">影响新对话的召回、融合、精排候选和最终证据预算。</p>
              </div>

              <section class="config-section">
                <h6 class="config-subtitle">检索窗口与阈值</h6>
                <div class="config-grid">
                  <div v-for="field in retrievalNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">通道开关</h6>
                <div class="toggle-grid">
                  <label v-for="field in channelToggleFields" :key="field.key" class="config-toggle" :class="{ 'is-disabled': drawerReadOnly }">
                    <input v-model="configForm[field.key]" type="checkbox" :disabled="drawerReadOnly" />
                    <span>{{ field.label }}</span>
                  </label>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">GraphRAG 与 RAPTOR 查询</h6>
                <div class="config-grid">
                  <div v-for="field in graphRagNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">混合融合权重</h6>
                <div class="config-grid">
                  <div v-for="field in hybridWeightFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>
            </section>

            <section class="config-group">
              <div class="config-group-header">
                <h5 class="config-title">解析与索引构建参数</h5>
                <p class="config-hint">影响新上传或重新构建索引后的块、GraphRAG 与 RAPTOR 产物。</p>
              </div>

              <section class="config-section">
                <h6 class="config-subtitle">ChildChunk 参数</h6>
                <div class="config-grid">
                  <div v-for="field in childChunkNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">ParentBlock 参数</h6>
                <div class="config-grid">
                  <div v-for="field in parentBlockNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">构建通道</h6>
                <div class="toggle-grid">
                  <label v-for="field in buildToggleFields" :key="field.key" class="config-toggle" :class="{ 'is-disabled': drawerReadOnly }">
                    <input v-model="configForm[field.key]" type="checkbox" :disabled="drawerReadOnly" />
                    <span>{{ field.label }}</span>
                  </label>
                </div>
              </section>

              <section class="config-section">
                <h6 class="config-subtitle">RAPTOR 构建</h6>
                <div class="config-grid">
                  <div v-for="field in raptorBuildNumberFields" :key="field.key" class="grid gap-2">
                    <Label class="field-label">{{ field.label }}</Label>
                    <Input v-model="configForm[field.key]" type="number" :step="field.step" :min="field.min" class="h-9 text-sm" :disabled="drawerReadOnly" />
                  </div>
                </div>
              </section>
            </section>
          </div>
        </div>

        <div class="flex justify-between gap-3 border-t border-border px-6 py-4">
          <Button v-if="!drawerReadOnly && drawerMode === 'create'" variant="ghost" size="sm" type="button" :disabled="actionLoading" @click="resetForm">清空</Button>
          <span v-else></span>
          <div class="flex gap-2">
            <Button v-if="!drawerReadOnly" variant="ghost" size="sm" type="button" :disabled="actionLoading" @click="applyRecommendedConfig">推荐参数</Button>
            <Button variant="ghost" size="sm" type="button" :disabled="actionLoading" @click="closeDrawer">{{ drawerReadOnly ? '关闭' : '取消' }}</Button>
            <Button v-if="drawerReadOnly" size="sm" type="button" :disabled="actionLoading" @click="switchDrawerToEdit">编辑</Button>
            <Button v-else size="sm" type="button" :disabled="actionLoading" @click="saveKnowledgeBase">{{ actionLoading ? '保存中...' : '保存' }}</Button>
          </div>
        </div>
      </aside>
    </transition>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { manageApi } from '../../api/api'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const tableHeads = ['知识库', '描述', '文档 / 可检索', '默认', '排序', '操作']

const RECOMMENDED_RAG_CONFIG = Object.freeze({
  vectorTopK: '10',
  keywordTopK: '10',
  candidateTopK: '40',
  rerankCandidateTopK: '24',
  reserveCandidateTopK: '30',
  finalTopK: '6',
  minVectorSimilarity: '0.35',
  keywordRelativeScoreFloor: '0.25',
  keywordChannelEnabled: true,
  tableChannelEnabled: true,
  graphRagChannelEnabled: true,
  raptorChannelEnabled: true,
  graphRagTopK: '6',
  graphRagMaxHops: '2',
  raptorTopK: '6',
  raptorSourceChunkTopK: '4',
  vectorWeight: '1.0',
  keywordWeight: '1.1',
  tableWeight: '1.2',
  graphRagWeight: '1.1',
  raptorWeight: '1.05',
  rankWeight: '1.0',
  originalScoreWeight: '0.08',
  metadataBoostWeight: '0.04',
  maxMetadataBoost: '1.0',
  childRecursiveMaxChars: '800',
  childRecursiveOverlapChars: '120',
  childSemanticMaxChars: '700',
  childSemanticMinChars: '240',
  childSemanticSimilarityThreshold: '0.18',
  parentBlockMaxChars: '2200',
  parentBlockOverlapChars: '180',
  parentSemanticMaxChars: '1600',
  parentSemanticMinChars: '480',
  graphRagBuildEnabled: true,
  raptorBuildEnabled: true,
  raptorLlmSummaryEnabled: true,
  raptorMaxClusterSize: '6',
  raptorMaxLevels: '3',
  raptorSummaryQualityFloor: '0.42'
})

const retrievalNumberFields = [
  { key: 'vectorTopK', label: '向量 topK', min: '1', step: '1', integer: true },
  { key: 'keywordTopK', label: 'BM25 topK', min: '1', step: '1', integer: true },
  { key: 'candidateTopK', label: '融合候选 topK', min: '1', step: '1', integer: true },
  { key: 'rerankCandidateTopK', label: '精排候选 topK', min: '1', step: '1', integer: true },
  { key: 'reserveCandidateTopK', label: '证据保留窗口', min: '1', step: '1', integer: true },
  { key: 'finalTopK', label: '最终证据 topK', min: '1', step: '1', integer: true },
  { key: 'minVectorSimilarity', label: '向量阈值', min: '0', step: '0.01' },
  { key: 'keywordRelativeScoreFloor', label: 'BM25 相对阈值', min: '0', step: '0.01' }
]

const channelToggleFields = [
  { key: 'keywordChannelEnabled', label: '关键词/BM25' },
  { key: 'tableChannelEnabled', label: '表格通道' },
  { key: 'graphRagChannelEnabled', label: 'GraphRAG' },
  { key: 'raptorChannelEnabled', label: 'RAPTOR' }
]

const graphRagNumberFields = [
  { key: 'graphRagTopK', label: 'GraphRAG topK', min: '1', step: '1', integer: true },
  { key: 'graphRagMaxHops', label: 'GraphRAG 最大跳数', min: '1', step: '1', integer: true },
  { key: 'raptorTopK', label: 'RAPTOR topK', min: '1', step: '1', integer: true },
  { key: 'raptorSourceChunkTopK', label: 'RAPTOR 原文下钻 topK', min: '1', step: '1', integer: true }
]

const hybridWeightFields = [
  { key: 'vectorWeight', label: '向量权重', min: '0', step: '0.01' },
  { key: 'keywordWeight', label: 'BM25 权重', min: '0', step: '0.01' },
  { key: 'tableWeight', label: '表格权重', min: '0', step: '0.01' },
  { key: 'graphRagWeight', label: 'GraphRAG 权重', min: '0', step: '0.01' },
  { key: 'raptorWeight', label: 'RAPTOR 权重', min: '0', step: '0.01' },
  { key: 'rankWeight', label: '排名分权重', min: '0', step: '0.01' },
  { key: 'originalScoreWeight', label: '原始分权重', min: '0', step: '0.01' },
  { key: 'metadataBoostWeight', label: '元数据 boost 权重', min: '0', step: '0.01' },
  { key: 'maxMetadataBoost', label: '最大 metadata boost', min: '0', step: '0.01' }
]

const childChunkNumberFields = [
  { key: 'childRecursiveMaxChars', label: 'Child 递归最大字符', min: '100', step: '1', integer: true },
  { key: 'childRecursiveOverlapChars', label: 'Child 重叠字符', min: '0', step: '1', integer: true },
  { key: 'childSemanticMaxChars', label: 'Child 语义最大字符', min: '100', step: '1', integer: true },
  { key: 'childSemanticMinChars', label: 'Child 语义最小字符', min: '80', step: '1', integer: true },
  { key: 'childSemanticSimilarityThreshold', label: '语义相似度阈值', min: '0', step: '0.01' }
]

const parentBlockNumberFields = [
  { key: 'parentBlockMaxChars', label: 'Parent 最大字符', min: '300', step: '1', integer: true },
  { key: 'parentBlockOverlapChars', label: 'Parent 重叠字符', min: '0', step: '1', integer: true },
  { key: 'parentSemanticMaxChars', label: 'Parent 语义最大字符', min: '300', step: '1', integer: true },
  { key: 'parentSemanticMinChars', label: 'Parent 语义最小字符', min: '120', step: '1', integer: true }
]

const buildToggleFields = [
  { key: 'graphRagBuildEnabled', label: 'GraphRAG 构建' },
  { key: 'raptorBuildEnabled', label: 'RAPTOR 构建' },
  { key: 'raptorLlmSummaryEnabled', label: 'RAPTOR LLM 摘要' }
]

const raptorBuildNumberFields = [
  { key: 'raptorMaxClusterSize', label: 'RAPTOR 簇大小', min: '2', step: '1', integer: true },
  { key: 'raptorMaxLevels', label: 'RAPTOR 最大层数', min: '1', step: '1', integer: true },
  { key: 'raptorSummaryQualityFloor', label: '摘要质量阈值', min: '0', step: '0.01' }
]

const allNumberFields = [
  ...retrievalNumberFields,
  ...graphRagNumberFields,
  ...hybridWeightFields,
  ...childChunkNumberFields,
  ...parentBlockNumberFields,
  ...raptorBuildNumberFields
]

const fieldLabelMap = Object.fromEntries(allNumberFields.map((field) => [field.key, field.label]))
const knownRetrievalKeys = [
  'vectorTopK',
  'keywordTopK',
  'candidateTopK',
  'rerankCandidateTopK',
  'reserveCandidateTopK',
  'finalTopK',
  'minVectorSimilarity',
  'keywordRelativeScoreFloor',
  'keywordChannelEnabled',
  'tableChannelEnabled',
  'indexing',
  'hybrid'
]
const knownIndexingKeys = [
  'childRecursiveMaxChars',
  'childRecursiveOverlapChars',
  'childSemanticMaxChars',
  'childSemanticMinChars',
  'childSemanticSimilarityThreshold',
  'parentBlockMaxChars',
  'parentBlockOverlapChars',
  'parentSemanticMaxChars',
  'parentSemanticMinChars'
]
const knownGraphRagKeys = ['graphRagTopK', 'graphRagMaxHops', 'graphRagChannelEnabled', 'build']
const knownGraphRagBuildKeys = ['graphRagBuildEnabled']
const knownRaptorKeys = ['raptorTopK', 'raptorSourceChunkTopK', 'raptorChannelEnabled', 'build']
const knownRaptorBuildKeys = ['raptorBuildEnabled', 'raptorLlmSummaryEnabled', 'raptorMaxClusterSize', 'raptorMaxLevels', 'raptorSummaryQualityFloor']

const knowledgeBases = ref([])
const loading = ref(false)
const actionLoading = ref(false)
const notice = reactive({ type: 'info', message: '' })
const form = reactive(emptyForm())
const configForm = reactive(createRecommendedConfig())
const configExtras = reactive({
  retrieval: {},
  indexing: {},
  graphRag: {},
  graphRagBuild: {},
  raptor: {},
  raptorBuild: {},
  metadata: {}
})
const drawerVisible = ref(false)
const drawerMode = ref('view')
const drawerTarget = ref(null)
const drawerReadOnly = computed(() => drawerMode.value === 'view')
const drawerTitle = computed(() => {
  if (drawerMode.value === 'create') return '新建知识库'
  if (drawerMode.value === 'edit') return '编辑知识库'
  return '知识库详情'
})
const drawerSubtitle = computed(() => {
  if (drawerMode.value === 'create') return '配置知识库基础信息、问答运行时参数和解析构建参数。'
  return form.baseName || '查看当前知识库的基础信息与 RAG 参数配置。'
})

function createRecommendedConfig() {
  return { ...RECOMMENDED_RAG_CONFIG }
}

function emptyForm() {
  return {
    id: '',
    baseName: '',
    description: '',
    embeddingModel: '',
    retrievalConfigJson: '',
    graphRagConfigJson: '',
    raptorConfigJson: '',
    metadataFilterJson: '',
    isDefault: '0',
    sortOrder: '0',
    operatorId: '10001'
  }
}

function showNotice(message, type = 'info') {
  notice.message = message
  notice.type = type
}

function resetForm() {
  Object.assign(form, emptyForm())
  applyRecommendedConfig()
  clearConfigExtras()
}

function openCreateDrawer() {
  resetForm()
  drawerTarget.value = null
  drawerMode.value = 'create'
  drawerVisible.value = true
}

function openViewDrawer(item) {
  editKnowledgeBase(item)
  drawerTarget.value = { ...item }
  drawerMode.value = 'view'
  drawerVisible.value = true
}

function openEditDrawer(item) {
  editKnowledgeBase(item)
  drawerTarget.value = { ...item }
  drawerMode.value = 'edit'
  drawerVisible.value = true
}

function closeDrawer() {
  drawerVisible.value = false
  drawerTarget.value = null
  drawerMode.value = 'view'
  resetForm()
}

function switchDrawerToEdit() {
  drawerMode.value = 'edit'
}

function applyRecommendedConfig() {
  Object.assign(configForm, createRecommendedConfig())
}

function clearConfigExtras() {
  configExtras.retrieval = {}
  configExtras.indexing = {}
  configExtras.graphRag = {}
  configExtras.graphRagBuild = {}
  configExtras.raptor = {}
  configExtras.raptorBuild = {}
  configExtras.metadata = {}
}

function editKnowledgeBase(item) {
  Object.assign(form, {
    ...emptyForm(),
    ...item,
    id: String(item.id || ''),
    isDefault: String(item.isDefault || '0'),
    sortOrder: String(item.sortOrder || '0')
  })
  applyConfigFromJson(item)
}

function applyConfigFromJson(item = {}) {
  const retrieval = parseJsonObject(item.retrievalConfigJson)
  const graphRag = parseJsonObject(item.graphRagConfigJson)
  const raptor = parseJsonObject(item.raptorConfigJson)
  const metadata = parseJsonObject(item.metadataFilterJson)

  applyRecommendedConfig()
  assignKnownValues(retrieval, [
    'vectorTopK',
    'keywordTopK',
    'candidateTopK',
    'rerankCandidateTopK',
    'reserveCandidateTopK',
    'finalTopK',
    'minVectorSimilarity',
    'keywordRelativeScoreFloor',
    'keywordChannelEnabled',
    'tableChannelEnabled'
  ])
  assignKnownValues(graphRag, ['graphRagTopK', 'graphRagMaxHops', 'graphRagChannelEnabled'])
  assignKnownValues(raptor, ['raptorTopK', 'raptorSourceChunkTopK', 'raptorChannelEnabled'])

  if (retrieval.indexing && typeof retrieval.indexing === 'object' && !Array.isArray(retrieval.indexing)) {
    assignKnownValues(retrieval.indexing, knownIndexingKeys)
  }
  if (graphRag.build && typeof graphRag.build === 'object' && !Array.isArray(graphRag.build)) {
    assignKnownValues(graphRag.build, knownGraphRagBuildKeys)
  }
  if (raptor.build && typeof raptor.build === 'object' && !Array.isArray(raptor.build)) {
    assignKnownValues(raptor.build, knownRaptorBuildKeys)
  }

  if (retrieval.hybrid && typeof retrieval.hybrid === 'object' && !Array.isArray(retrieval.hybrid)) {
    assignKnownValues(retrieval.hybrid, [
      'vectorWeight',
      'keywordWeight',
      'tableWeight',
      'graphRagWeight',
      'raptorWeight',
      'rankWeight',
      'originalScoreWeight',
      'metadataBoostWeight',
      'maxMetadataBoost'
    ])
  }
  configExtras.retrieval = omitKeys(retrieval, knownRetrievalKeys)
  configExtras.indexing = omitKeys(retrieval.indexing, knownIndexingKeys)
  configExtras.graphRag = omitKeys(graphRag, knownGraphRagKeys)
  configExtras.graphRagBuild = omitKeys(graphRag.build, knownGraphRagBuildKeys)
  configExtras.raptor = omitKeys(raptor, knownRaptorKeys)
  configExtras.raptorBuild = omitKeys(raptor.build, knownRaptorBuildKeys)
  configExtras.metadata = metadata
}

function assignKnownValues(source, keys) {
  keys.forEach((key) => {
    if (Object.prototype.hasOwnProperty.call(source, key) && source[key] !== null && source[key] !== undefined) {
      configForm[key] = typeof source[key] === 'boolean' ? source[key] : String(source[key])
    }
  })
}

function parseJsonObject(rawJson) {
  if (!rawJson || typeof rawJson !== 'string') {
    return {}
  }
  try {
    const parsed = JSON.parse(rawJson)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

function omitKeys(source, keys) {
  return Object.fromEntries(
    Object.entries(source || {}).filter(([key]) => !keys.includes(key))
  )
}

async function loadKnowledgeBases() {
  loading.value = true
  try {
    const data = await manageApi.listKnowledgeBases()
    knowledgeBases.value = Array.isArray(data) ? data : []
  } catch (error) {
    showNotice(error.message || '加载知识库列表失败', 'danger')
  } finally {
    loading.value = false
  }
}

async function saveKnowledgeBase() {
  if (!form.baseName.trim()) {
    showNotice('知识库名称不能为空。', 'danger')
    return
  }
  let payload
  try {
    payload = buildSavePayload()
  } catch (error) {
    showNotice(error.message || 'RAG 参数不合法。', 'danger')
    return
  }

  actionLoading.value = true
  try {
    await manageApi.saveKnowledgeBase(payload)
    showNotice('知识库已保存。')
    await loadKnowledgeBases()
    closeDrawer()
  } catch (error) {
    showNotice(error.message || '保存知识库失败', 'danger')
  } finally {
    actionLoading.value = false
  }
}

function buildSavePayload() {
  const retrievalConfig = {
    ...configExtras.retrieval,
    vectorTopK: readInt('vectorTopK'),
    keywordTopK: readInt('keywordTopK'),
    candidateTopK: readInt('candidateTopK'),
    rerankCandidateTopK: readInt('rerankCandidateTopK'),
    reserveCandidateTopK: readInt('reserveCandidateTopK'),
    finalTopK: readInt('finalTopK'),
    minVectorSimilarity: readNumber('minVectorSimilarity'),
    keywordRelativeScoreFloor: readNumber('keywordRelativeScoreFloor'),
    keywordChannelEnabled: Boolean(configForm.keywordChannelEnabled),
    tableChannelEnabled: Boolean(configForm.tableChannelEnabled),
    indexing: {
      ...configExtras.indexing,
      childRecursiveMaxChars: readInt('childRecursiveMaxChars'),
      childRecursiveOverlapChars: readInt('childRecursiveOverlapChars'),
      childSemanticMaxChars: readInt('childSemanticMaxChars'),
      childSemanticMinChars: readInt('childSemanticMinChars'),
      childSemanticSimilarityThreshold: readNumber('childSemanticSimilarityThreshold'),
      parentBlockMaxChars: readInt('parentBlockMaxChars'),
      parentBlockOverlapChars: readInt('parentBlockOverlapChars'),
      parentSemanticMaxChars: readInt('parentSemanticMaxChars'),
      parentSemanticMinChars: readInt('parentSemanticMinChars')
    },
    hybrid: {
      vectorWeight: readNumber('vectorWeight'),
      keywordWeight: readNumber('keywordWeight'),
      tableWeight: readNumber('tableWeight'),
      graphRagWeight: readNumber('graphRagWeight'),
      raptorWeight: readNumber('raptorWeight'),
      rankWeight: readNumber('rankWeight'),
      originalScoreWeight: readNumber('originalScoreWeight'),
      metadataBoostWeight: readNumber('metadataBoostWeight'),
      maxMetadataBoost: readNumber('maxMetadataBoost')
    }
  }
  const graphRagConfig = {
    ...configExtras.graphRag,
    graphRagTopK: readInt('graphRagTopK'),
    graphRagMaxHops: readInt('graphRagMaxHops'),
    graphRagChannelEnabled: Boolean(configForm.graphRagChannelEnabled),
    build: {
      ...configExtras.graphRagBuild,
      graphRagBuildEnabled: Boolean(configForm.graphRagBuildEnabled)
    }
  }
  const raptorConfig = {
    ...configExtras.raptor,
    raptorTopK: readInt('raptorTopK'),
    raptorSourceChunkTopK: readInt('raptorSourceChunkTopK'),
    raptorChannelEnabled: Boolean(configForm.raptorChannelEnabled),
    build: {
      ...configExtras.raptorBuild,
      raptorBuildEnabled: Boolean(configForm.raptorBuildEnabled),
      raptorLlmSummaryEnabled: Boolean(configForm.raptorLlmSummaryEnabled),
      raptorMaxClusterSize: readInt('raptorMaxClusterSize'),
      raptorMaxLevels: readInt('raptorMaxLevels'),
      raptorSummaryQualityFloor: readNumber('raptorSummaryQualityFloor')
    }
  }
  return {
    ...form,
    retrievalConfigJson: JSON.stringify(retrievalConfig),
    graphRagConfigJson: JSON.stringify(graphRagConfig),
    raptorConfigJson: JSON.stringify(raptorConfig),
    metadataFilterJson: Object.keys(configExtras.metadata).length
      ? JSON.stringify(configExtras.metadata)
      : ''
  }
}

function readInt(key) {
  const value = readNumber(key)
  if (!Number.isInteger(value)) {
    throw new Error(`${fieldLabelMap[key]} 必须是整数。`)
  }
  return value
}

function readNumber(key) {
  const label = fieldLabelMap[key] || key
  const raw = String(configForm[key] ?? '').trim()
  if (!raw) {
    throw new Error(`${label} 不能为空。`)
  }
  const value = Number(raw)
  const field = allNumberFields.find((item) => item.key === key)
  const min = Number(field?.min ?? 0)
  if (!Number.isFinite(value) || value < min) {
    throw new Error(`${label} 必须是大于等于 ${min} 的数字。`)
  }
  return value
}

async function deleteKnowledgeBase(item) {
  if (!item?.id || !window.confirm(`确认删除知识库「${item.baseName}」吗？`)) return
  actionLoading.value = true
  try {
    await manageApi.deleteKnowledgeBase({ id: item.id, operatorId: '10001' })
    if (String(form.id) === String(item.id) || String(drawerTarget.value?.id) === String(item.id)) closeDrawer()
    showNotice('知识库已删除。')
    await loadKnowledgeBases()
  } catch (error) {
    showNotice(error.message || '删除知识库失败', 'danger')
  } finally {
    actionLoading.value = false
  }
}

onMounted(loadKnowledgeBases)
</script>

<style scoped>
.field-label {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-muted-strong);
}

.config-section {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: rgba(250, 250, 250, 0.58);
  padding: 14px;
}

.config-group {
  display: grid;
  gap: 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.72);
  padding: 14px;
}

.config-group-header {
  display: grid;
  gap: 4px;
}

.config-title {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 13px;
  font-weight: 750;
}

.config-subtitle {
  margin: 0 0 12px;
  color: var(--color-text-strong);
  font-size: 12px;
  font-weight: 750;
}

.config-hint {
  margin: 0;
  color: var(--color-muted-strong);
  font-size: 12px;
  line-height: 1.5;
}

.config-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.toggle-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.config-toggle {
  display: flex;
  min-height: 38px;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: #fff;
  padding: 8px 10px;
  color: var(--color-text-strong);
  font-size: 13px;
  font-weight: 600;
}

.form-textarea {
  min-height: 78px;
  width: 100%;
  resize: vertical;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: #fff;
  padding: 10px 12px;
  color: var(--color-text-strong);
}

.form-textarea:focus,
.config-toggle:focus-within {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-soft);
}

.form-textarea:disabled,
.config-toggle.is-disabled {
  cursor: not-allowed;
  opacity: 0.68;
}

/* 保留：Vue <transition> 钩子类名无法用 Tailwind 替换 */
.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 0.25s ease;
}

.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}

.drawer-slide-enter-active,
.drawer-slide-leave-active {
  transition: transform 0.25s ease;
}

.drawer-slide-enter-from,
.drawer-slide-leave-to {
  transform: translateX(100%);
}

@media (prefers-reduced-motion: reduce) {
  .drawer-fade-enter-active,
  .drawer-fade-leave-active,
  .drawer-slide-enter-active,
  .drawer-slide-leave-active {
    transition-duration: 0.01ms;
  }
}

@media (max-width: 520px) {
  .config-grid,
  .toggle-grid {
    grid-template-columns: 1fr;
  }
}
</style>
