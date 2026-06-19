<template>
  <section class="flex flex-col gap-3 overflow-hidden" :style="pageStyle">
    <!-- 页头 -->
    <header class="flex flex-none items-center gap-4 rounded-lg border border-border bg-card px-5 py-3.5 shadow-sm">
      <div class="flex-none">
        <h3 class="m-0 text-base font-semibold text-foreground">知识路由追踪</h3>
      </div>
      <div class="flex flex-1 flex-wrap justify-center gap-5 max-[860px]:hidden">
        <span v-for="item in headerStats" :key="item.label" class="flex flex-col items-center gap-0.5">
          <strong class="text-lg leading-none text-foreground">{{ item.value }}</strong>
          <span class="text-[11px] text-muted-foreground">{{ item.label }}</span>
        </span>
      </div>
      <Button size="sm" class="flex-none rounded-full" :disabled="loading" @click="loadTraces">
        {{ loading ? '正在刷新...' : '刷新追踪' }}
      </Button>
    </header>

    <!-- 洞察区（可折叠） -->
    <div class="flex-none overflow-hidden rounded-lg border border-border bg-card shadow-sm">
      <div class="flex cursor-pointer select-none items-center justify-between px-[18px] py-2.5 text-[13px] font-semibold text-foreground hover:bg-secondary"
        @click="insightCollapsed = !insightCollapsed">
        <span>路由洞察</span>
        <span class="text-[10px] text-muted-foreground transition-transform" :class="insightCollapsed ? '-rotate-90' : ''">&#9660;</span>
      </div>
      <div v-show="!insightCollapsed" class="grid grid-cols-3 border-t border-border max-[1100px]:grid-cols-2 max-[860px]:grid-cols-1">
        <article class="border-r border-border p-4 max-[860px]:border-b max-[860px]:border-r-0 max-[1100px]:border-r">
          <div class="mb-2.5 flex items-center justify-between gap-2">
            <h5 class="m-0 text-[13px] font-semibold text-foreground">路由健康度</h5>
            <span class="text-xs text-muted-foreground">当前页样本</span>
          </div>
          <div class="grid gap-2.5">
            <article v-for="item in routeHealthCards" :key="item.label" class="rounded-lg border border-border bg-background p-2.5">
              <div class="flex items-center justify-between gap-2">
                <span class="text-xs text-muted-foreground">{{ item.label }}</span>
                <strong class="text-[13px] text-foreground">{{ item.value }}</strong>
              </div>
              <div class="my-2 h-1.5 overflow-hidden rounded-full bg-foreground/[0.08]">
                <span :class="healthFillClass(item.tone)" :style="{ width: item.percent }"></span>
              </div>
              <small class="text-[11px] text-muted-foreground">{{ item.description }}</small>
            </article>
          </div>
        </article>
        <article class="border-r border-border p-4 max-[860px]:border-b max-[860px]:border-r-0">
          <div class="mb-2.5 flex items-center justify-between gap-2">
            <h5 class="m-0 text-[13px] font-semibold text-foreground">Top 候选文档分布</h5>
            <span class="text-xs text-muted-foreground">{{ topDocumentDistribution.length }} 个文档</span>
          </div>
          <div v-if="topDocumentDistribution.length" class="grid gap-2">
            <article v-for="item in topDocumentDistribution" :key="item.documentId" class="flex items-center justify-between gap-2.5 rounded-lg border border-border bg-background p-2.5">
              <div>
                <strong class="block text-[13px] text-foreground">{{ item.documentName }}</strong>
                <span class="text-xs text-muted-foreground">出现 {{ item.count }} 次 · 均值 {{ item.averageConfidenceText }}</span>
              </div>
              <span class="inline-flex whitespace-nowrap rounded-full px-2 py-1 text-[11px] font-bold"
                :class="item.lowConfidenceCount > 0 ? 'bg-amber-500/[0.14] text-amber-700' : 'bg-green-500/[0.12] text-green-700'"
              >{{ item.lowConfidenceCount > 0 ? `${item.lowConfidenceCount} 次低置信` : '全部成功' }}</span>
            </article>
          </div>
          <p v-else class="text-xs text-muted-foreground">当前页还没有可统计的 Top 文档。</p>
        </article>
        <article class="p-4 max-[1100px]:col-span-2 max-[1100px]:border-t max-[860px]:col-span-1">
          <div class="mb-2.5 flex items-center justify-between gap-2">
            <h5 class="m-0 text-[13px] font-semibold text-foreground">详细统计</h5>
          </div>
          <div class="grid grid-cols-3 gap-2.5">
            <div v-for="item in summaryCards" :key="item.label" class="flex flex-col gap-0.5">
              <strong class="text-[15px] text-foreground">{{ item.value }}</strong>
              <span class="text-[11px] text-muted-foreground">{{ item.label }}</span>
            </div>
          </div>
        </article>
      </div>
    </div>

    <!-- 主工作台 -->
    <div class="grid min-h-0 flex-1 grid-cols-[340px_minmax(0,1fr)] gap-3 max-[1100px]:grid-cols-[300px_minmax(0,1fr)] max-[860px]:grid-cols-1 max-[860px]:flex-col">
      <!-- 左侧列表 -->
      <aside class="flex flex-col overflow-hidden rounded-lg border border-border bg-card shadow-sm max-[860px]:h-[360px]">
        <div class="flex flex-none flex-col gap-2 border-b border-border p-3">
          <input v-model.trim="filters.conversationId" class="w-full rounded-md border border-input bg-secondary px-2.5 py-2 text-[13px] text-foreground focus:border-ring focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-1" placeholder="按会话 ID 筛选..." @keydown.enter="loadTraces('1')" />
          <div class="grid grid-cols-2 gap-1.5">
            <select v-model="filters.mode" class="rounded-md border border-input bg-background px-2 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring">
              <option value="">全部模式</option>
              <option value="shadow">shadow</option>
              <option value="auto">auto</option>
            </select>
            <select v-model="filters.routeStatus" class="rounded-md border border-input bg-background px-2 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring">
              <option value="">全部状态</option>
              <option value="1">成功</option>
              <option value="2">低置信</option>
              <option value="3">失败</option>
            </select>
          </div>
          <div class="flex justify-end gap-1.5">
            <button class="rounded-full border border-border bg-background px-3 py-1.5 text-xs font-semibold text-foreground disabled:opacity-50" type="button" :disabled="loading" @click="resetFilters">重置</button>
            <button class="rounded-full border-0 bg-primary px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50" type="button" :disabled="loading" @click="loadTraces('1')">筛选</button>
          </div>
        </div>

        <div class="flex-1 overflow-y-auto p-1.5">
          <div v-if="loading" class="py-8 text-center text-[13px] text-muted-foreground">正在加载...</div>
          <div v-else-if="!normalizedRecords.length" class="py-8 text-center text-[13px] text-muted-foreground">暂无追踪记录</div>
          <article
            v-else
            v-for="item in normalizedRecords"
            :key="item.id"
            class="mb-1 cursor-pointer rounded-lg border p-3 transition-colors"
            :class="selectedId === item.id ? 'border-primary/20 bg-primary/[0.09]' : 'border-transparent hover:bg-secondary'"
            @click="selectRecord(item)"
          >
            <div class="mb-1.5 flex flex-wrap gap-1">
              <span class="inline-flex rounded-full px-2 py-0.5 text-[11px] font-bold" :class="chipClass('neutral')">{{ item.modeLabel }}</span>
              <span class="inline-flex rounded-full px-2 py-0.5 text-[11px] font-bold" :class="chipClass(item.statusTone)">{{ item.statusLabel }}</span>
              <span class="inline-flex rounded-full px-2 py-0.5 text-[11px] font-bold" :class="chipClass(item.confidenceBand.tone)">{{ item.confidenceText }}</span>
            </div>
            <p class="mb-1.5 line-clamp-2 text-[13px] leading-snug text-foreground">{{ item.question || '未记录问题' }}</p>
            <div class="flex justify-between gap-2 text-[11px] text-muted-foreground">
              <span class="min-w-0 flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{{ primaryDocumentText(item) }}</span>
              <span class="shrink-0">{{ formatDateTime(item.createTimeNumber) }}</span>
            </div>
          </article>
        </div>

        <nav class="flex flex-none items-center justify-center gap-2.5 border-t border-border p-2.5 text-xs text-muted-foreground">
          <button class="rounded-full border border-border bg-background px-2.5 py-1 text-xs font-semibold disabled:opacity-45" type="button" :disabled="Number(page.pageNo) <= 1 || loading" @click="changePage(Number(page.pageNo) - 1)">上一页</button>
          <span>{{ page.pageNo }} / {{ page.totalPages || '0' }}</span>
          <button class="rounded-full border border-border bg-background px-2.5 py-1 text-xs font-semibold disabled:opacity-45" type="button" :disabled="Number(page.pageNo) >= Number(page.totalPages || 0) || loading" @click="changePage(Number(page.pageNo) + 1)">下一页</button>
        </nav>
      </aside>

      <!-- 右侧详情 -->
      <main class="overflow-y-auto rounded-lg border border-border bg-card p-5 shadow-sm">
        <div v-if="!selectedRecord" class="flex h-full items-center justify-center text-sm text-muted-foreground">
          <p>从左侧选择一条追踪记录查看详情</p>
        </div>

        <template v-else>
          <div class="mb-5">
            <div class="mb-2.5 flex flex-wrap gap-1.5">
              <span class="inline-flex rounded-full px-2 py-0.5 text-[11px] font-bold" :class="chipClass('neutral')">{{ selectedRecord.modeLabel }}</span>
              <span class="inline-flex rounded-full px-2 py-0.5 text-[11px] font-bold" :class="chipClass(selectedRecord.statusTone)">{{ selectedRecord.statusLabel }}</span>
              <span class="inline-flex rounded-full px-2 py-0.5 text-[11px] font-bold" :class="chipClass(selectedRecord.confidenceBand.tone)">{{ selectedRecord.confidenceBand.label }} · {{ selectedRecord.confidenceText }}</span>
            </div>
            <h4 class="mb-1.5 text-base font-semibold text-foreground">{{ selectedRecord.question || '未记录问题' }}</h4>
            <p class="mb-2.5 text-[13px] leading-relaxed text-muted-foreground">改写问题：{{ selectedRecord.rewriteQuestion || '未记录改写问题' }}</p>
            <div class="flex flex-wrap gap-4 text-xs text-muted-foreground">
              <span>{{ formatDateTime(selectedRecord.createTimeNumber) }}</span>
              <span>会话 {{ shortenId(selectedRecord.conversationId) }}</span>
              <span>轮次 {{ selectedRecord.exchangeId || '-' }}</span>
            </div>
          </div>

          <div class="mb-5 grid grid-cols-2 gap-2.5 max-[860px]:grid-cols-1">
            <article class="rounded-lg border border-primary/20 bg-gradient-to-br from-primary/[0.07] to-[#ef7b39]/[0.07] p-3.5">
              <p class="mb-1 text-[11px] text-muted-foreground">主候选文档</p>
              <strong class="mb-1.5 block leading-snug text-foreground">{{ primaryDocumentText(selectedRecord) }}</strong>
              <span class="text-xs leading-relaxed text-muted-foreground">{{ selectedRecord.reasonText || '当前未记录额外路由说明' }}</span>
            </article>
            <article v-for="card in detailSummaryCards" :key="card.label" class="rounded-lg border border-border bg-background p-3.5">
              <p class="mb-1 text-[11px] text-muted-foreground">{{ card.label }}</p>
              <strong class="mb-1.5 block leading-snug text-foreground">{{ card.value }}</strong>
              <span class="text-xs leading-relaxed text-muted-foreground">{{ card.desc }}</span>
            </article>
          </div>

          <section v-if="selectedRecord.documents.length" class="mb-5">
            <div class="mb-2.5 flex items-center justify-between gap-2.5">
              <h5 class="m-0 text-[13px] font-semibold text-foreground">候选文档</h5>
              <span class="text-xs text-muted-foreground">{{ selectedRecord.documents.length }} 份</span>
            </div>
            <div class="flex flex-col gap-2">
              <article v-for="(candidate, index) in selectedRecord.documents" :key="`doc-${candidate.documentId || index}`"
                class="flex gap-3 rounded-lg border p-3"
                :class="index === 0 ? 'border-primary/20 bg-gradient-to-br from-primary/[0.05] to-white/90' : 'border-border bg-background'">
                <div class="grid h-6 w-6 shrink-0 place-items-center rounded-full border text-[11px] font-bold"
                  :class="index === 0 ? 'border-primary bg-primary text-white' : 'border-border bg-secondary text-muted-foreground'">{{ index + 1 }}</div>
                <div class="flex-1 min-w-0">
                  <strong class="mb-0.5 block text-foreground">{{ candidate.documentName || candidate.documentId }}</strong>
                  <span class="mb-1 inline-block text-xs text-muted-foreground">分数 {{ candidate.scoreText }}</span>
                  <small class="block text-xs leading-relaxed text-muted-foreground">{{ candidate.reason || '基于文档画像与元数据综合召回' }}</small>
                </div>
              </article>
            </div>
          </section>

          <div class="mb-5 grid grid-cols-2 gap-3 max-[860px]:grid-cols-1">
            <section v-for="group in candidateGroups" :key="group.title">
              <div class="mb-2.5 flex items-center justify-between gap-2.5">
                <h5 class="m-0 text-[13px] font-semibold text-foreground">{{ group.title }}</h5>
                <span class="text-xs text-muted-foreground">{{ group.count }} 个</span>
              </div>
              <div v-if="group.items.length" class="flex flex-wrap gap-1.5">
                <span v-for="(c, i) in group.items" :key="i" class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-foreground">{{ c.name }} · {{ c.scoreText }}</span>
              </div>
              <p v-else class="text-xs text-muted-foreground">{{ group.empty }}</p>
            </section>
          </div>

          <details class="border-t border-border pt-3">
            <summary class="cursor-pointer text-[13px] font-semibold text-primary">查看原始 JSON</summary>
            <div class="mt-3 grid grid-cols-3 gap-2.5 max-[860px]:grid-cols-1">
              <pre v-for="json in rawJsonBlocks" :key="json" class="m-0 overflow-auto rounded-lg bg-[#0f172a] p-3 text-xs text-[#e2e8f0]">{{ formatJson(json) }}</pre>
            </div>
          </details>
        </template>
      </main>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { manageApi } from '../../api/api'
import { formatDateTime } from './observabilityHelpers'
import { buildTopDocumentDistribution, normalizeRouteTrace, summarizeRouteTraceRecords } from '../../utils/knowledgeRoute'
import { Button } from '@/components/ui/button'

const loading = ref(false)
const records = ref([])
const selectedId = ref(null)
const insightCollapsed = ref(true)
const filters = reactive({ conversationId: '', mode: '', routeStatus: '' })
const page = reactive({ pageNo: '1', pageSize: '20', totalSize: '0', totalPages: '0' })

const normalizedRecords = computed(() => (records.value || []).map((item) => normalizeRouteTrace(item)))
const selectedRecord = computed(() => normalizedRecords.value.find((r) => r.id === selectedId.value) || null)
const traceStats = computed(() => summarizeRouteTraceRecords(records.value || []))
const topDocumentDistribution = computed(() => buildTopDocumentDistribution(records.value || []))

const pageStyle = computed(() => ({
  height: 'calc(100vh - 52px - 40px)',
  overflow: 'hidden'
}))

const headerStats = computed(() => [
  { label: '总追踪量', value: page.totalSize || '0' },
  { label: '成功率', value: traceStats.value.successRateText },
  { label: '平均置信度', value: traceStats.value.averageConfidenceText },
  { label: 'shadow 命中率', value: traceStats.value.shadowHitRateText },
  { label: '低置信或失败', value: String(traceStats.value.lowConfidenceCount + traceStats.value.failedCount) }
])

const summaryCards = computed(() => [
  { label: '总追踪量', value: page.totalSize || '0' },
  { label: '本页 auto', value: String(traceStats.value.autoCount) },
  { label: '本页 shadow', value: String(traceStats.value.shadowCount) },
  { label: '高置信', value: String(traceStats.value.highConfidenceCount) },
  { label: '低置信或失败', value: String(traceStats.value.lowConfidenceCount + traceStats.value.failedCount) },
  { label: 'shadow Top3 命中率', value: traceStats.value.shadowHitRateText },
  { label: '平均置信度', value: traceStats.value.averageConfidenceText },
  { label: '成功率', value: traceStats.value.successRateText },
  { label: '低置信率', value: traceStats.value.lowConfidenceRateText },
  { label: '均候选文档', value: traceStats.value.averageDocumentCountText },
  { label: '扩范围次数', value: String(traceStats.value.widenedCount) }
])

const routeHealthCards = computed(() => [
  { label: '成功率', value: traceStats.value.successRateText, percent: normalizePercent(traceStats.value.successRateText), tone: 'success', description: '越高说明自动候选越稳定。' },
  { label: '低置信率', value: traceStats.value.lowConfidenceRateText, percent: normalizePercent(traceStats.value.lowConfidenceRateText), tone: 'warning', description: '越高说明范围、主题或画像还需要补强。' },
  { label: '候选文档均值', value: traceStats.value.averageDocumentCountText, percent: `${Math.min(100, Number(traceStats.value.averageDocumentCountText || 0) * 20)}%`, tone: 'neutral', description: '高置信时通常接近 3，低置信时会放宽到 5。' }
])

const detailSummaryCards = computed(() => {
  if (!selectedRecord.value) return []
  return [
    { label: '实际落点', value: actualSelectionText(selectedRecord.value), desc: hitConclusion(selectedRecord.value) },
    { label: '候选规模', value: `${selectedRecord.value.candidateDocumentCount} 文档 / ${selectedRecord.value.candidateTopicCount} 主题 / ${selectedRecord.value.candidateScopeCount} 范围`, desc: candidateConclusion(selectedRecord.value) },
    { label: '观察建议', value: recommendationTitle(selectedRecord.value), desc: recommendationText(selectedRecord.value) }
  ]
})

const candidateGroups = computed(() => {
  if (!selectedRecord.value) return []
  return [
    {
      title: '范围候选', count: selectedRecord.value.scopes.length,
      items: selectedRecord.value.scopes.map((c) => ({ name: c.scopeName || c.scopeCode, scoreText: c.scoreText })),
      empty: '当前没有显式范围候选。'
    },
    {
      title: '主题候选', count: selectedRecord.value.topics.length,
      items: selectedRecord.value.topics.map((c) => ({ name: c.topicName || c.topicCode, scoreText: c.scoreText })),
      empty: '当前没有显式主题候选。'
    }
  ]
})

const rawJsonBlocks = computed(() => selectedRecord.value
  ? [selectedRecord.value.topScopesJson, selectedRecord.value.topTopicsJson, selectedRecord.value.topDocumentsJson]
  : []
)

function chipClass(tone) {
  if (tone === 'success') return 'bg-green-500/[0.12] text-green-700'
  if (tone === 'warning') return 'bg-amber-500/[0.14] text-amber-700'
  if (tone === 'danger') return 'bg-red-500/[0.12] text-red-700'
  return 'bg-foreground/[0.06] text-foreground'
}

function healthFillClass(tone) {
  if (tone === 'success') return 'block h-full rounded-full bg-gradient-to-r from-green-500 to-green-600'
  if (tone === 'warning') return 'block h-full rounded-full bg-gradient-to-r from-amber-500 to-amber-600'
  return 'block h-full rounded-full bg-gradient-to-r from-primary to-[#0f766e]'
}

function selectRecord(item) { selectedId.value = item.id }

async function loadTraces(nextPage = page.pageNo) {
  loading.value = true
  try {
    const data = await manageApi.queryKnowledgeRouteTracePage({ ...filters, pageNo: String(nextPage), pageSize: page.pageSize })
    records.value = data?.records || []
    page.pageNo = data?.pageNo || '1'
    page.pageSize = data?.pageSize || page.pageSize
    page.totalSize = data?.totalSize || '0'
    page.totalPages = data?.totalPages || '0'
    selectedId.value = null
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.conversationId = ''
  filters.mode = ''
  filters.routeStatus = ''
  loadTraces('1')
}

function changePage(nextPage) {
  if (nextPage <= 0) return
  loadTraces(String(nextPage))
}

function normalizePercent(value) {
  const numeric = Number(String(value || '').replace('%', ''))
  if (!Number.isFinite(numeric)) return '0%'
  return `${Math.max(0, Math.min(100, numeric))}%`
}

function formatJson(value) {
  if (!value) return '[]'
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

function shortenId(value) {
  const normalized = String(value || '')
  if (normalized.length <= 14) return normalized || '-'
  return `${normalized.slice(0, 6)}...${normalized.slice(-6)}`
}

function primaryDocumentText(item) {
  return item.topDocument?.documentName || item.topDocument?.documentId || '未形成显式主候选'
}

function actualSelectionText(item) {
  if (item.mode === 'auto') return item.topDocument?.documentName || item.topDocument?.documentId || '执行期可能回退到通用可检索文档池'
  return item.selectedDocument?.documentName || item.selectedDocumentId || '未记录当前文档'
}

function hitConclusion(item) {
  if (item.mode === 'auto') return item.topDocument ? '自动模式会以该主候选为中心，再进入稳定检索主链。' : '当前没有明确主候选，说明需要继续补范围、主题或文档画像。'
  if (item.hitTop3) return '影子路由 Top3 已覆盖当前文档，人工选择与自动路由基本一致。'
  if (item.missedTop3) return '影子路由 Top3 未覆盖当前文档，说明这轮问题可能更像跨文档。'
  return '当前样本还不足以判断影子路由与人工选择是否一致。'
}

function candidateConclusion(item) {
  if (item.lowConfidenceWidened) return '当前是低置信样本，系统已经自动放宽候选文档规模。'
  if (!item.documents.length) return '当前没有候选文档，优先检查文档画像、主题关联与标签是否完整。'
  return '候选规模已经稳定，后续主要观察 Top1 和 Top3 的命中情况。'
}

function recommendationTitle(item) {
  if (item.mode === 'auto' && item.statusKey === 'SUCCESS' && (item.confidenceNumber || 0) >= 0.8) return '可以继续扩大样本观察'
  if (item.lowConfidenceWidened || item.statusKey === 'LOW_CONFIDENCE') return '建议补强知识范围和主题别名'
  if (item.statusKey === 'FAILED') return '建议优先排查空路由原因'
  if (item.mode === 'shadow' && item.missedTop3) return '建议检查当前文档是否放错范围'
  return '当前配置可继续保留'
}

function recommendationText(item) {
  if (item.lowConfidenceWidened || item.statusKey === 'LOW_CONFIDENCE') return '优先补 documentTags、knowledgeScopeName、topic 别名，以及 topic-document relation 的人工确认。'
  if (item.statusKey === 'FAILED') return '当前路由没有形成稳定候选，先检查上传元数据、文档画像和主题树是否为空。'
  if (item.mode === 'shadow' && item.missedTop3) return '人工选文档和自动路由差异较大，建议对比问题表达与文档画像的关键词覆盖情况。'
  return '当前样本已经接近可教学展示状态，下一步重点看不同问题类型下是否还能持续稳定。'
}

onMounted(() => loadTraces('1'))
</script>
