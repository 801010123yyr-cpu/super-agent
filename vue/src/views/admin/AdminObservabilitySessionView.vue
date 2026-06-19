<template>
  <section class="flex flex-col gap-6">
    <!-- 工具栏 -->
    <div class="flex items-center justify-between gap-3 max-[760px]:flex-col max-[760px]:items-stretch">
      <RouterLink
        :to="{ name: 'AdminObservabilityList' }"
        class="inline-flex items-center gap-1.5 rounded-md border border-border bg-card px-3 py-2 text-sm font-semibold text-foreground hover:border-border-strong hover:bg-secondary"
      >
        <ArrowLeftIcon class="h-4 w-4" />
        返回会话列表
      </RouterLink>

      <div class="flex flex-wrap items-center gap-2">
        <span v-if="activeSession?.running || pollingSession" class="inline-flex items-center gap-1.5 rounded bg-[#0d7c7c]/10 px-2.5 py-1.5 text-xs font-semibold text-[#0d7c7c]">
          <span class="h-1.5 w-1.5 rounded-full bg-current"></span>
          {{ pollingSession ? '实时轮询中' : '会话运行中' }}
        </span>
        <Button variant="outline" size="sm" :disabled="loadingSession" @click="loadSession()">
          <ArrowPathIcon class="h-4 w-4" />
          {{ loadingSession ? '刷新中...' : '刷新会话详情' }}
        </Button>
        <Button size="sm" :disabled="!activeSession || rebuildingSummary" @click="rebuildSummary">
          <SparklesIcon class="h-4 w-4" />
          {{ rebuildingSummary ? '正在重建摘要...' : '重建长期摘要' }}
        </Button>
      </div>
    </div>

    <div v-if="pageError" class="rounded-md border border-destructive/10 bg-destructive/[0.06] px-3.5 py-3 text-sm text-destructive">{{ pageError }}</div>
    <div v-if="loadingSession && !activeSession" class="rounded-md border border-dashed border-border px-6 py-10 text-center text-sm text-muted-foreground">正在加载会话详情...</div>
    <div v-else-if="!activeSession" class="rounded-md border border-dashed border-border px-6 py-10 text-center text-sm text-muted-foreground">没有找到这条会话，请返回列表重新选择。</div>

    <template v-else>
      <!-- 页头 -->
      <header class="border-b border-border pb-5">
        <span class="font-mono text-[11px] uppercase tracking-widest text-muted-foreground">Conversation Chain</span>
        <h2 class="my-1.5 text-xl font-semibold leading-snug text-foreground">{{ activeSession.selectedDocumentName || sessionTitle(activeSession) }}</h2>
        <p class="m-0 text-[13px] leading-relaxed text-[var(--color-muted-strong)]">
          这个页面只负责看整条会话里的每次问答，不展示单轮内部细节。先从下方轮次列表里找到你关心的那一轮，再进入专门的轮次详情页。
        </p>

        <div class="mt-3.5 flex flex-wrap gap-1.5">
          <span class="inline-flex items-center rounded bg-[#17304f]/[0.07] px-2.5 py-1 text-xs font-semibold text-[var(--color-muted-strong)]">{{ formatChatMode(activeSession.chatMode) }}</span>
          <span v-if="activeSession.running" class="inline-flex items-center rounded bg-[#0d7c7c]/10 px-2.5 py-1 text-xs font-semibold text-[#0d7c7c]">当前会话仍在执行</span>
          <span v-else-if="activeSession.latestTurnStatus" class="inline-flex items-center rounded px-2.5 py-1 text-xs font-semibold"
            :class="{
              'bg-[var(--color-success)]/10 text-[var(--color-success)]': statusTone(activeSession.latestTurnStatus) === 'completed',
              'bg-destructive/10 text-destructive': statusTone(activeSession.latestTurnStatus) === 'failed',
              'bg-[var(--color-warning)]/10 text-[var(--color-warning)]': statusTone(activeSession.latestTurnStatus) === 'stopped',
              'bg-[#0d7c7c]/10 text-[#0d7c7c]': statusTone(activeSession.latestTurnStatus) === 'running',
            }"
          >最近一轮{{ formatStatusLabel(activeSession.latestTurnStatus) }}</span>
          <span class="inline-flex items-center rounded bg-foreground/[0.06] px-2.5 py-1 text-xs font-semibold text-[var(--color-muted-strong)]">会话ID {{ activeSession.conversationId }}</span>
          <span v-for="item in sessionMetrics" :key="item.label" class="inline-flex items-center gap-1.5 rounded bg-secondary px-2.5 py-1 text-xs">
            <span class="text-muted-foreground">{{ item.label }}</span>
            <strong class="font-mono text-foreground">{{ item.value }}</strong>
          </span>
        </div>
      </header>

      <!-- 会话上下文 -->
      <section class="border-b border-border pb-5">
        <span class="font-mono text-[11px] uppercase tracking-widest text-muted-foreground">Session Context</span>
        <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">会话级背景</h3>
        <p class="m-0 text-[13px] leading-relaxed text-[var(--color-muted-strong)]">只解释整条会话的上下文、最近状态和记忆压缩，不进入某一轮内部链路。</p>

        <dl class="mt-4 flex flex-col divide-y divide-black/[0.04]">
          <div v-for="item in contextItems" :key="item.dt" class="flex gap-4 py-2.5 max-[760px]:flex-col max-[760px]:gap-1">
            <dt class="w-[140px] shrink-0 text-[13px] text-muted-foreground max-[760px]:w-auto">{{ item.dt }}</dt>
            <dd class="m-0 break-words text-[13px] leading-relaxed text-foreground">{{ item.dd }}</dd>
          </div>
        </dl>

        <div v-if="activeSession.memorySummary?.compressionApplied" class="mt-4 border-t border-border pt-4">
          <span class="font-mono text-[11px] uppercase tracking-widest text-muted-foreground">Memory</span>
          <h4 class="mb-2.5 mt-1 text-[15px] font-semibold text-foreground">长期摘要快照</h4>
          <div class="mb-2.5 flex flex-wrap gap-1.5">
            <span v-for="chip in memoryChips" :key="chip" class="inline-flex rounded bg-[#17304f]/[0.06] px-2 py-0.5 font-mono text-[11px] font-semibold text-[var(--color-muted-strong)]">{{ chip }}</span>
          </div>
          <pre class="m-0 rounded-md bg-secondary p-3 font-mono text-[13px] leading-relaxed text-foreground whitespace-pre-wrap">{{ activeSession.memorySummary?.summaryText || '无' }}</pre>
        </div>
        <div v-else class="mt-3 rounded-md bg-secondary p-3 text-[13px] text-[var(--color-muted-strong)]">
          当前会话还没有形成长期摘要。常见原因是轮次还不够，或者摘要预热尚未完成。
        </div>
      </section>

      <!-- 轮次时间线 -->
      <section class="flex flex-col gap-3">
        <div>
          <span class="font-mono text-[11px] uppercase tracking-widest text-muted-foreground">Round List</span>
          <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">本会话的每次一来一回</h3>
          <p class="m-0 text-[13px] leading-relaxed text-[var(--color-muted-strong)]">这里是整条会话的轮次总览，点击某一轮后会跳转到独立的轮次详情页。</p>
        </div>

        <div v-if="!assistantExchanges.length" class="rounded-md border border-dashed border-border px-6 py-6 text-center text-sm text-muted-foreground">
          当前会话还没有助手轮次，无法展示执行链路。
        </div>

        <div v-else class="flex flex-col">
          <article
            v-for="(exchange, index) in assistantExchanges"
            :key="exchange.exchangeId"
            class="flex gap-4"
          >
            <div class="flex w-5 shrink-0 flex-col items-center pt-[18px]">
              <span class="z-[1] h-2.5 w-2.5 shrink-0 rounded-full"
                :class="{
                  'bg-[#0d7c7c]': statusTone(exchange.status) === 'running',
                  'bg-[var(--color-success)]': statusTone(exchange.status) === 'completed',
                  'bg-[var(--color-danger)]': statusTone(exchange.status) === 'failed',
                  'bg-[var(--color-warning)]': statusTone(exchange.status) === 'stopped',
                  'bg-border-strong': !['running','completed','failed','stopped'].includes(statusTone(exchange.status))
                }"
              ></span>
              <span v-if="index < assistantExchanges.length - 1" class="w-0.5 flex-1 bg-border"></span>
            </div>

            <RouterLink class="flex min-w-0 flex-1 flex-col gap-2 border-b border-black/[0.04] pb-5 pt-3.5 hover:no-underline last:border-0" :to="exchangeTarget(exchange)">
              <div class="flex items-center justify-between gap-3 max-[760px]:flex-col max-[760px]:items-start">
                <div class="flex flex-wrap items-center gap-1.5">
                  <span class="font-mono text-[13px] font-semibold text-foreground">第 {{ index + 1 }} 轮</span>
                  <span class="inline-flex rounded px-2 py-0.5 text-[11px] font-semibold"
                    :class="{
                      'bg-[var(--color-success)]/10 text-[var(--color-success)]': statusTone(exchange.status) === 'completed',
                      'bg-destructive/10 text-destructive': statusTone(exchange.status) === 'failed',
                      'bg-[var(--color-warning)]/10 text-[var(--color-warning)]': statusTone(exchange.status) === 'stopped',
                      'bg-[#0d7c7c]/10 text-[#0d7c7c]': statusTone(exchange.status) === 'running',
                      'bg-foreground/[0.06] text-foreground': !['completed','failed','stopped','running'].includes(statusTone(exchange.status))
                    }"
                  >{{ formatStatusLabel(exchange.status) }}</span>
                  <span v-if="exchange.debugTrace?.executionMode" class="inline-flex rounded bg-[#17304f]/[0.07] px-2 py-0.5 text-[11px] font-semibold text-[var(--color-muted-strong)]">{{ formatExecutionMode(exchange.debugTrace.executionMode) }}</span>
                </div>
                <span class="whitespace-nowrap text-xs text-muted-foreground">{{ formatDateTime(exchange.editTime || exchange.createTime) }}</span>
              </div>

              <div class="flex flex-col gap-1">
                <p class="m-0 text-[13px] leading-relaxed text-foreground"><strong>问：</strong>{{ exchange.question || '未记录问题' }}</p>
                <p class="m-0 text-[13px] leading-relaxed text-[var(--color-muted-strong)]"><strong>答：</strong>{{ truncate(exchange.answer || '还没有回答内容', 200) }}</p>
              </div>

              <div class="flex flex-wrap gap-3 text-xs text-muted-foreground">
                <span>耗时 {{ exchange.totalResponseTimeMs ? `${exchange.totalResponseTimeMs} ms` : '无' }}</span>
                <span>引用 {{ exchange.references?.length || 0 }}</span>
                <span>推荐 {{ exchange.recommendations?.length || 0 }}</span>
                <span>Token {{ exchangeTokenCount(exchange) }}</span>
                <span>成本 {{ exchangeCost(exchange) }}</span>
              </div>

              <span class="text-[13px] font-semibold text-[var(--color-primary-strong)] group-hover:underline">进入这一轮的详情页 →</span>
            </RouterLink>
          </article>
        </div>
      </section>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ArrowLeftIcon, ArrowPathIcon, SparklesIcon } from '@heroicons/vue/24/outline'
import { chatApi } from '../../api/api'
import {
  formatChatMode,
  formatDateTime,
  formatExecutionMode,
  formatStatusLabel,
  listAssistantExchanges,
  normalizeError,
  sessionPreview,
  sessionTitle,
  statusTone,
  truncate
} from './observabilityHelpers'
import { Button } from '@/components/ui/button'

const route = useRoute()
const loadingSession = ref(false)
const pollingSession = ref(false)
const activeSession = ref(null)
const pageError = ref('')
const rebuildingSummary = ref(false)

const POLL_INTERVAL_MS = 2500
let pollTimer = 0
let sessionRequestInFlight = false

const conversationId = computed(() => String(route.params.conversationId || ''))
const assistantExchanges = computed(() => listAssistantExchanges(activeSession.value))

const sessionMetrics = computed(() => {
  if (!activeSession.value) return []
  return [
    { label: '助手轮次', value: assistantExchanges.value.length },
    { label: '会话消息数', value: activeSession.value.messageCount || 0 },
    { label: '长期摘要', value: activeSession.value.memorySummary?.compressionApplied ? '已形成' : '未形成' },
    { label: '最近更新时间', value: formatDateTime(activeSession.value.updatedAt) }
  ]
})

const contextItems = computed(() => {
  if (!activeSession.value) return []
  return [
    { dt: '最近用户问题', dd: activeSession.value.latestUserMessage || '无' },
    { dt: '最近助手回答', dd: sessionPreview(activeSession.value) },
    { dt: 'Checkpoint / 消息数', dd: `${activeSession.value.checkpointCount || 0} / ${activeSession.value.messageCount || 0}` }
  ]
})

const memoryChips = computed(() => {
  const m = activeSession.value?.memorySummary
  if (!m) return []
  return [`covered ${m.coveredExchangeCount ?? 0}`, `version ${m.summaryVersion ?? 0}`, `compress ${m.compressionCount ?? 0}`]
})

async function loadSession(options = {}) {
  if (!conversationId.value || sessionRequestInFlight) return
  const silent = Boolean(options.silent)
  sessionRequestInFlight = true
  if (silent) { pollingSession.value = true } else { loadingSession.value = true }
  pageError.value = ''
  try {
    activeSession.value = await chatApi.getSession(conversationId.value)
  } catch (error) {
    activeSession.value = null
    pageError.value = normalizeError(error, '加载会话详情失败')
  } finally {
    sessionRequestInFlight = false
    loadingSession.value = false
    pollingSession.value = false
    schedulePolling()
  }
}

function schedulePolling() {
  clearTimeout(pollTimer)
  if (!activeSession.value?.running) return
  pollTimer = window.setTimeout(() => loadSession({ silent: true }), POLL_INTERVAL_MS)
}

async function rebuildSummary() {
  if (!conversationId.value || rebuildingSummary.value) return
  rebuildingSummary.value = true
  pageError.value = ''
  try {
    const summary = await chatApi.rebuildConversationSummary(conversationId.value)
    if (activeSession.value?.conversationId === conversationId.value) {
      activeSession.value = { ...activeSession.value, memorySummary: summary }
    }
  } catch (error) {
    pageError.value = normalizeError(error, '手动重建长期摘要失败')
  } finally {
    rebuildingSummary.value = false
  }
}

function exchangeTarget(exchange) {
  return { name: 'AdminObservabilityExchangeDetail', params: { conversationId: conversationId.value, exchangeId: String(exchange.exchangeId) } }
}

function exchangeTokenCount(exchange) {
  const traces = exchange?.debugTrace?.modelUsageTraces || []
  const total = traces.reduce((sum, item) => sum + Number(item?.totalTokens || 0), 0)
  return total || '无'
}

function exchangeCost(exchange) {
  const traces = exchange?.debugTrace?.modelUsageTraces || []
  const total = traces.reduce((sum, item) => sum + Number(item?.estimatedCost || 0), 0)
  return total > 0 ? `¥ ${total.toFixed(4)}` : '无'
}

watch(conversationId, () => { activeSession.value = null; loadSession() }, { immediate: true })
onMounted(() => schedulePolling())
onUnmounted(() => clearTimeout(pollTimer))
</script>
