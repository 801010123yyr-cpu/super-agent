<template>
  <section class="flex flex-col gap-6">
    <!-- 页头 -->
    <header class="border-b border-border pb-5">
      <div class="flex items-start justify-between gap-4 max-[640px]:flex-col">
        <div>
          <span class="font-mono text-xs uppercase tracking-widest text-muted-foreground">Conversation Observatory</span>
          <h2 class="my-2.5 text-[22px] font-semibold leading-snug text-foreground">先选会话，再进入整页观测详情</h2>
          <p class="m-0 max-w-[680px] text-sm leading-relaxed text-[var(--color-muted-strong)]">
            列表页只负责定位问题会话，详情页再按单轮执行阶段展开。这样不会把大量轨迹信息压缩在同一块区域里，
            也更适合教学演示和排障复盘。
          </p>
        </div>
        <Button size="sm" type="button" :disabled="loadingSessions" @click="loadSessions">
          {{ loadingSessions ? '正在刷新...' : '刷新会话列表' }}
        </Button>
      </div>

      <div class="mt-4 flex flex-wrap gap-1.5">
        <span
          v-for="item in summaryStats"
          :key="item.label"
          :title="item.description"
          class="inline-flex items-center gap-1.5 rounded-md bg-secondary px-3 py-1.5 text-[13px] text-[var(--color-muted-strong)]"
        >
          <span class="text-muted-foreground">{{ item.label }}</span>
          <strong class="font-mono text-sm text-foreground">{{ item.value }}</strong>
        </span>
      </div>
    </header>

    <!-- 筛选栏 -->
    <section class="flex flex-wrap items-end gap-3 border-b border-border pb-4 max-[980px]:flex-col max-[980px]:items-stretch">
      <div class="flex min-w-[200px] flex-1 flex-col gap-1.5">
        <span class="text-xs uppercase tracking-wider text-muted-foreground">搜索会话</span>
        <Input
          v-model.trim="keyword"
          type="text"
          placeholder="按会话ID、文档名、问题或回答筛选"
          class="h-10"
          @keydown.enter.prevent="applyFilters"
        />
      </div>

      <div class="flex flex-col gap-1.5">
        <span class="text-xs uppercase tracking-wider text-muted-foreground">提问模式</span>
        <select
          v-model="modeFilter"
          class="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
        >
          <option value="ALL">全部模式</option>
          <option value="DOCUMENT">当前文档问答</option>
          <option value="AUTO_DOCUMENT">自动知识问答</option>
          <option value="OPEN_CHAT">开放式提问</option>
        </select>
      </div>

      <div class="flex flex-col gap-1.5">
        <span class="text-xs uppercase tracking-wider text-muted-foreground">最近状态</span>
        <select
          v-model="statusFilter"
          class="h-10 rounded-md border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
        >
          <option value="ALL">全部状态</option>
          <option value="RUNNING">进行中</option>
          <option value="COMPLETED">已完成</option>
          <option value="FAILED">失败</option>
          <option value="STOPPED">已停止</option>
        </select>
      </div>

      <div class="flex gap-2 max-[980px]:justify-end">
        <Button variant="outline" size="sm" type="button" :disabled="loadingSessions" @click="resetFilters">重置筛选</Button>
        <Button size="sm" type="button" :disabled="loadingSessions" @click="applyFilters">应用筛选</Button>
      </div>
    </section>

    <!-- 错误 / 空态 / 加载 -->
    <div v-if="pageError" class="rounded-md border border-destructive/10 bg-destructive/[0.06] px-3.5 py-3 text-sm text-destructive">{{ pageError }}</div>
    <div v-if="loadingSessions" class="rounded-md border border-dashed border-border px-6 py-12 text-center text-sm text-muted-foreground">正在加载会话列表...</div>
    <div v-else-if="!sessions.length" class="rounded-md border border-dashed border-border px-6 py-12 text-center text-sm leading-relaxed text-muted-foreground">
      当前筛选条件下没有匹配的会话。可以先清空筛选，或者去聊天页发起一轮对话再回来观察。
    </div>

    <!-- 会话列表 -->
    <div v-else class="flex flex-col divide-y divide-border">
      <article
        v-for="session in sessions"
        :key="session.conversationId"
        class="relative border-l-4 pl-1 transition-colors hover:bg-secondary"
        :class="{
          'border-l-[#0d7c7c]': sessionTone(session) === 'running',
          'border-l-[var(--color-success)]': sessionTone(session) === 'completed',
          'border-l-[var(--color-danger)]': sessionTone(session) === 'failed',
          'border-l-[var(--color-warning)]': sessionTone(session) === 'stopped',
          'border-l-transparent': !['running','completed','failed','stopped'].includes(sessionTone(session))
        }"
      >
        <RouterLink :to="detailTarget(session)" class="block px-4 pb-3 pt-4">
          <div class="mb-2 flex items-center justify-between gap-3 max-[640px]:flex-col max-[640px]:items-start">
            <div class="flex flex-wrap gap-1.5">
              <Badge variant="secondary" class="rounded">{{ formatChatMode(session.chatMode) }}</Badge>
              <Badge v-if="session.running" class="rounded bg-[#0d7c7c]/10 text-[#0d7c7c]" variant="outline">实时执行中</Badge>
              <Badge
                v-else-if="session.latestTurnStatus"
                class="rounded"
                :class="{
                  'bg-[var(--color-success)]/10 text-[var(--color-success)] border-0': statusTone(session.latestTurnStatus) === 'completed',
                  'bg-destructive/10 text-destructive border-0': statusTone(session.latestTurnStatus) === 'failed',
                  'bg-[var(--color-warning)]/10 text-[var(--color-warning)] border-0': statusTone(session.latestTurnStatus) === 'stopped'
                }"
                variant="outline"
              >{{ formatStatusLabel(session.latestTurnStatus) }}</Badge>
            </div>
            <span class="whitespace-nowrap text-xs text-muted-foreground">{{ formatTime(session.updatedAt) }}</span>
          </div>

          <h3 class="mb-1.5 text-[15px] font-semibold leading-snug text-foreground">{{ sessionTitle(session) }}</h3>
          <p class="mb-2.5 text-[13px] leading-relaxed text-[var(--color-muted-strong)]">{{ sessionPreview(session) }}</p>

          <div class="flex flex-wrap gap-3 text-xs text-muted-foreground">
            <code class="break-all font-mono text-[11px]">{{ session.conversationId }}</code>
            <span>{{ sessionMessageCount(session) }} 条消息</span>
            <span v-if="session.selectedDocumentName">{{ session.selectedDocumentName }}</span>
          </div>

          <p v-if="session.latestTurnErrorMessage" class="mt-2.5 rounded-md bg-destructive/[0.06] px-3 py-2 text-[13px] leading-relaxed text-destructive">
            最近一轮异常：{{ truncate(session.latestTurnErrorMessage, 88) }}
          </p>
        </RouterLink>

        <div class="flex gap-4 px-4 pb-3.5">
          <RouterLink :to="detailTarget(session)" class="text-[13px] font-semibold text-[var(--color-primary-strong)] hover:underline">查看整页详情</RouterLink>
          <RouterLink
            v-if="session.latestExchangeId"
            :to="exchangeTarget(session)"
            class="text-[13px] font-semibold text-[var(--color-muted-strong)] hover:underline"
          >{{ exchangeLinkLabel(session) }}</RouterLink>
        </div>
      </article>
    </div>

    <!-- 分页 -->
    <nav v-if="!loadingSessions && totalPagesCount > 0" class="flex items-center justify-between gap-4 border-t border-border pt-4 max-[980px]:flex-col max-[980px]:items-stretch">
      <div class="flex flex-col gap-1 text-[13px] text-[var(--color-muted-strong)]">
        <strong class="text-foreground">第 {{ pageNo }} / {{ totalPages }} 页</strong>
        <span>共 {{ totalSize }} 条会话记录</span>
      </div>

      <div class="flex items-center gap-3.5 max-[980px]:flex-col max-[980px]:items-stretch">
        <label class="flex items-center gap-2 text-[13px] text-muted-foreground max-[980px]:justify-between">
          每页
          <select
            v-model="pageSize"
            class="rounded-md border border-input bg-background px-2 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            @change="handlePageSizeChange"
          >
            <option value="12">12</option>
            <option value="24">24</option>
            <option value="36">36</option>
            <option value="48">48</option>
          </select>
        </label>

        <div class="flex flex-wrap gap-1">
          <button class="min-w-[36px] rounded-md border border-input bg-background px-2.5 py-1.5 text-[13px] font-semibold text-foreground hover:enabled:border-border-strong hover:enabled:bg-secondary disabled:cursor-default disabled:opacity-45" type="button" :disabled="!canPrev" @click="goPrevPage">上一页</button>
          <button
            v-for="(item, index) in paginationItems"
            :key="`page-${item}-${index}`"
            class="min-w-[36px] rounded-md border px-2.5 py-1.5 text-[13px] font-semibold transition-colors disabled:cursor-default disabled:opacity-45"
            :class="item === pageNo
              ? 'border-primary bg-primary/[0.08] text-[var(--color-primary-strong)]'
              : item === '...'
                ? 'border-dashed border-input bg-transparent text-muted-foreground'
                : 'border-input bg-background text-foreground hover:border-border-strong hover:bg-secondary'"
            type="button"
            :disabled="item === '...'"
            @click="typeof item === 'string' && item !== '...' ? goPage(item) : null"
          >{{ item }}</button>
          <button class="min-w-[36px] rounded-md border border-input bg-background px-2.5 py-1.5 text-[13px] font-semibold text-foreground hover:enabled:border-border-strong hover:enabled:bg-secondary disabled:cursor-default disabled:opacity-45" type="button" :disabled="!canNext" @click="goNextPage">下一页</button>
        </div>
      </div>
    </nav>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { chatApi } from '../../api/api'
import {
  formatChatMode,
  formatStatusLabel,
  formatTime,
  normalizeError,
  sessionMessageCount,
  sessionPreview,
  sessionTitle,
  statusTone,
  truncate
} from './observabilityHelpers'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'

const sessions = ref([])
const loadingSessions = ref(false)
const pageError = ref('')
const keyword = ref('')
const modeFilter = ref('ALL')
const statusFilter = ref('ALL')
const pageNo = ref('1')
const pageSize = ref('12')
const totalSize = ref('0')
const totalPages = ref('0')

const currentPageNumber = computed(() => Number(pageNo.value || '1') || 1)
const totalPagesCount = computed(() => Number(totalPages.value || '0') || 0)
const canPrev = computed(() => currentPageNumber.value > 1)
const canNext = computed(() => totalPagesCount.value > 0 && currentPageNumber.value < totalPagesCount.value)

const summaryStats = computed(() => {
  const total = totalSize.value
  const running = sessions.value.filter((item) => item.running).length
  const documentMode = sessions.value.filter((item) => item.chatMode === 'DOCUMENT').length
  const failed = sessions.value.filter((item) => item.latestTurnStatus === 'FAILED').length
  return [
    { label: '会话总数', value: total, description: '后台当前可回看的全部业务会话数' },
    { label: '本页运行中', value: running, description: '正在生成中的会话会在详情页实时轮询' },
    { label: '本页文档问答', value: documentMode, description: '当前页里走 RAG 编排链路的会话规模' },
    { label: '本页最近失败', value: failed, description: '优先进入这些会话可更快定位问题' }
  ]
})

const paginationItems = computed(() => {
  const total = totalPagesCount.value
  const current = currentPageNumber.value
  if (total <= 7) return Array.from({ length: total }, (_, i) => String(i + 1))
  if (current <= 4) return ['1', '2', '3', '4', '5', '...', String(total)]
  if (current >= total - 3) return ['1', '...', String(total - 4), String(total - 3), String(total - 2), String(total - 1), String(total)]
  return ['1', '...', String(current - 1), String(current), String(current + 1), '...', String(total)]
})

async function loadSessions(options = {}) {
  loadingSessions.value = true
  pageError.value = ''
  try {
    const page = await chatApi.listSessionsPage({
      keyword: options.keyword ?? keyword.value,
      chatMode: options.chatMode ?? modeFilter.value,
      turnStatus: options.turnStatus ?? statusFilter.value,
      pageNo: options.pageNo || pageNo.value,
      pageSize: options.pageSize || pageSize.value
    })
    sessions.value = page.sessions || []
    pageNo.value = page.pageNo || '1'
    pageSize.value = page.pageSize || pageSize.value
    totalSize.value = page.totalSize || '0'
    totalPages.value = page.totalPages || '0'
  } catch (error) {
    pageError.value = normalizeError(error, '加载会话列表失败')
  } finally {
    loadingSessions.value = false
  }
}

function sessionTone(session) {
  return session.running ? 'running' : statusTone(session.latestTurnStatus)
}

function goPage(nextPageNo) {
  if (!nextPageNo || nextPageNo === pageNo.value || loadingSessions.value) return
  loadSessions({ keyword: keyword.value, chatMode: modeFilter.value, turnStatus: statusFilter.value, pageNo: String(nextPageNo), pageSize: pageSize.value })
}

function goPrevPage() { if (canPrev.value) goPage(String(currentPageNumber.value - 1)) }
function goNextPage() { if (canNext.value) goPage(String(currentPageNumber.value + 1)) }

function handlePageSizeChange() {
  loadSessions({ keyword: keyword.value, chatMode: modeFilter.value, turnStatus: statusFilter.value, pageNo: '1', pageSize: pageSize.value })
}

function applyFilters() {
  loadSessions({ keyword: keyword.value, chatMode: modeFilter.value, turnStatus: statusFilter.value, pageNo: '1', pageSize: pageSize.value })
}

function resetFilters() {
  keyword.value = ''
  modeFilter.value = 'ALL'
  statusFilter.value = 'ALL'
  loadSessions({ keyword: '', chatMode: 'ALL', turnStatus: 'ALL', pageNo: '1', pageSize: pageSize.value })
}

function detailTarget(session) {
  return { name: 'AdminObservabilitySession', params: { conversationId: session.conversationId } }
}

function exchangeTarget(session) {
  return { name: 'AdminObservabilityExchangeDetail', params: { conversationId: session.conversationId, exchangeId: String(session.latestExchangeId) } }
}

function exchangeLinkLabel(session) {
  if (session.running) return '直达当前轮次'
  if (session.latestTurnStatus === 'FAILED' || session.latestTurnStatus === 'STOPPED') return '直达异常轮次'
  return '直达最近轮次'
}

onMounted(loadSessions)
</script>
