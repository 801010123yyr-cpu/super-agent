<template>
  <article class="mb-[18px] flex gap-3.5" :class="isUser ? 'flex-row-reverse' : ''">
    <div
      class="grid h-[42px] w-[42px] flex-none place-items-center rounded-xl border border-foreground/[0.08] text-[var(--color-primary-strong)]"
      :class="isUser ? 'bg-primary/10' : 'bg-primary/10'"
    >
      <UserIcon v-if="isUser" class="h-[18px] w-[18px]" />
      <SparklesIcon v-else class="h-[18px] w-[18px]" />
    </div>

    <div
      class="min-w-0 flex-1 rounded-2xl border border-foreground/[0.08] p-[18px]"
      :class="isUser
        ? 'max-w-[min(760px,100%)] bg-primary/[0.08]'
        : 'bg-white'"
    >
      <div class="mb-3 flex items-center justify-between gap-3">
        <div>
          <p class="m-0 font-bold text-foreground">{{ isUser ? '你' : '智能助手' }}</p>
          <p class="mt-1 text-xs text-muted-foreground">{{ formatTime(message.updatedAt || message.createdAt) }}</p>
        </div>
        <button
          class="grid h-9 w-9 flex-none place-items-center rounded-[12px] border border-foreground/[0.08] bg-white/[0.88] text-foreground"
          type="button" :title="copyButtonTitle" @click="copyContent"
        >
          <CheckIcon v-if="copied" class="h-[18px] w-[18px]" />
          <DocumentDuplicateIcon v-else class="h-[18px] w-[18px]" />
        </button>
      </div>

      <div v-if="isUser" class="whitespace-pre-wrap break-words leading-[1.8]">{{ message.content }}</div>
      <template v-else>
        <p v-if="showStatusNotice" class="mb-3 rounded-[14px] border border-primary/[0.14] bg-primary/[0.06] p-3 leading-[1.7] text-[var(--color-primary-strong)] whitespace-pre-wrap"></p>
        <p v-if="showErrorNotice" class="mb-3 rounded-[14px] border border-red-700/[0.14] bg-red-700/[0.06] p-3 leading-[1.7] text-red-700 whitespace-pre-wrap">{{ message.errorMessage }}</p>
        <div v-if="hasAssistantContent" ref="contentRef" class="markdown-body break-words leading-[1.8] text-foreground" v-html="renderedContent"></div>
        <p v-else-if="showEmptyAssistantHint" class="mb-3 rounded-[14px] border border-dashed border-foreground/[0.12] bg-slate-400/[0.08] p-3 leading-[1.7] text-muted-foreground">本次回答没有生成可展示的正文内容。</p>

        <section v-if="showRouteExplainCard" class="mt-4 rounded-[16px] border p-4" :class="routeCardClass(routeExplain.statusTone)">
          <div class="flex items-start justify-between gap-3 max-[768px]:flex-col">
            <div>
              <p class="m-0 text-xs text-muted-foreground">{{ routeExplain.modeLabel }}</p>
              <h4 class="mt-1.5 text-[15px] text-foreground">{{ routeExplain.confidenceBand.label }} · 置信度 {{ routeExplain.confidenceText }}</h4>
            </div>
            <span class="inline-flex items-center whitespace-nowrap rounded-full px-2.5 py-1.5 text-xs font-bold" :class="routeStatusBadgeClass(routeExplain.statusTone)">
              {{ routeExplain.statusLabel }}
            </span>
          </div>

          <p class="mt-3.5 leading-[1.75] text-foreground">{{ routeExplain.summary }}</p>

          <div v-if="routeExplain.notes?.length" class="mt-3.5 flex flex-wrap gap-2">
            <span v-for="(item, index) in routeExplain.notes" :key="`${message.id}-route-note-${index}`"
              class="inline-flex items-center rounded-full bg-foreground/[0.06] px-3 py-1.5 text-xs text-foreground">{{ item }}</span>
          </div>

          <div v-if="routeExplain.topDocuments?.length" class="mt-4 grid gap-2.5" style="grid-template-columns:repeat(auto-fit,minmax(180px,1fr))">
            <article v-for="(item, index) in routeExplain.topDocuments" :key="`${message.id}-route-doc-${item.documentId || index}`"
              class="grid gap-1.5 rounded-[14px] border border-foreground/[0.08] bg-white/[0.74] p-3"
              :class="index === 0 ? '!border-primary/[0.18]' : ''">
              <strong class="text-foreground">{{ item.documentName || item.documentId }}</strong>
              <span class="text-xs text-muted-foreground">匹配分 {{ item.scoreText }}</span>
              <small class="text-xs text-muted-foreground">{{ item.reason || '基于文档画像与元数据综合召回' }}</small>
            </article>
          </div>

          <details v-if="routeExplain.scopePreview?.length || routeExplain.topicPreview?.length" class="mt-4 border-t border-foreground/[0.08] pt-3">
            <summary class="cursor-pointer font-semibold text-[var(--color-primary-strong)]">查看范围与主题候选</summary>
            <div class="mt-3 grid grid-cols-2 gap-3">
              <div v-if="routeExplain.scopePreview?.length" class="grid gap-2">
                <div class="flex flex-wrap gap-2">
                  <span v-for="(item, index) in routeExplain.scopePreview" :key="`${message.id}-route-scope-${item.scopeId || index}`"
                    class="inline-flex items-center rounded-full border border-foreground/[0.08] bg-white/[0.72] px-3 py-1.5 text-xs text-foreground">{{ item.scopeName || `范围 ${item.scopeId || index + 1}` }} · {{ item.scoreText }}</span>
                </div>
              </div>
              <div v-if="routeExplain.topicPreview?.length" class="grid gap-2">
                <div class="flex flex-wrap gap-2">
                  <span v-for="(item, index) in routeExplain.topicPreview" :key="`${message.id}-route-topic-${item.topicId || index}`"
                    class="inline-flex items-center rounded-full border border-foreground/[0.08] bg-white/[0.72] px-3 py-1.5 text-xs text-foreground">{{ item.topicName || `主题 ${item.topicId || index + 1}` }} · {{ item.scoreText }}</span>
                </div>
              </div>
            </div>
          </details>
        </section>
      </template>

      <div v-if="isStreaming" class="stream-cursor mt-3"></div>

      <section v-if="showRecommendationBar" class="mt-4 border-t border-foreground/[0.08] pt-3.5">
        <p class="mb-2.5 text-[12px] font-medium text-muted-foreground">推荐追问</p>
        <div class="flex flex-wrap gap-2.5">
          <button v-for="(item, index) in message.recommendations" :key="`${message.id}-recommend-${index}`"
            class="rounded-full border border-primary/[0.12] bg-primary/[0.06] px-3.5 py-2.5 text-[13px] font-semibold text-[var(--color-primary-strong)] transition-all hover:-translate-y-px hover:border-primary/[0.18] hover:bg-primary/10"
            type="button" @click="$emit('recommend', item)">{{ item }}</button>
        </div>
      </section>
    </div>
  </article>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import sql from 'highlight.js/lib/languages/sql'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import { marked } from 'marked'
import { CheckIcon, DocumentDuplicateIcon, SparklesIcon, UserIcon } from '@heroicons/vue/24/outline'

const props = defineProps({
  message: { type: Object, required: true },
  isStreaming: { type: Boolean, default: false },
  showRecommendations: { type: Boolean, default: false }
})
defineEmits(['recommend'])

const contentRef = ref(null)
const copied = ref(false)

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('yaml', yaml)

marked.setOptions({ breaks: true, gfm: true })

const isUser = computed(() => props.message.role === 'user')
const copyButtonTitle = computed(() => (copied.value ? '已复制' : '复制内容'))
const hasAssistantContent = computed(() => !isUser.value && Boolean(props.message.content))
const showStatusNotice = computed(() => !isUser.value && Boolean(props.message.statusText))
const showErrorNotice = computed(() => !isUser.value && Boolean(props.message.errorMessage))
const showEmptyAssistantHint = computed(() => {
  return !isUser.value && !props.isStreaming && !props.message.content && (showStatusNotice.value || showErrorNotice.value)
})
const routeExplain = computed(() => (!isUser.value ? props.message.routeExplain || null : null))
const showRouteExplainCard = computed(() => Boolean(routeExplain.value))
const copyableText = computed(() => {
  if (props.message.content) return props.message.content
  return [props.message.statusText, props.message.errorMessage].filter(Boolean).join('\n')
})
const showRecommendationBar = computed(() => {
  return !isUser.value && props.showRecommendations && Array.isArray(props.message.recommendations) && props.message.recommendations.length > 0
})
const renderedContent = computed(() => {
  if (!props.message.content) return ''
  const rendered = marked.parse(props.message.content)
  return DOMPurify.sanitize(rendered, { ADD_ATTR: ['target', 'rel', 'class'] })
})

function routeCardClass(tone) {
  if (tone === 'success') return 'border-green-500/[0.18] bg-gradient-to-b from-green-50/90 to-emerald-50/90'
  if (tone === 'warning') return 'border-amber-500/[0.2] bg-gradient-to-b from-amber-50/90 to-amber-100/70'
  if (tone === 'danger') return 'border-red-500/[0.18] bg-gradient-to-b from-red-50/90 to-red-100/70'
  return 'border-foreground/[0.08] bg-gradient-to-b from-slate-50/90 to-slate-100/90'
}

function routeStatusBadgeClass(tone) {
  if (tone === 'success') return 'bg-green-500/[0.12] text-green-700'
  if (tone === 'warning') return 'bg-amber-500/[0.14] text-amber-700'
  if (tone === 'danger') return 'bg-red-500/[0.12] text-red-700'
  return 'bg-foreground/[0.06] text-foreground'
}

async function highlightCodeBlocks() {
  await nextTick()
  if (!contentRef.value || isUser.value) return
  contentRef.value.querySelectorAll('pre code').forEach((block) => hljs.highlightElement(block))
}

async function copyContent() {
  try {
    await navigator.clipboard.writeText(copyableText.value || '')
    copied.value = true
    setTimeout(() => { copied.value = false }, 1800)
  } catch (error) {
    console.error('复制消息失败', error)
  }
}

function formatTime(value) {
  if (!value) return '刚刚'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '刚刚'
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(date)
}

watch(() => props.message.content, () => { if (!isUser.value) highlightCodeBlocks() })
onMounted(() => { if (!isUser.value) highlightCodeBlocks() })
</script>

<style scoped>
/* 保留：v-html 渲染的 markdown 需要 :deep() 才能命中 */
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  margin-top: 1.2em;
  margin-bottom: 0.6em;
  color: var(--color-text-strong);
  letter-spacing: -0.02em;
}
.markdown-body :deep(p:first-child) { margin-top: 0; }
.markdown-body :deep(p:last-child) { margin-bottom: 0; }
.markdown-body :deep(a) {
  color: var(--color-primary-strong);
  text-decoration: underline;
  text-decoration-color: rgba(192, 80, 45, 0.22);
  text-underline-offset: 3px;
}
.markdown-body :deep(pre) {
  overflow-x: auto;
  margin: 16px 0;
  padding: 14px;
  border-radius: 14px;
  background: #0f1724;
}
.markdown-body :deep(code:not(pre code)) {
  padding: 2px 6px;
  border-radius: 8px;
  background: rgba(17, 24, 39, 0.08);
}

/* 流式光标 — tailwindcss-animate 的 animate-pulse 幅度不同，保留自定义 */
.stream-cursor {
  width: 10px;
  height: 20px;
  border-radius: 999px;
  background: var(--color-primary);
  animation: chat-pulse 1s infinite;
}

@keyframes chat-pulse {
  0%, 100% { opacity: 0.3; }
  50%       { opacity: 1; }
}
</style>
