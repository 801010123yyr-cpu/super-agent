<template>
  <section class="grid min-h-screen grid-cols-[320px_minmax(0,1fr)] gap-[18px] p-4 md:p-6 max-[1120px]:grid-cols-1">
    <aside
      class="flex flex-col gap-4 rounded-lg border border-border bg-card p-[22px] shadow-sm max-[1120px]:fixed max-[1120px]:bottom-[18px] max-[1120px]:left-[18px] max-[1120px]:top-[18px] max-[1120px]:z-30 max-[1120px]:w-[min(360px,calc(100vw-36px))] max-[1120px]:transition-[transform] max-[1120px]:duration-[240ms] max-[768px]:p-4"
      :class="sidebarOpen ? 'max-[1120px]:translate-x-0' : 'max-[1120px]:-translate-x-[110%]'"
    >
      <div class="flex items-center justify-between">
        <h2 class="m-0 text-base font-semibold text-foreground">聊天记录</h2>
        <button class="inline-flex h-9 w-9 items-center justify-center rounded-md border border-border bg-card text-foreground hover:bg-secondary max-[1120px]:flex [1120px]:hidden" type="button" @click="sidebarOpen = false">
          <XMarkIcon class="h-[18px] w-[18px]" />
        </button>
      </div>

      <button class="inline-flex w-full items-center justify-center gap-1.5 rounded-lg bg-primary px-4 py-2.5 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50" type="button" :disabled="isStreaming" @click="startNewConversation">
        <PlusIcon class="h-[18px] w-[18px]" />
        新对话
      </button>

      <div class="flex min-h-0 flex-col gap-2 overflow-y-auto pr-1">
        <article v-for="session in sortedSessions" :key="session.conversationId"
          class="flex w-full gap-2.5 rounded-md border border-border bg-card p-3 text-left text-foreground transition-colors hover:border-primary/20"
          :class="session.conversationId === currentConversationId ? 'border-primary' : ''"
        >
          <button class="min-w-0 flex-1 bg-transparent p-0 text-left text-inherit disabled:cursor-not-allowed" type="button" :disabled="isStreaming" @click="loadConversation(session.conversationId)">
            <div class="flex items-center gap-2">
              <span class="text-sm font-semibold text-foreground">{{ sessionTitle(session) }}</span>
              <span v-if="session.running" class="rounded-full bg-primary/[0.08] px-2 py-0.5 text-xs text-primary">运行中</span>
            </div>
            <p class="mb-2 mt-1.5 text-[13px] leading-snug text-muted-foreground">{{ sessionPreview(session) }}</p>
            <div class="flex flex-wrap gap-2.5 text-xs text-muted-foreground">
              <span>{{ formatTime(session.updatedAt) }}</span>
              <span>{{ sessionMessageCount(session) }} 条消息</span>
            </div>
          </button>
          <button class="grid h-8 w-8 shrink-0 place-items-center rounded-md border border-destructive/[0.12] bg-destructive/[0.08] text-destructive disabled:cursor-not-allowed disabled:opacity-50" type="button" title="删除会话" :disabled="isStreaming" @click.stop="deleteConversation(session.conversationId)">
            <TrashIcon class="h-[18px] w-[18px]" />
          </button>
        </article>

        <div v-if="!loadingSessions && !sortedSessions.length" class="rounded-md border border-dashed border-border bg-secondary p-[18px] text-muted-foreground">
          <p class="m-0 mb-1.5 font-semibold text-foreground">还没有历史会话。</p>
          <span class="text-sm">发送第一条消息后，这里会自动出现会话记录。</span>
        </div>
      </div>
    </aside>

    <div v-if="sidebarOpen" class="fixed inset-0 z-20 hidden bg-[rgba(9,21,34,0.36)] max-[1120px]:block" @click="sidebarOpen = false"></div>

    <main class="flex min-h-[760px] min-w-0 flex-col gap-4 rounded-lg border border-border bg-card p-[22px] shadow-sm max-[768px]:p-4">
      <header class="flex items-center justify-between gap-3 border-b border-border pb-3.5 max-[768px]:flex-col max-[768px]:items-start">
        <div class="flex items-center gap-3">
          <button class="inline-flex h-9 w-9 items-center justify-center rounded-md border border-border bg-card text-foreground hover:bg-secondary max-[1120px]:flex [1120px]:hidden" type="button" @click="sidebarOpen = true">
            <Bars3Icon class="h-[18px] w-[18px]" />
          </button>
          <h2 class="m-0 text-lg font-semibold text-foreground">{{ activeSessionTitle }}</h2>
        </div>
        <div class="flex items-center gap-3 max-[768px]:w-full">
          <a class="inline-flex items-center gap-1.5 rounded-lg bg-foreground px-4 py-2.5 text-sm font-semibold text-white transition-opacity hover:opacity-90 max-[768px]:flex-1 max-[768px]:justify-center" :href="adminConsoleHref" target="_blank" rel="noopener noreferrer">
            <BuildingOffice2Icon class="h-[18px] w-[18px]" />
            管理后台
          </a>
        </div>
      </header>

      <div ref="messagesPanelRef" class="min-h-0 flex-1 overflow-y-auto rounded-md border border-border bg-secondary p-[18px]">
        <div v-if="pageError" class="mb-4 rounded-md border border-destructive/[0.14] bg-destructive/[0.08] p-3 text-sm text-destructive">{{ pageError }}</div>
        <div v-if="loadingConversation" class="mb-4 rounded-md border border-primary/10 bg-primary/[0.06] p-3 text-sm text-primary">正在加载会话内容...</div>

        <div v-if="!displayMessages.length && !loadingConversation" class="grid min-h-full place-items-center rounded-md border border-dashed border-border bg-secondary px-6 py-14 text-center">
          <div>
            <div class="mx-auto mb-2 grid h-14 w-14 place-items-center rounded-md bg-primary/[0.08]">
              <SparklesIcon class="h-7 w-7 text-primary" />
            </div>
            <h3 class="mx-auto mb-2 mt-4 max-w-[720px] text-xl font-semibold leading-snug text-foreground">让零散问题更快落成可执行方案</h3>
            <p class="mx-auto m-0 max-w-[620px] leading-[1.7] text-muted-foreground">结合业务问答、文档理解与知识检索，把想法整理成清晰结论和下一步动作</p>
            <div class="mt-5 flex flex-wrap justify-center gap-2.5">
              <button v-for="p in promptChips" :key="p.text" class="rounded-full border border-border bg-card px-3.5 py-2 text-[13px] font-medium text-foreground transition-colors hover:border-primary/20 hover:bg-secondary" type="button" @click="sendMessage(p.text)">{{ p.label }}</button>
            </div>
          </div>
        </div>

        <Chat v-for="message in displayMessages" :key="message.id" :message="message"
          :is-streaming="isStreaming && message.id === currentAssistantMessageId"
          :show-recommendations="message.id === latestAssistantDisplayId"
          @recommend="sendMessage"
        />
      </div>

      <footer class="rounded-md border border-border bg-card p-4">
        <div class="mb-2.5 flex items-center justify-between max-[768px]:flex-col max-[768px]:items-start max-[768px]:gap-2">
          <span class="text-[13px] text-muted-foreground">按 Enter 发送，Shift + Enter 换行。</span>
          <span v-if="isStreaming" class="text-[13px] font-semibold text-primary">正在生成回答...</span>
        </div>

        <div class="mb-3 flex flex-wrap items-center gap-2.5">
          <span class="text-[13px] text-muted-foreground">知识库</span>
          <div
            class="relative min-w-[280px] max-w-full"
            @focusout="handleKnowledgeBaseMenuFocusOut"
            @keydown.esc.stop.prevent="knowledgeBaseDropdownOpen = false"
          >
            <button
              class="inline-flex min-h-[42px] w-full items-center justify-between gap-3 rounded-md border border-border bg-card px-3 py-2 text-left text-sm text-foreground shadow-sm transition-colors hover:border-primary/30 disabled:cursor-not-allowed disabled:bg-secondary disabled:text-muted-foreground"
              :class="selectedKnowledgeBaseIds.length ? 'border-primary/25 text-primary' : ''"
              type="button"
              :aria-expanded="knowledgeBaseDropdownOpen"
              aria-haspopup="listbox"
              :disabled="isStreaming || loadingKnowledgeBaseOptions || !knowledgeBaseOptions.length"
              @click="toggleKnowledgeBaseDropdown"
            >
              <span class="truncate">{{ knowledgeBaseTriggerText }}</span>
              <ChevronDownIcon class="h-4 w-4 shrink-0 text-muted-foreground transition-transform" :class="knowledgeBaseDropdownOpen ? 'rotate-180' : ''" />
            </button>

            <div
              v-if="knowledgeBaseDropdownOpen"
              class="absolute left-0 top-[calc(100%+8px)] z-20 max-h-[260px] w-full min-w-[300px] overflow-y-auto rounded-md border border-border bg-card p-1.5 shadow-lg"
              role="listbox"
              aria-multiselectable="true"
            >
              <button
                v-for="item in knowledgeBaseOptions"
                :key="item.id"
                class="flex w-full items-center gap-2 rounded-sm px-3 py-2.5 text-left text-sm transition-colors hover:bg-secondary"
                :class="isKnowledgeBaseSelected(item.id) ? 'bg-primary/[0.08] text-primary' : 'text-foreground'"
                type="button"
                role="option"
                :aria-selected="isKnowledgeBaseSelected(item.id)"
                :disabled="isStreaming"
                @mousedown.prevent
                @click="toggleKnowledgeBaseSelection(item.id)"
              >
                <span class="min-w-0 flex-1 truncate font-medium">{{ item.baseName }}</span>
                <span class="shrink-0 text-xs" :class="isKnowledgeBaseSelected(item.id) ? 'text-primary' : 'text-muted-foreground'">{{ item.retrievableDocumentCount || 0 }} 文档</span>
                <CheckIcon v-if="isKnowledgeBaseSelected(item.id)" class="h-4 w-4 shrink-0" />
              </button>
            </div>
          </div>
          <span v-if="!loadingKnowledgeBaseOptions && !knowledgeBaseOptions.length" class="text-[13px] text-muted-foreground">
            暂无可用知识库，可在管理端创建
          </span>
        </div>

        <div class="mb-2.5 flex flex-wrap items-center gap-2.5">
          <span class="text-[13px] text-muted-foreground">回答模式</span>
          <div class="inline-flex items-center gap-1.5 rounded-full bg-secondary p-1" role="tablist">
            <button v-for="m in chatModeButtons" :key="m.value"
              class="rounded-full px-3.5 py-2 text-[13px] font-semibold transition-all disabled:cursor-not-allowed disabled:opacity-60"
              :class="chatMode === m.value ? 'bg-card text-primary shadow-sm' : 'bg-transparent text-muted-foreground'"
              type="button" :disabled="isStreaming" @click="setChatMode(m.value)">{{ m.label }}</button>
          </div>
        </div>

        <div v-if="isDocumentMode" class="mb-3 flex flex-wrap items-center gap-2.5">
          <span class="text-[13px] text-muted-foreground">提问文档</span>
          <select v-model="selectedDocumentId" class="min-w-[240px] max-w-full rounded-[12px] border border-border bg-card px-3 py-2 text-sm text-foreground disabled:bg-secondary" :disabled="isStreaming || loadingDocumentOptions" @change="handleDocumentScopeChange">
            <option value="">请选择一个文档</option>
            <option v-for="item in filteredDocumentOptions" :key="item.documentId" :value="item.documentId">{{ item.documentName }}</option>
          </select>
          <span v-if="selectedDocumentName" class="inline-flex items-center rounded-full bg-primary/[0.08] px-3 py-1.5 text-[13px] font-medium text-primary">当前文档：{{ selectedDocumentName }}</span>
          <span v-else-if="knowledgeBaseSelectionMode === KB_SELECTION_MODES.NONE" class="inline-flex items-center rounded-full bg-amber-500/[0.14] px-3 py-1.5 text-[13px] font-medium text-amber-700">当前文档模式需要先选择知识库</span>
          <span v-else-if="!loadingDocumentOptions" class="inline-flex items-center rounded-full bg-amber-500/[0.14] px-3 py-1.5 text-[13px] font-medium text-amber-700">请先选择一个文档再发送问题</span>
        </div>

        <textarea ref="composerRef" v-model="userInput"
          class="min-h-[56px] max-h-[220px] w-full resize-none rounded-md border border-border bg-card p-[10px_12px] text-foreground outline-none transition-shadow focus:border-primary focus:ring-[3px] focus:ring-primary/[0.08] disabled:bg-secondary"
          rows="1" :placeholder="composerPlaceholder" :disabled="isStreaming"
          @input="resizeComposer" @keydown="handleComposerKeydown"
        ></textarea>

        <div class="mt-3 flex items-center justify-end gap-2.5 max-[768px]:flex-col max-[768px]:items-stretch">
          <button v-if="isStreaming"
            class="inline-flex items-center gap-1.5 rounded-lg border border-border bg-card px-4 py-2.5 text-sm font-semibold text-foreground transition-colors hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
            type="button" :disabled="isStopping" @click="stopStreaming">
            <StopIcon class="h-[18px] w-[18px]" />
            {{ isStopping ? '停止中...' : '停止生成' }}
          </button>
          <button
            class="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2.5 text-sm font-semibold text-white transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50 max-[768px]:justify-center"
            type="button" :disabled="isStreaming || !canSend" @click="sendMessage()">
            <PaperAirplaneIcon class="h-[18px] w-[18px]" />
            发送
          </button>
        </div>
      </footer>
    </main>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bars3Icon, BuildingOffice2Icon, CheckIcon, ChevronDownIcon, PaperAirplaneIcon, PlusIcon, SparklesIcon, StopIcon, TrashIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import Chat from '../components/Chat.vue'
import { APIError, chatApi, createConversationId, manageApi } from '../api/api'
import { buildChatRouteExplain, buildRouteTraceLookup } from '../utils/knowledgeRoute'

const router = useRouter()
const adminConsoleHref = router.resolve({ name: 'AdminLogin', query: { redirect: '/admin/dashboard' } }).href
const composerRef = ref(null)
const messagesPanelRef = ref(null)
const sidebarOpen = ref(false)
const sessions = ref([])
const currentConversationId = ref('')
const displayMessages = ref([])
const userInput = ref('')
const loadingSessions = ref(false)
const loadingConversation = ref(false)
const loadingDocumentOptions = ref(false)
const loadingKnowledgeBaseOptions = ref(false)
const isStreaming = ref(false)
const isStopping = ref(false)
const pageError = ref('')
const currentStreamHandle = ref(null)
const currentAssistantMessageId = ref('')
const documentOptions = ref([])
const knowledgeBaseOptions = ref([])
const knowledgeBaseDropdownOpen = ref(false)
const selectedDocumentId = ref('')
const selectedDocumentName = ref('')
const CHAT_MODES = Object.freeze({ DOCUMENT: 'DOCUMENT', AUTO_DOCUMENT: 'AUTO_DOCUMENT', OPEN_CHAT: 'OPEN_CHAT' })
const KB_SELECTION_MODES = Object.freeze({ NONE: 'NONE', SELECTED: 'SELECTED' })
const chatMode = ref(CHAT_MODES.OPEN_CHAT)
const knowledgeBaseSelectionMode = ref(KB_SELECTION_MODES.NONE)
const selectedKnowledgeBaseIds = ref([])

const isDocumentMode = computed(() => chatMode.value === CHAT_MODES.DOCUMENT)
const isAutoDocumentMode = computed(() => chatMode.value === CHAT_MODES.AUTO_DOCUMENT)
const selectedKnowledgeBaseSet = computed(() => new Set(selectedKnowledgeBaseIds.value.map((item) => String(item))))
const hasSelectedKnowledgeBases = computed(() => selectedKnowledgeBaseIds.value.length > 0)
const filteredDocumentOptions = computed(() => {
  if (knowledgeBaseSelectionMode.value === KB_SELECTION_MODES.NONE) return []
  return documentOptions.value.filter((item) => selectedKnowledgeBaseSet.value.has(String(item.knowledgeBaseId || '')))
})
const knowledgeBaseTriggerText = computed(() => {
  if (loadingKnowledgeBaseOptions.value) return '知识库加载中...'
  if (!knowledgeBaseOptions.value.length) return '暂无可用知识库'
  if (!selectedKnowledgeBaseIds.value.length) return '选择知识库'
  const names = knowledgeBaseOptions.value
    .filter((item) => selectedKnowledgeBaseSet.value.has(String(item.id)))
    .map((item) => item.baseName)
  if (names.length === 1) return names[0]
  return `已选 ${names.length} 个知识库`
})
const canSend = computed(() => {
  if (!userInput.value.trim()) return false
  if (!isDocumentMode.value) return true
  return hasSelectedKnowledgeBases.value && Boolean(selectedDocumentId.value)
})
const composerPlaceholder = computed(() => {
  if (isAutoDocumentMode.value && hasSelectedKnowledgeBases.value) return '请输入你的问题，系统会自动选择最相关的知识文档，例如：上线观察与值班规则中观察时长有哪些？'
  return isDocumentMode.value
    ? '请输入关于当前文档的问题，例如：这份培训手册里的试用期规则是怎么规定的？'
    : '请输入你的问题，例如：帮我分析一下这个智能对话方案应该怎么拆分模块。'
})
const sortedSessions = computed(() => {
  return [...sessions.value].sort((l, r) => {
    const lt = l.updatedAt ? new Date(l.updatedAt).getTime() : 0
    const rt = r.updatedAt ? new Date(r.updatedAt).getTime() : 0
    return rt - lt
  })
})
const activeSessionTitle = computed(() => {
  const session = sessions.value.find((item) => item.conversationId === currentConversationId.value)
  return session ? sessionTitle(session) : '新的对话'
})
const latestAssistantDisplayId = computed(() => {
  const message = [...displayMessages.value].reverse().find((item) => item.role === 'assistant')
  return message?.id || ''
})
const latestAssistantRouteExplain = computed(() => {
  const message = [...displayMessages.value].reverse().find((item) => item.role === 'assistant' && item.routeExplain)
  return message?.routeExplain || null
})
const chatModeButtons = computed(() => [
  { value: CHAT_MODES.DOCUMENT, label: '当前文档问答' },
  { value: CHAT_MODES.AUTO_DOCUMENT, label: '自动知识问答' },
  { value: CHAT_MODES.OPEN_CHAT, label: '开放式提问' }
])
const promptChips = [
  { label: '助手能做什么', text: '请先介绍一下你能帮我做哪些事情，并给出几个典型使用场景' },
  { label: '拆解复杂问题', text: '请帮我把一个复杂问题拆成清晰的分析步骤，并给出执行建议' },
  { label: '梳理项目能力', text: '结合当前项目，帮我梳理对话能力、知识库能力和后台能力之间的关系' }
]

function sessionTitle(session) {
  const latestUserMessage = session.latestUserMessage || latestExchangeQuestion(session)
  const latestAssistantMessage = session.latestAssistantMessage || latestExchangeAnswer(session)
  return truncate(latestUserMessage || latestAssistantMessage || '新的对话', 22)
}
function sessionPreview(session) {
  const latestAssistantMessage = session.latestAssistantMessage || latestExchangeAnswer(session)
  const latestUserMessage = session.latestUserMessage || latestExchangeQuestion(session)
  return truncate(latestAssistantMessage || latestUserMessage || '还没有消息内容', 48)
}
function sessionMessageCount(session) {
  if (session?.messageCount) return session.messageCount
  return mapExchangesToMessages(session?.exchanges || []).length
}
function latestExchangeQuestion(session) {
  const exchanges = session?.exchanges || []
  for (let i = exchanges.length - 1; i >= 0; i -= 1) { if (exchanges[i]?.question) return exchanges[i].question }
  return ''
}
function latestExchangeAnswer(session) {
  const exchanges = session?.exchanges || []
  for (let i = exchanges.length - 1; i >= 0; i -= 1) { if (exchanges[i]?.answer) return exchanges[i].answer }
  return ''
}
function truncate(value, maxLength) {
  if (!value) return ''
  return value.length > maxLength ? `${value.slice(0, maxLength)}...` : value
}
function formatTime(value) {
  if (!value) return '刚刚'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '刚刚'
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}
function createUserMessage(question) {
  return { id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, role: 'user', content: question, createdAt: new Date().toISOString() }
}
function createAssistantMessage() {
  return {
    id: `assistant-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    role: 'assistant', content: '', thinkingSteps: [], references: [], recommendations: [], usedTools: [],
    status: 'RUNNING', statusText: '', errorMessage: '', firstResponseTimeMs: null, totalResponseTimeMs: null,
    debugTrace: null, routeExplain: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString()
  }
}
function mapExchangesToMessages(exchanges = [], routeTraceLookup = {}) {
  return exchanges.flatMap((exchange) => {
    const createdAt = exchange.createdAt || exchange.createTime || null
    const updatedAt = exchange.updatedAt || exchange.editTime || createdAt
    return [
      { id: `exchange-${exchange.exchangeId}-user`, role: 'user', content: exchange.question || '', createdAt },
      {
        id: `exchange-${exchange.exchangeId}-assistant`, role: 'assistant', content: exchange.answer || '',
        thinkingSteps: exchange.thinkingSteps || [], references: exchange.references || [],
        recommendations: exchange.recommendations || [], usedTools: exchange.usedTools || [],
        status: exchange.status || '', statusText: '', errorMessage: exchange.errorMessage || '',
        firstResponseTimeMs: exchange.firstResponseTimeMs, totalResponseTimeMs: exchange.totalResponseTimeMs,
        debugTrace: exchange.debugTrace || null,
        routeExplain: buildChatRouteExplain(routeTraceLookup[String(exchange.exchangeId)]),
        createdAt, updatedAt
      }
    ]
  })
}
function upsertSession(session) {
  const index = sessions.value.findIndex((item) => item.conversationId === session.conversationId)
  if (index === -1) { sessions.value = [session, ...sessions.value]; return }
  const next = [...sessions.value]
  next.splice(index, 1, session)
  sessions.value = next
}
// SSE 流里拿到的是增量事件，页面需要把它们持续合并进"当前这条助手消息"。
function updateCurrentAssistant(mutator) {
  const index = displayMessages.value.findIndex((message) => message.id === currentAssistantMessageId.value)
  if (index === -1) return
  const nextMessage = { ...displayMessages.value[index] }
  mutator(nextMessage)
  const nextMessages = [...displayMessages.value]
  nextMessages.splice(index, 1, nextMessage)
  displayMessages.value = nextMessages
}
async function scrollToBottom() {
  await nextTick()
  if (messagesPanelRef.value) messagesPanelRef.value.scrollTop = messagesPanelRef.value.scrollHeight
}
function resizeComposer() {
  nextTick(() => {
    if (!composerRef.value) return
    composerRef.value.style.height = 'auto'
    composerRef.value.style.height = `${Math.min(composerRef.value.scrollHeight, 220)}px`
  })
}
function focusComposer() {
  nextTick(() => { composerRef.value?.focus(); resizeComposer() })
}
async function refreshSessions() {
  loadingSessions.value = true
  try {
    const data = await chatApi.listSessions()
    sessions.value = Array.isArray(data) ? data : []
  } catch (error) {
    pageError.value = normalizeError(error, '加载会话列表失败')
  } finally {
    loadingSessions.value = false
  }
}
async function refreshDocumentOptions() {
  loadingDocumentOptions.value = true
  try {
    const data = await chatApi.listKnowledgeDocumentOptions()
    documentOptions.value = Array.isArray(data) ? data : []
    syncSelectedDocumentName()
  } catch (error) {
    pageError.value = normalizeError(error, '加载可选知识文档失败')
  } finally {
    loadingDocumentOptions.value = false
  }
}
async function refreshKnowledgeBaseOptions() {
  loadingKnowledgeBaseOptions.value = true
  try {
    const data = await chatApi.listKnowledgeBaseOptions()
    knowledgeBaseOptions.value = Array.isArray(data) ? data : []
  } catch (error) {
    pageError.value = normalizeError(error, '加载知识库选项失败')
  } finally {
    loadingKnowledgeBaseOptions.value = false
  }
}
async function loadConversation(conversationId) {
  if (!conversationId || isStreaming.value) return
  loadingConversation.value = true
  pageError.value = ''
  try {
    const [sessionResult, routeTraceResult] = await Promise.allSettled([
      chatApi.getSession(conversationId),
      manageApi.queryKnowledgeRouteTracePage({ conversationId, pageNo: '1', pageSize: '200' })
    ])
    if (sessionResult.status !== 'fulfilled') throw sessionResult.reason
    if (routeTraceResult.status === 'rejected') console.warn('加载知识路由追踪失败', routeTraceResult.reason)
    const session = sessionResult.value
    const routeTraceLookup = routeTraceResult.status === 'fulfilled'
      ? buildRouteTraceLookup(routeTraceResult.value?.records || []) : {}
    currentConversationId.value = conversationId
    displayMessages.value = mapExchangesToMessages(session.exchanges || [], routeTraceLookup)
    upsertSession(session)
    applySessionScope(session)
    sidebarOpen.value = false
    await scrollToBottom()
  } catch (error) {
    pageError.value = normalizeError(error, '加载会话详情失败')
  } finally {
    loadingConversation.value = false
  }
}
async function deleteConversation(conversationId) {
  if (!conversationId || isStreaming.value) return
  try {
    await chatApi.deleteSession(conversationId)
    sessions.value = sessions.value.filter((item) => item.conversationId !== conversationId)
    if (currentConversationId.value === conversationId) {
      const nextSession = sortedSessions.value[0]
      if (nextSession) { await loadConversation(nextSession.conversationId) } else { startNewConversation() }
    }
  } catch (error) {
    pageError.value = normalizeError(error, '删除会话失败')
  }
}
function startNewConversation() {
  if (isStreaming.value) return
  currentConversationId.value = createConversationId()
  displayMessages.value = []
  userInput.value = ''
  pageError.value = ''
  sidebarOpen.value = false
  syncSelectedDocumentName()
  focusComposer()
}
function applySessionScope(session) {
  chatMode.value = session?.chatMode || CHAT_MODES.OPEN_CHAT
  selectedDocumentId.value = session?.selectedDocumentId || ''
  selectedDocumentName.value = session?.selectedDocumentName || ''
  selectedKnowledgeBaseIds.value = Array.isArray(session?.selectedKnowledgeBaseIds)
    ? session.selectedKnowledgeBaseIds.map((item) => String(item))
    : []
  knowledgeBaseSelectionMode.value = normalizeKnowledgeBaseSelectionMode(session?.knowledgeBaseSelectionMode)
  if (knowledgeBaseSelectionMode.value !== KB_SELECTION_MODES.SELECTED) selectedKnowledgeBaseIds.value = []
  syncKnowledgeBaseSelectionMode()
  syncSelectedDocumentName()
}
function syncSelectedDocumentName() {
  if (!selectedDocumentId.value) { selectedDocumentName.value = ''; return }
  const option = filteredDocumentOptions.value.find((item) => String(item.documentId) === String(selectedDocumentId.value))
  if (option) { selectedDocumentName.value = option.documentName; return }
  selectedDocumentId.value = ''
  selectedDocumentName.value = ''
}
function handleDocumentScopeChange() {
  syncSelectedDocumentName()
  if (isDocumentMode.value && displayMessages.value.length > 0 && !isStreaming.value) startNewConversation()
}
function normalizeKnowledgeBaseSelectionMode(value) {
  const normalized = String(value || '').trim().toUpperCase()
  return normalized === KB_SELECTION_MODES.SELECTED ? KB_SELECTION_MODES.SELECTED : KB_SELECTION_MODES.NONE
}
function syncKnowledgeBaseSelectionMode() {
  knowledgeBaseSelectionMode.value = selectedKnowledgeBaseIds.value.length ? KB_SELECTION_MODES.SELECTED : KB_SELECTION_MODES.NONE
}
function clearKnowledgeBaseSelection() {
  selectedKnowledgeBaseIds.value = []
  knowledgeBaseDropdownOpen.value = false
  syncKnowledgeBaseSelectionMode()
  selectedDocumentId.value = ''
  selectedDocumentName.value = ''
}
function toggleKnowledgeBaseDropdown() {
  if (isStreaming.value || loadingKnowledgeBaseOptions.value || !knowledgeBaseOptions.value.length) return
  knowledgeBaseDropdownOpen.value = !knowledgeBaseDropdownOpen.value
}
function handleKnowledgeBaseMenuFocusOut(event) {
  if (!event.currentTarget.contains(event.relatedTarget)) knowledgeBaseDropdownOpen.value = false
}
function isKnowledgeBaseSelected(id) {
  return selectedKnowledgeBaseSet.value.has(String(id))
}
function toggleKnowledgeBaseSelection(id) {
  if (isStreaming.value) return
  const nextId = String(id)
  selectedKnowledgeBaseIds.value = isKnowledgeBaseSelected(nextId)
    ? selectedKnowledgeBaseIds.value.filter((item) => String(item) !== nextId)
    : [...selectedKnowledgeBaseIds.value, nextId]
  handleSelectedKnowledgeBasesChange()
}
function handleSelectedKnowledgeBasesChange() {
  selectedKnowledgeBaseIds.value = [...new Set(selectedKnowledgeBaseIds.value.map((item) => String(item)).filter(Boolean))]
  syncKnowledgeBaseSelectionMode()
  if (knowledgeBaseSelectionMode.value === KB_SELECTION_MODES.NONE) {
    chatMode.value = CHAT_MODES.OPEN_CHAT
    selectedDocumentId.value = ''
    selectedDocumentName.value = ''
  } else if (chatMode.value === CHAT_MODES.OPEN_CHAT) {
    chatMode.value = CHAT_MODES.AUTO_DOCUMENT
  }
  syncSelectedDocumentName()
}
function setChatMode(nextMode) {
  if (isStreaming.value || chatMode.value === nextMode) return
  if (nextMode === CHAT_MODES.AUTO_DOCUMENT && !hasSelectedKnowledgeBases.value) {
    chatMode.value = CHAT_MODES.OPEN_CHAT
    pageError.value = ''
    return
  }
  chatMode.value = nextMode
  pageError.value = ''
  if (nextMode === CHAT_MODES.OPEN_CHAT) {
    clearKnowledgeBaseSelection()
    return
  }
}
function handleComposerKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); sendMessage() }
}
// 历史会话是完整快照，流式回答是增量事件，这里统一负责把增量事件映射到展示态。
function applyStreamEvent(event) {
  updateCurrentAssistant((message) => {
    if (event.type === 'text') message.content += event.content || ''
    if (event.type === 'thinking' && event.content && !message.thinkingSteps.includes(event.content))
      message.thinkingSteps = [...message.thinkingSteps, event.content]
    if (event.type === 'reference') message.references = Array.isArray(event.content) ? event.content : []
    if (event.type === 'recommend') message.recommendations = Array.isArray(event.content) ? event.content : []
    if (event.type === 'status') message.statusText = event.content || ''
    if (event.type === 'error') { message.errorMessage = event.content || '对话执行失败'; message.status = 'FAILED' }
    message.updatedAt = event.timestamp || new Date().toISOString()
  })
  scrollToBottom()
}
async function sendMessage(presetQuestion) {
  const question = (presetQuestion || userInput.value).trim()
  if (!question || isStreaming.value) return
  const payloadScope = buildKnowledgeBasePayload()
  const payloadChatMode = resolvePayloadChatMode(payloadScope.knowledgeBaseSelectionMode)
  if (payloadChatMode === CHAT_MODES.DOCUMENT && !selectedDocumentId.value) { pageError.value = '当前文档问答模式下请先选择一个文档'; return }
  const conversationId = currentConversationId.value || createConversationId()
  const assistantMessage = createAssistantMessage()
  currentConversationId.value = conversationId
  pageError.value = ''
  displayMessages.value = [...displayMessages.value, createUserMessage(question), assistantMessage]
  currentAssistantMessageId.value = assistantMessage.id
  isStreaming.value = true
  isStopping.value = false
  if (!presetQuestion) { userInput.value = ''; resizeComposer() }
  await scrollToBottom()
  const streamHandle = chatApi.openStream(
    {
      question,
      conversationId,
      chatMode: payloadChatMode,
      selectedDocumentId: payloadChatMode === CHAT_MODES.DOCUMENT ? selectedDocumentId.value || null : null,
      ...payloadScope
    },
    { onEvent: applyStreamEvent }
  )
  currentStreamHandle.value = streamHandle
  try {
    await streamHandle.done
  } catch (error) {
    if (error.name !== 'AbortError') {
      updateCurrentAssistant((message) => { message.errorMessage = normalizeError(error, '流式对话失败'); message.status = 'FAILED' })
      pageError.value = normalizeError(error, '流式对话失败')
    }
  } finally {
    currentStreamHandle.value = null
    currentAssistantMessageId.value = ''
    isStreaming.value = false
    isStopping.value = false
    try {
      await refreshSessions()
      const sessionExists = sessions.value.some((item) => item.conversationId === conversationId)
      if (sessionExists) await loadConversation(conversationId)
    } catch { /* 各自方法里已有页面提示 */ }
  }
}
function buildKnowledgeBasePayload() {
  if (knowledgeBaseSelectionMode.value === KB_SELECTION_MODES.SELECTED && selectedKnowledgeBaseIds.value.length) {
    return {
      knowledgeBaseSelectionMode: KB_SELECTION_MODES.SELECTED,
      selectedKnowledgeBaseIds: selectedKnowledgeBaseIds.value.map((item) => String(item))
    }
  }
  return { knowledgeBaseSelectionMode: KB_SELECTION_MODES.NONE, selectedKnowledgeBaseIds: [] }
}
function resolvePayloadChatMode(selectionMode) {
  if (selectionMode === KB_SELECTION_MODES.NONE) return CHAT_MODES.OPEN_CHAT
  return isDocumentMode.value ? CHAT_MODES.DOCUMENT : CHAT_MODES.AUTO_DOCUMENT
}
async function stopStreaming() {
  if (!isStreaming.value || !currentConversationId.value || !currentStreamHandle.value) return
  isStopping.value = true
  try {
    const result = await chatApi.stopSession(currentConversationId.value)
    updateCurrentAssistant((message) => { message.statusText = result?.message || '用户已停止生成' })
  } catch (error) {
    pageError.value = normalizeError(error, '停止会话失败')
    isStopping.value = false
    return
  }
  currentStreamHandle.value.controller.abort()
}
function normalizeError(error, fallback) {
  if (error instanceof APIError && error.message) return error.message
  if (error instanceof Error && error.message) return error.message
  return fallback
}
onMounted(async () => {
  await Promise.all([refreshKnowledgeBaseOptions(), refreshDocumentOptions(), refreshSessions()])
  if (sortedSessions.value.length > 0) { await loadConversation(sortedSessions.value[0].conversationId) } else { startNewConversation() }
})
</script>
