<template>
  <section class="flex flex-col gap-6">
    <div class="flex items-center justify-between gap-3">
      <RouterLink :to="{ name: 'AdminObservabilitySession', params: { conversationId } }"
        class="inline-flex items-center gap-1.5 rounded-md border border-border bg-card px-3 py-2 text-sm font-semibold text-foreground hover:bg-secondary">
        <ArrowLeftIcon class="h-4 w-4" />返回会话轮次列表
      </RouterLink>
      <button class="inline-flex items-center gap-1.5 rounded-md border border-border bg-card px-3 py-2 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-55" type="button" :disabled="loadingPage" @click="loadPage()">
        <ArrowPathIcon class="h-4 w-4" />{{ loadingPage ? '刷新中...' : '刷新这一轮详情' }}
      </button>
    </div>

    <div v-if="pageError" class="rounded-md border border-destructive/10 bg-destructive/[0.06] px-3.5 py-3 text-sm text-destructive">{{ pageError }}</div>
    <div v-if="loadingPage && !activeExchangeDetail" class="rounded-md border border-dashed border-border px-6 py-10 text-center text-sm text-muted-foreground">正在加载轮次详情...</div>
    <div v-else-if="!activeExchange" class="rounded-md border border-dashed border-border px-6 py-10 text-center text-sm text-muted-foreground">没有找到这条轮次，请返回会话页重新选择。</div>

    <template v-else>
      <header class="border-b border-border pb-5">
        <h2 class="my-2 text-xl font-semibold leading-snug text-foreground">{{ activeExchange.question || '未记录问题' }}</h2>
        <p class="m-0 text-[13px] leading-relaxed text-[var(--color-muted-strong)]">{{ currentExchangeNarrative }}</p>
        <div class="mt-3.5 flex flex-wrap gap-1.5">
          <span class="inline-flex items-center rounded px-2.5 py-1 text-xs font-semibold" :class="statusBadgeClass(activeExchange.status)">{{ formatStatusLabel(activeExchange.status) }}</span>
          <span class="inline-flex items-center rounded bg-[#17304f]/[0.07] px-2.5 py-1 text-xs font-semibold text-[var(--color-muted-strong)]">{{ formatChatMode(activeSession?.chatMode) }}</span>
          <span v-if="activeExchange.debugTrace?.executionMode" class="inline-flex items-center rounded bg-foreground/[0.06] px-2.5 py-1 text-xs font-semibold text-[var(--color-muted-strong)]">{{ formatExecutionMode(activeExchange.debugTrace.executionMode) }}</span>
          <span class="inline-flex items-center rounded bg-foreground/[0.06] px-2.5 py-1 text-xs font-semibold text-[var(--color-muted-strong)]">会话 {{ conversationId }}</span>
          <span class="inline-flex items-center rounded bg-foreground/[0.06] px-2.5 py-1 text-xs font-semibold text-[var(--color-muted-strong)]">轮次 {{ exchangeId }}</span>
        </div>
        <dl class="mt-4 flex flex-wrap gap-x-6 gap-y-2">
          <div v-for="pair in headerMetaPairs" :key="pair.dt" class="flex gap-2 text-[13px]">
            <dt class="text-muted-foreground">{{ pair.dt }}</dt>
            <dd class="m-0 text-foreground">{{ pair.dd }}</dd>
          </div>
        </dl>
      </header>

      <section>
        <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">执行阶段时间线</h3>
        <p class="m-0 text-[13px] leading-relaxed text-[var(--color-muted-strong)]">先浏览整个执行顺序，再点击某个阶段进入子页面查看这个阶段的详细过程。</p>
        <div v-if="!stageTraces.length" class="mt-3 rounded-md border border-dashed border-border px-6 py-6 text-center text-sm text-muted-foreground">当前轮次还没有可展示的阶段轨迹。</div>
        <div v-else class="mt-4 flex flex-col">
          <article v-for="(trace, index) in stageTraces" :key="trace.stageId" class="flex gap-4"
            :class="String(trace.stageId) === selectedTraceStageId ? 'bg-primary/[0.03]' : ''">
            <div class="flex w-5 shrink-0 flex-col items-center pt-4">
              <span class="z-[1] h-2.5 w-2.5 shrink-0 rounded-full" :class="dotClass(trace.stageState)"></span>
              <span v-if="index < stageTraces.length - 1" class="w-0.5 flex-1 bg-border"></span>
            </div>
            <button class="flex min-w-0 flex-1 flex-col gap-1.5 border-b border-black/[0.04] pb-5 pt-3.5 text-left last:border-0" type="button" @click="openTraceDetail(trace.stageId)">
              <div class="flex items-center justify-between gap-3">
                <div class="flex flex-wrap items-center gap-1.5">
                  <strong class="text-[13px] text-foreground">{{ trace.stageName }}</strong>
                  <span v-if="trace.stageCode" class="inline-flex rounded bg-foreground/[0.06] px-2 py-0.5 font-mono text-[10px] font-semibold text-[var(--color-muted-strong)]">{{ trace.stageCode }}</span>
                  <span class="inline-flex rounded px-2 py-0.5 text-[11px] font-semibold" :class="statusBadgeClass(trace.stageState)">{{ formatStatusLabel(trace.stageState) }}</span>
                </div>
                <span class="whitespace-nowrap text-xs text-muted-foreground">{{ formatDateTime(trace.startTime) }}</span>
              </div>
              <p class="m-0 text-[13px] text-[var(--color-muted-strong)]">{{ trace.summaryText || '当前阶段已记录。' }}</p>
              <div class="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-foreground/[0.08]">
                <div class="h-full rounded-full bg-primary/60 transition-all" :style="{ width: traceBarWidth(trace) }"></div>
              </div>
              <div class="text-xs text-muted-foreground">耗时 {{ trace.durationMs ? `${trace.durationMs} ms` : '无' }}</div>
              <span class="text-[13px] font-semibold text-[var(--color-primary-strong)]">查看这个阶段 →</span>
            </button>
          </article>
        </div>
      </section>

      <section>
        <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">这轮回答的关键结果</h3>
        <p class="m-0 text-[13px] leading-relaxed text-[var(--color-muted-strong)]">这里是当前轮次的摘要信息，帮助你快速判断这轮是否正常，再决定要点开哪个阶段。</p>
        <div class="mt-4 flex flex-col gap-4">
          <article v-for="stage in exchangeStages" :key="stage.key" class="rounded-lg border border-border bg-card p-4">
            <div class="flex items-start justify-between gap-3">
              <div>
                <span class="text-[11px] text-muted-foreground">{{ stage.eyebrow || stage.key }}</span>
                <h4 class="m-0 mt-1 text-sm font-semibold text-foreground">{{ stage.title }}</h4>
                <p class="mt-0.5 text-[13px] text-muted-foreground">{{ stage.subtitle }}</p>
              </div>
              <div v-if="stage.chips?.length" class="flex flex-wrap justify-end gap-1.5">
                <span v-for="item in stage.chips" :key="`${stage.key}-${item.label}-${item.value}`"
                  class="inline-flex rounded px-2 py-0.5 text-[11px] font-semibold" :class="summaryChipClass(item.tone)">{{ item.label }}：{{ item.value }}</span>
              </div>
            </div>
            <div v-if="stage.metrics?.length" class="mt-3 flex flex-wrap gap-3 text-[13px] text-muted-foreground">
              <span v-for="item in stage.metrics" :key="`${stage.key}-${item.label}`">{{ item.label }}：{{ item.value }}</span>
            </div>
            <dl v-if="stage.textBlocks?.length" class="mt-3 grid grid-cols-2 gap-3 max-[768px]:grid-cols-1">
              <div v-for="item in stage.textBlocks.slice(0, 2)" :key="`${stage.key}-${item.label}`" class="grid gap-1">
                <dt class="text-xs text-muted-foreground">{{ item.label }}</dt>
                <dd class="m-0 break-words text-[13px] text-foreground">{{ item.code ? truncate(item.value, 90) : item.value }}</dd>
              </div>
            </dl>
            <div v-if="stage.listBlocks?.length" class="mt-3">
              <span class="text-xs text-muted-foreground">{{ stage.listBlocks[0].label }}</span>
              <p class="mt-1 text-[13px] text-foreground">{{ stage.listBlocks[0].items.slice(0, 2).join('；') || '无' }}</p>
            </div>
            <button v-if="canOpenStage(stage)" class="mt-3 text-[13px] font-semibold text-[var(--color-primary-strong)] hover:underline" type="button" @click="openSummaryStage(stage)">查看这个阶段的执行过程 →</button>
          </article>
        </div>
      </section>

      <section v-if="tableEvidenceItems.length > 0">
        <div class="flex items-start justify-between gap-3 max-[768px]:flex-col">
          <div>
            <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">最终表格证据定位</h3>
            <p class="m-0 text-[13px] leading-relaxed text-[var(--color-muted-strong)]">表格问答最终引用到的表、行、列、单元格和 bbox 都在这里，方便核对结构化查询有没有真正落到原表位置。</p>
          </div>
          <div class="flex flex-wrap gap-1.5">
            <span class="inline-flex rounded bg-primary/[0.08] px-2.5 py-1 text-xs font-semibold text-primary">{{ tableEvidenceItems.length }} 条表格证据</span>
            <span v-if="tableEvidenceBboxCount" class="inline-flex rounded bg-[var(--color-success)]/10 px-2.5 py-1 text-xs font-semibold text-[var(--color-success)]">{{ tableEvidenceBboxCount }} 个 cell bbox</span>
          </div>
        </div>
        <div class="mt-4 grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(280px,1fr))">
          <article v-for="item in tableEvidenceItems" :key="item.key" class="rounded-lg border border-border bg-card p-4">
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <div class="flex flex-wrap items-center gap-1.5">
                  <span class="inline-flex rounded bg-foreground/[0.06] px-2 py-0.5 text-[11px] font-semibold text-[var(--color-muted-strong)]">引用 {{ item.referenceId }}</span>
                  <span class="inline-flex rounded bg-primary/[0.08] px-2 py-0.5 text-[11px] font-semibold text-primary">{{ item.channel }}</span>
                </div>
                <h4 class="m-0 mt-2 text-sm font-semibold text-foreground">{{ item.tableTitle }}</h4>
                <p class="m-0 mt-0.5 truncate text-xs text-muted-foreground">{{ item.documentName }}<span v-if="item.sectionPath"> / {{ item.sectionPath }}</span></p>
              </div>
              <span v-if="item.citationRepaired" class="shrink-0 rounded bg-[var(--color-success)]/10 px-2 py-0.5 text-[11px] font-semibold text-[var(--color-success)]">已语义修复</span>
            </div>
            <dl class="mt-3 grid grid-cols-2 gap-3 text-[13px] max-[540px]:grid-cols-1">
              <div v-for="pair in tableEvidencePairs(item)" :key="`${item.key}-${pair.label}`" class="grid gap-1">
                <dt class="text-xs text-muted-foreground">{{ pair.label }}</dt>
                <dd class="m-0 break-words text-foreground">{{ pair.value }}</dd>
              </div>
            </dl>
            <p v-if="item.snippet" class="mt-3 rounded-md bg-secondary px-3 py-2 text-xs leading-relaxed text-[var(--color-muted-strong)]">{{ truncate(item.snippet, 160) }}</p>
            <details v-if="item.tableBboxJson || item.cellBboxJsons.length" class="mt-3 border-t border-border pt-2">
              <summary class="cursor-pointer text-[13px] font-semibold text-primary">查看 bbox 原文</summary>
              <pre v-if="item.tableBboxJson" class="mt-2 overflow-auto rounded-md bg-[#0f172a] p-3 text-xs text-[#e2e8f0] whitespace-pre-wrap">{{ item.tableBboxJson }}</pre>
              <pre v-if="item.cellBboxJsons.length" class="mt-2 max-h-44 overflow-auto rounded-md bg-[#0f172a] p-3 text-xs text-[#e2e8f0] whitespace-pre-wrap">{{ item.cellBboxJsons.join('\n') }}</pre>
            </details>
            <RouterLink v-if="item.documentId" :to="tableEvidenceDocumentRoute(item)"
              class="mt-3 inline-flex rounded-md border border-primary/20 bg-primary/[0.06] px-3 py-2 text-[13px] font-semibold text-primary transition-colors hover:bg-primary/[0.10]">
              在文档表格中高亮
            </RouterLink>
          </article>
        </div>
      </section>

      <section v-if="channelExecutionDisplay.length > 0">
        <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">通道执行对比</h3>
        <p class="m-0 text-[13px] text-[var(--color-muted-strong)]">对比各检索通道的性能和效果。</p>
        <div class="mt-4 grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(260px,1fr))">
          <article v-for="exec in channelExecutionDisplay" :key="exec.id" class="rounded-lg border border-border bg-card p-4">
            <div class="mb-2 flex items-center justify-between gap-2">
              <strong class="text-sm text-foreground">{{ formatChannelType(exec.channelType) }}</strong>
              <span class="inline-flex rounded px-2 py-0.5 text-[11px] font-semibold" :class="statusBadgeClass(exec.executionState === 1 ? 'COMPLETED' : 'FAILED')">{{ formatExecutionState(exec.executionState) }}</span>
            </div>
            <p v-if="exec.subQuestion" class="mb-2 text-xs text-muted-foreground">子问题 {{ exec.subQuestionIndex }}：{{ truncate(exec.subQuestion, 60) }}</p>
            <div class="grid grid-cols-3 gap-2">
              <div v-for="m in channelMetrics(exec)" :key="m.label" class="grid gap-1">
                <span class="text-[11px] text-muted-foreground">{{ m.label }}</span>
                <span class="text-sm font-semibold" :class="m.highlight ? 'text-primary' : 'text-foreground'">{{ m.value }}</span>
              </div>
            </div>
            <div v-if="exec.errorMessage" class="mt-2 rounded-md bg-destructive/[0.06] px-2.5 py-2 text-xs text-destructive">{{ exec.errorMessage }}</div>
          </article>
        </div>
      </section>

      <section v-if="fusionTraceView.hasData">
        <div class="flex items-start justify-between gap-3 max-[768px]:flex-col">
          <div>
            <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">融合过程</h3>
            <p class="m-0 text-[13px] text-[var(--color-muted-strong)]">查看 route intent、通道权重、各通道 TopN、weighted hybrid、rerank 和最终选择原因。</p>
          </div>
          <div class="flex flex-wrap justify-end gap-1.5">
            <span class="inline-flex rounded bg-foreground/[0.06] px-2.5 py-1 text-xs font-semibold text-[var(--color-muted-strong)]">子问题 {{ fusionTraceView.summary.subQuestionCount }}</span>
            <span class="inline-flex rounded bg-primary/[0.08] px-2.5 py-1 text-xs font-semibold text-primary">候选 {{ fusionTraceView.summary.candidateCount }}</span>
            <span class="inline-flex rounded bg-[var(--color-success)]/10 px-2.5 py-1 text-xs font-semibold text-[var(--color-success)]">命中证据候选 {{ fusionTraceView.summary.selectedCount }}</span>
            <span class="inline-flex rounded bg-amber-500/[0.14] px-2.5 py-1 text-xs font-semibold text-amber-700">过滤 {{ fusionTraceView.summary.filteredCount }}</span>
          </div>
        </div>
        <div class="mt-4 flex flex-col gap-5">
          <article v-for="subQ in fusionTraceView.groups" :key="`fusion-${subQ.index}`" class="rounded-lg border border-border bg-card p-4">
            <div class="flex flex-wrap items-start justify-between gap-3">
              <div class="min-w-0">
                <h4 class="m-0 text-sm font-semibold text-foreground">子问题 {{ subQ.index }}：{{ subQ.question }}</h4>
                <div class="mt-1 flex flex-wrap gap-2 text-xs text-muted-foreground">
                  <span>融合候选 {{ subQ.fusedCandidateCount ?? '-' }}</span>
                  <span>父块提升 {{ subQ.parentCandidateCount ?? '-' }}</span>
                  <span>重排候选 {{ subQ.rerankedCandidateCount ?? '-' }}</span>
                  <span>最终引用 {{ subQ.referenceCount ?? subQ.selectedCount }}</span>
                </div>
              </div>
              <div class="flex flex-wrap gap-1.5">
                <span class="inline-flex rounded bg-primary/[0.08] px-2 py-0.5 text-[11px] font-semibold text-primary">命中证据候选 {{ subQ.selectedCount }}</span>
                <span class="inline-flex rounded bg-foreground/[0.06] px-2 py-0.5 text-[11px] font-semibold text-[var(--color-muted-strong)]">候选 {{ subQ.resultRows.length }}</span>
              </div>
            </div>
            <div v-if="subQ.channelMetrics.length" class="mt-4 grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(220px,1fr))">
              <div v-for="metric in subQ.channelMetrics" :key="`${subQ.index}-${metric.key}`" class="rounded-md border border-border bg-secondary/50 p-3">
                <div class="mb-2 flex items-center justify-between gap-2">
                  <strong class="text-[13px] text-foreground">{{ metric.channelLabel }}</strong>
                  <span class="rounded bg-card px-2 py-0.5 font-mono text-[11px] font-semibold text-[var(--color-muted-strong)]">{{ metric.channelWeightText }}</span>
                </div>
                <div class="h-1.5 overflow-hidden rounded-full bg-foreground/[0.08]">
                  <div class="h-full rounded-full bg-primary/65" :style="{ width: metric.weightWidth }"></div>
                </div>
                <div class="mt-2 grid grid-cols-3 gap-2 text-[12px]">
                  <div class="grid gap-0.5"><span class="text-muted-foreground">召回</span><strong class="text-foreground">{{ metric.recalledCount }}</strong></div>
                  <div class="grid gap-0.5"><span class="text-muted-foreground">闸门后</span><strong class="text-foreground">{{ metric.acceptedCount }}</strong></div>
                  <div class="grid gap-0.5"><span class="text-muted-foreground">命中证据</span><strong class="text-primary">{{ metric.finalSelectedCount }}</strong></div>
                </div>
                <div v-if="metric.retrievalIntent" class="mt-2 text-[11px] text-muted-foreground">intent {{ metric.retrievalIntent }}</div>
              </div>
            </div>
            <div v-if="subQ.resultRows.length" class="mt-4 overflow-x-auto rounded-md border border-border">
              <table class="w-full min-w-[980px] border-collapse text-sm">
                <thead><tr class="bg-secondary"><th v-for="h in ['状态','通道/排名','文档块','分数拆解','最终排名/原因']" :key="h" class="border-b border-border px-3 py-2.5 text-left text-xs font-semibold text-muted-foreground">{{ h }}</th></tr></thead>
                <tbody>
                  <tr v-for="row in subQ.resultRows" :key="row.id" class="border-b border-border last:border-0 transition-colors" :class="row.isSelected ? 'bg-primary/[0.04]' : ''">
                    <td class="px-3 py-3">
                      <span class="inline-flex rounded px-2 py-0.5 text-xs font-semibold" :class="fusionStatusClass(row.status.tone)">{{ row.status.label }}</span>
                    </td>
                    <td class="px-3 py-3 text-[13px] text-muted-foreground">
                      <div class="font-semibold text-foreground">{{ row.channelLabel }}</div>
                      <div>通道 #{{ row.channelRank || '-' }}<span v-if="row.rrfRank"> / RRF #{{ row.rrfRank }}</span></div>
                    </td>
                    <td class="px-3 py-3">
                      <div class="text-[13px] font-medium text-foreground">{{ row.documentName }}</div>
                      <div v-if="row.sectionPath" class="mt-0.5 text-xs text-muted-foreground">{{ row.sectionPath }}</div>
                      <div class="mt-0.5 text-xs text-muted-foreground">chunk {{ row.chunkNo || row.chunkId || '-' }} / parent {{ row.parentBlockNo || row.parentBlockId || '-' }}</div>
                      <p v-if="row.preview" class="mt-1 max-w-[340px] text-xs leading-relaxed text-[var(--color-muted-strong)]">{{ truncate(row.preview, 130) }}</p>
                    </td>
                    <td class="px-3 py-3">
                      <div class="grid min-w-[320px] grid-cols-7 gap-1.5">
                        <div v-for="score in row.scoreItems" :key="`${row.id}-${score.label}`" class="grid gap-1">
                          <span class="font-mono text-[10px] text-muted-foreground">{{ score.label }}</span>
                          <span class="font-mono text-[11px] text-foreground">{{ score.text }}</span>
                          <span class="h-1 overflow-hidden rounded-full bg-foreground/[0.08]"><span class="block h-full rounded-full bg-primary/60" :style="{ width: score.width }"></span></span>
                        </div>
                      </div>
                    </td>
                    <td class="px-3 py-3 text-[13px] text-muted-foreground">
                      <div class="font-semibold" :class="row.isSelected ? 'text-primary' : 'text-foreground'">{{ row.finalRank ? `最终 #${row.finalRank}` : '未进入最终排序' }}</div>
                      <div class="mt-0.5 max-w-[260px] leading-relaxed">{{ row.selectionReason }}</div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </article>
        </div>
      </section>

      <section v-if="groupedRetrievalResults.length > 0">
        <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">检索结果详情</h3>
        <p class="m-0 text-[13px] text-[var(--color-muted-strong)]">查看每个通道检索到的文档块、分数变化和最终选择情况。</p>
        <div v-for="subQ in groupedRetrievalResults" :key="subQ.index" class="mt-4">
          <h4 class="mb-3 text-sm font-semibold text-foreground">子问题 {{ subQ.index }}：{{ subQ.question }}</h4>
          <div v-for="channel in subQ.channels" :key="channel.type" class="mb-4">
            <div class="mb-2 flex items-center gap-2">
              <strong class="text-sm text-foreground">{{ formatChannelType(channel.type) }}</strong>
              <span class="rounded-full bg-secondary px-2 py-0.5 text-xs text-foreground">{{ channel.results.length }} 条</span>
            </div>
            <div class="overflow-x-auto rounded-md border border-border">
              <table class="w-full min-w-[640px] border-collapse text-sm">
                <thead><tr class="bg-secondary"><th v-for="h in ['排名变化','文档块','原始分','RRF 分','Rerank 分','状态']" :key="h" class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">{{ h }}</th></tr></thead>
                <tbody>
                  <tr v-for="result in channel.results" :key="result.id" class="border-b border-border last:border-0 transition-colors" :class="result.isSelected ? 'bg-primary/[0.04]' : ''">
                    <td class="p-4 text-sm text-muted-foreground">{{ result.channelRank || '-' }}<span v-if="result.finalRank" class="ml-1 text-primary">→ {{ result.finalRank }}</span></td>
                    <td class="p-4">
                      <div class="text-sm font-medium text-foreground">{{ result.documentName || '未知文档' }}</div>
                      <div v-if="result.sectionPath" class="mt-0.5 text-xs text-muted-foreground">{{ result.sectionPath }}</div>
                      <div v-if="result.chunkTextPreview" class="mt-1 text-xs text-muted-foreground">{{ truncate(result.chunkTextPreview, 120) }}</div>
                    </td>
                    <td class="p-4 text-sm text-muted-foreground">{{ formatScore(result.originalScore) }}</td>
                    <td class="p-4 text-sm text-muted-foreground">{{ formatScore(result.rrfScore) }}</td>
                    <td class="p-4 text-sm text-muted-foreground">{{ formatScore(result.rerankScore) }}</td>
                    <td class="p-4">
                      <span v-if="result.isSelected" class="inline-flex rounded-full bg-[var(--color-success)]/10 px-2 py-0.5 text-xs font-semibold text-[var(--color-success)]">已选入</span>
                      <span v-else-if="!result.gatePassed" class="inline-flex rounded-full bg-amber-500/[0.14] px-2 py-0.5 text-xs font-semibold text-amber-700">闸门过滤</span>
                      <span v-else class="inline-flex rounded-full bg-foreground/[0.06] px-2 py-0.5 text-xs font-semibold text-muted-foreground">未选入</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </section>

      <section v-if="citationRepairView.hasData">
        <div class="flex items-start justify-between gap-3 max-[768px]:flex-col">
          <div>
            <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">Citation repair</h3>
            <p class="m-0 text-[13px] text-[var(--color-muted-strong)]">查看答案句、候选证据、语义匹配 quote、修复结果和最终引用。</p>
          </div>
          <div class="flex flex-wrap justify-end gap-1.5">
            <span class="inline-flex rounded bg-foreground/[0.06] px-2.5 py-1 text-xs font-semibold text-[var(--color-muted-strong)]">候选 {{ citationRepairView.summary.documentReferenceCount }}</span>
            <span class="inline-flex rounded bg-primary/[0.08] px-2.5 py-1 text-xs font-semibold text-primary">匹配 {{ citationRepairView.summary.matchedCitationCount }}</span>
            <span class="inline-flex rounded bg-[var(--color-success)]/10 px-2.5 py-1 text-xs font-semibold text-[var(--color-success)]">最终 {{ citationRepairView.summary.repairedReferenceCount }}</span>
            <span class="inline-flex rounded bg-amber-500/[0.14] px-2.5 py-1 text-xs font-semibold text-amber-700">移除 {{ citationRepairView.summary.removedDocumentReferenceCount }}</span>
          </div>
        </div>
        <div class="mt-4 grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(180px,1fr))">
          <div v-for="item in citationSummaryItems" :key="item.label" class="rounded-md border border-border bg-card p-3">
            <span class="text-xs text-muted-foreground">{{ item.label }}</span>
            <strong class="mt-1 block text-sm text-foreground">{{ item.value }}</strong>
          </div>
        </div>
        <div v-if="citationRepairView.segments.length" class="mt-4 flex flex-col gap-3">
          <article v-for="segment in citationRepairView.segments" :key="segment.key" class="rounded-lg border border-border bg-card p-4">
            <div class="mb-3 flex flex-wrap items-start justify-between gap-2">
              <div class="min-w-0">
                <span class="text-xs text-muted-foreground">答案句 {{ segment.segmentIndex != null ? Number(segment.segmentIndex) + 1 : '' }}</span>
                <p class="m-0 mt-1 text-[13px] leading-relaxed text-foreground">{{ segment.answerSegment }}</p>
              </div>
              <span class="inline-flex rounded bg-primary/[0.08] px-2 py-0.5 text-[11px] font-semibold text-primary">最终 {{ segment.finals.length }} / 匹配 {{ segment.matched.length }}</span>
            </div>
            <div class="grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(260px,1fr))">
              <div v-for="item in segment.finals" :key="`final-${segment.key}-${item.key}`" class="rounded-md border border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04] p-3">
                <div class="flex items-center justify-between gap-2">
                  <strong class="text-[13px] text-foreground">引用 {{ item.referenceId || item.evidenceId || '-' }}</strong>
                  <span class="rounded bg-card px-2 py-0.5 font-mono text-[11px] font-semibold text-[var(--color-success)]">{{ item.scoreText }}</span>
                </div>
                <p v-if="item.quoteText" class="mt-2 text-xs leading-relaxed text-foreground">{{ truncate(item.quoteText, 180) }}</p>
                <div class="mt-2 text-xs text-muted-foreground">{{ item.documentName }}<span v-if="item.sectionPath"> / {{ item.sectionPath }}</span></div>
                <div class="mt-1 flex flex-wrap gap-1.5">
                  <span class="rounded bg-card px-2 py-0.5 text-[11px] font-semibold text-[var(--color-muted-strong)]">{{ item.channelLabel }}</span>
                  <span v-if="item.repairedAfter" class="rounded bg-[var(--color-success)]/10 px-2 py-0.5 text-[11px] font-semibold text-[var(--color-success)]">repair 后引用</span>
                </div>
                <RouterLink v-if="item.documentId" :to="citationDocumentRoute(item)"
                  class="mt-3 inline-flex rounded-md border border-primary/20 bg-primary/[0.06] px-3 py-1.5 text-[12px] font-semibold text-primary transition-colors hover:bg-primary/[0.10]">
                  查看文档证据
                </RouterLink>
              </div>
            </div>
          </article>
        </div>
        <details v-if="citationRepairView.removedCandidates.length" class="mt-4 rounded-lg border border-border bg-card p-4">
          <summary class="cursor-pointer text-sm font-semibold text-foreground">未进入最终引用的候选证据</summary>
          <div class="mt-3 overflow-x-auto rounded-md border border-border">
            <table class="w-full min-w-[760px] border-collapse text-sm">
              <thead><tr class="bg-secondary"><th v-for="h in ['Evidence','通道','文档','候选文本','原因']" :key="h" class="border-b border-border px-3 py-2.5 text-left text-xs font-semibold text-muted-foreground">{{ h }}</th></tr></thead>
              <tbody>
                <tr v-for="item in citationRepairView.removedCandidates" :key="`removed-${item.key}`" class="border-b border-border last:border-0">
                  <td class="px-3 py-2.5 font-mono text-xs text-foreground">{{ item.evidenceId || item.referenceId || '-' }}</td>
                  <td class="px-3 py-2.5 text-[13px] text-muted-foreground">{{ item.channelLabel }}</td>
                  <td class="px-3 py-2.5 text-[13px] text-foreground">{{ item.documentName }}</td>
                  <td class="px-3 py-2.5 text-xs text-muted-foreground">{{ truncate(item.candidateText, 130) }}</td>
                  <td class="px-3 py-2.5 text-[13px] text-muted-foreground">{{ item.filteredReason || '未进入最终引用' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </details>
      </section>

      <section v-if="evidenceBudgetSnapshot">
        <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">证据预算分析</h3>
        <p class="m-0 text-[13px] text-[var(--color-muted-strong)]">查看证据选择过程和预算使用情况。</p>
        <div class="mt-4 flex flex-wrap gap-4">
          <div v-for="b in budgetItems" :key="b.label" class="grid gap-1">
            <span class="text-xs text-muted-foreground">{{ b.label }}</span>
            <span class="text-sm font-semibold" :class="b.highlight ? 'text-primary' : 'text-foreground'">{{ b.value }}</span>
          </div>
        </div>
        <div v-if="evidenceBudgetSnapshot.renderedReferenceDetails?.length" class="mt-4">
          <h4 class="mb-2 text-sm font-semibold text-[var(--color-success)]">已纳入 Prompt 的证据</h4>
          <ul class="flex flex-col gap-1 pl-5 text-[13px] text-muted-foreground list-disc">
            <li v-for="(detail, idx) in evidenceBudgetSnapshot.renderedReferenceDetails" :key="`rendered-${idx}`">{{ detail }}</li>
          </ul>
        </div>
        <div v-if="evidenceBudgetSnapshot.omittedReferenceDetails?.length" class="mt-4">
          <h4 class="mb-2 text-sm font-semibold text-amber-700">因预算限制省略的证据</h4>
          <ul class="flex flex-col gap-1 pl-5 text-[13px] text-muted-foreground list-disc">
            <li v-for="(detail, idx) in evidenceBudgetSnapshot.omittedReferenceDetails" :key="`omitted-${idx}`">{{ detail }}</li>
          </ul>
        </div>
      </section>

      <section v-if="hasPromptData">
        <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">Prompt 预览</h3>
        <p class="m-0 text-[13px] text-[var(--color-muted-strong)]">查看最终喂给模型的完整 Prompt。</p>
        <div class="mt-4 flex gap-2">
          <button v-for="t in ['system','user']" :key="t" type="button"
            class="rounded-md px-3 py-1.5 text-sm font-semibold transition-colors"
            :class="activePromptTab === t ? 'bg-primary/[0.08] text-primary' : 'bg-secondary text-foreground hover:bg-primary/[0.04]'"
            @click="activePromptTab = t">{{ t === 'system' ? 'System Prompt' : 'User Prompt' }}</button>
        </div>
        <pre class="mt-3 overflow-auto rounded-lg bg-[#0f172a] p-4 text-xs text-[#e2e8f0] whitespace-pre-wrap">{{ activePromptTab === 'system' ? ragSystemPrompt || '无' : ragUserPrompt || '无' }}</pre>
      </section>

      <section v-if="stageTraces.length > 0 && stageBenchmarks.length > 0">
        <h3 class="mb-1 mt-1 text-base font-semibold text-foreground">阶段性能基准对比</h3>
        <p class="m-0 text-[13px] text-[var(--color-muted-strong)]">对比当前执行与历史基准（P50/P90/P99），识别异常慢的阶段。</p>
        <div class="mt-4 grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(220px,1fr))">
          <article v-for="trace in stageTraces.filter(t => t.durationMs)" :key="trace.stageId" class="rounded-lg border border-border bg-card p-4">
            <div class="mb-3 flex items-center justify-between gap-2">
              <strong class="text-sm text-foreground">{{ trace.stageName }}</strong>
              <span v-if="findBenchmark(trace.stageCode, trace.executionMode)"
                class="inline-flex rounded-full px-2.5 py-1 text-[11px] font-bold"
                :class="benchmarkLevelClass(formatBenchmarkComparison(trace.durationMs, findBenchmark(trace.stageCode, trace.executionMode))?.level)">
                {{ formatBenchmarkComparison(trace.durationMs, findBenchmark(trace.stageCode, trace.executionMode))?.text || '-' }}
              </span>
            </div>
            <div class="grid grid-cols-2 gap-2 text-[13px]">
              <div class="grid gap-0.5"><span class="text-xs text-muted-foreground">本次</span><span class="font-semibold text-primary">{{ trace.durationMs }} ms</span></div>
              <template v-if="findBenchmark(trace.stageCode, trace.executionMode)">
                <div v-for="bm in benchmarkCols(trace)" :key="bm.label" class="grid gap-0.5">
                  <span class="text-xs text-muted-foreground">{{ bm.label }}</span>
                  <span class="text-foreground">{{ bm.value }}</span>
                </div>
              </template>
              <div v-else class="grid gap-0.5"><span class="text-xs text-muted-foreground">基准</span><span class="text-muted-foreground">暂无数据</span></div>
            </div>
          </article>
        </div>
      </section>

      <div v-if="traceDetailOpen && overlayInspector" class="fixed inset-0 z-50 bg-[rgba(15,23,42,0.5)]" @click="closeTraceDetail">
        <aside class="absolute bottom-0 right-0 top-0 flex w-[540px] max-w-[90vw] flex-col bg-card shadow-[-4px_0_24px_rgba(15,23,42,0.14)]" @click.stop>
          <div class="flex items-start justify-between gap-3 border-b border-border px-6 py-5">
            <div>
              <div class="mt-1 flex flex-wrap items-center gap-2">
                <h3 class="m-0 text-base font-semibold text-foreground">{{ overlayInspector.title }}</h3>
                <span v-if="overlayInspector.stageCode" class="inline-flex rounded bg-foreground/[0.06] px-2 py-0.5 font-mono text-[10px] font-semibold text-[var(--color-muted-strong)]">{{ overlayInspector.stageCode }}</span>
              </div>
              <p class="mt-0.5 text-[13px] text-[var(--color-muted-strong)]">{{ overlayInspector.summary || '这个阶段已经执行完成，下面是它记录下来的结构化细节。' }}</p>
            </div>
            <button class="shrink-0 rounded-md border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="closeTraceDetail">关闭</button>
          </div>
          <div class="flex-1 overflow-y-auto px-6 py-5">
            <div class="mb-4 flex flex-wrap gap-3 text-[13px] text-muted-foreground">
              <span>状态：{{ formatStatusLabel(overlayInspector.status) }}</span>
              <span v-if="overlayInspector.stageCode">阶段代码：{{ overlayInspector.stageCode }}</span>
              <span>开始：{{ formatDateTime(overlayInspector.startTime) }}</span>
              <span>结束：{{ formatDateTime(overlayInspector.endTime) }}</span>
              <span>耗时：{{ overlayInspector.durationMs ? `${overlayInspector.durationMs} ms` : '无' }}</span>
            </div>
            <div v-if="overlayInspector.summaryItems?.length" class="grid grid-cols-2 gap-3 max-[540px]:grid-cols-1">
              <div v-for="item in overlayInspector.summaryItems" :key="`trace-item-${item.label}`" class="grid gap-1">
                <span class="text-xs text-muted-foreground">{{ item.label }}</span>
                <pre v-if="item.code" class="overflow-auto rounded-md bg-[#0f172a] p-3 text-xs text-[#e2e8f0] whitespace-pre-wrap">{{ item.value }}</pre>
                <strong v-else class="text-sm text-foreground">{{ item.value }}</strong>
              </div>
            </div>
            <div v-if="overlayInspector.listSections?.length" class="mt-4 flex flex-col gap-4">
              <section v-for="item in overlayInspector.listSections" :key="`trace-list-${item.label}`">
                <span class="text-xs text-muted-foreground">{{ item.label }}</span>
                <ol v-if="item.ordered" class="mt-2 list-decimal pl-5 flex flex-col gap-1 text-[13px] text-foreground">
                  <li v-for="(entry, idx) in item.items" :key="`${item.label}-${idx}`">{{ entry }}</li>
                </ol>
                <ul v-else class="mt-2 list-disc pl-5 flex flex-col gap-1 text-[13px] text-foreground">
                  <li v-for="(entry, idx) in item.items" :key="`${item.label}-${idx}`">{{ entry }}</li>
                </ul>
              </section>
            </div>
            <div v-if="overlayInspector.tableSections?.length" class="mt-4 flex flex-col gap-4">
              <section v-for="table in overlayInspector.tableSections" :key="`trace-table-${table.label}`">
                <span class="text-xs text-muted-foreground">{{ table.label }}</span>
                <div class="mt-2 overflow-x-auto rounded-md border border-border">
                  <table class="w-full border-collapse text-sm">
                    <thead><tr class="bg-secondary"><th v-for="col in table.columns" :key="col" class="border-b border-border px-3 py-2.5 text-left text-xs font-semibold text-muted-foreground">{{ col }}</th></tr></thead>
                    <tbody>
                      <tr v-for="(row, ri) in table.rows" :key="`row-${table.label}-${ri}`" class="border-b border-border last:border-0">
                        <td v-for="(cell, ci) in row.cells" :key="`cell-${ri}-${ci}`" class="px-3 py-2.5 text-[13px] text-foreground">{{ cell }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </section>
            </div>
            <details v-if="overlayInspector.advancedItems?.length" class="mt-4 border-t border-border pt-3">
              <summary class="cursor-pointer text-[13px] font-semibold text-primary">查看这个阶段的原始快照</summary>
              <div class="mt-3 grid grid-cols-2 gap-3 max-[540px]:grid-cols-1">
                <div v-for="item in overlayInspector.advancedItems" :key="`trace-advanced-${item.label}`" class="grid gap-1">
                  <span class="text-xs text-muted-foreground">{{ item.label }}</span>
                  <pre v-if="item.code" class="overflow-auto rounded-md bg-[#0f172a] p-3 text-xs text-[#e2e8f0] whitespace-pre-wrap">{{ item.value }}</pre>
                  <strong v-else class="text-sm text-foreground">{{ item.value }}</strong>
                </div>
              </div>
            </details>
          </div>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, ref, watch, watchEffect } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ArrowLeftIcon, ArrowPathIcon } from '@heroicons/vue/24/outline'
import { chatApi } from '../../api/api'
import {
  buildExchangeStages, buildExchangeStatusNarrative, buildTraceStageInspector, buildUsageStageInspector,
  buildChannelExecutionDisplay, buildCitationRepairView, buildFusionTraceGroups, buildTableEvidenceItems,
  formatChatMode, formatDateTime, formatExecutionMode, formatStatusLabel, formatChannelType,
  formatExecutionState, formatScore, groupResultsBySubQuestion, normalizeError, statusTone, truncate
} from './observabilityHelpers'

const route = useRoute()
const loadingPage = ref(false)
const activeSession = ref(null)
const activeExchangeDetail = ref(null)
const pageError = ref('')
const traceDetailOpen = ref(false)
const selectedTraceStageId = ref('')
const overlayInspector = ref(null)
const retrievalResults = ref([])
const channelExecutions = ref([])
const loadingRetrievalData = ref(false)
const stageBenchmarks = ref([])
const loadingBenchmarks = ref(false)
const activePromptTab = ref('system')

const conversationId = computed(() => String(route.params.conversationId || ''))
const exchangeId = computed(() => String(route.params.exchangeId || ''))
const activeExchange = computed(() => activeExchangeDetail.value?.exchange || null)
const stageTraces = computed(() => activeExchangeDetail.value?.stageTraces || [])
const activeTraceStage = computed(() => {
  if (!selectedTraceStageId.value) return stageTraces.value[0] || null
  return stageTraces.value.find((item) => String(item.stageId) === selectedTraceStageId.value) || stageTraces.value[0] || null
})
const activeTraceInspector = computed(() => buildTraceStageInspector(activeTraceStage.value, activeExchange.value))
const exchangeStages = computed(() => buildExchangeStages(activeSession.value, activeExchange.value))
const currentExchangeNarrative = computed(() => activeExchange.value ? buildExchangeStatusNarrative(activeExchange.value) : '这页只负责看这一轮的执行链路。')
const totalTokenCount = computed(() => (activeExchange.value?.debugTrace?.modelUsageTraces || []).reduce((sum, item) => sum + Number(item?.totalTokens || 0), 0))
const totalCostText = computed(() => { const total = (activeExchange.value?.debugTrace?.modelUsageTraces || []).reduce((sum, item) => sum + Number(item?.estimatedCost || 0), 0); return total > 0 ? `¥ ${total.toFixed(4)}` : '无' })
const maxTraceDuration = computed(() => stageTraces.value.reduce((max, item) => Math.max(max, Number(item?.durationMs || 0)), 0))
const groupedRetrievalResults = computed(() => groupResultsBySubQuestion(retrievalResults.value))
const fusionTraceView = computed(() => buildFusionTraceGroups(retrievalResults.value, channelExecutions.value, stageTraces.value, activeExchange.value?.references || []))
const channelExecutionDisplay = computed(() => buildChannelExecutionDisplay(channelExecutions.value, fusionTraceView.value))
const evidenceBudgetSnapshot = computed(() => stageTraces.value.find((item) => item.stageCode === 'EVIDENCE_BUDGET')?.snapshot || null)
const tableEvidenceItems = computed(() => buildTableEvidenceItems(activeExchange.value?.references || []))
const tableEvidenceBboxCount = computed(() => tableEvidenceItems.value.reduce((sum, item) => sum + item.cellBboxJsons.length, 0))
const citationRepairView = computed(() => buildCitationRepairView(stageTraces.value, activeExchange.value?.references || [], retrievalResults.value))
const citationSummaryItems = computed(() => [
  { label: '候选引用', value: citationRepairView.value.summary.candidateReferenceCount },
  { label: '文档候选', value: citationRepairView.value.summary.documentReferenceCount },
  { label: '语义匹配', value: citationRepairView.value.summary.matchedCitationCount },
  { label: '最终引用', value: citationRepairView.value.summary.repairedReferenceCount },
  { label: '移除文档证据', value: citationRepairView.value.summary.removedDocumentReferenceCount },
  { label: '阈值 / 分句', value: `${citationRepairView.value.summary.minScore ?? '-'} / ${citationRepairView.value.summary.maxSegments ?? '-'}` }
])
const ragSystemPrompt = computed(() => activeExchange.value?.debugTrace?.ragSystemPrompt || '')
const ragUserPrompt = computed(() => activeExchange.value?.debugTrace?.ragUserPrompt || '')
const hasPromptData = computed(() => Boolean(ragSystemPrompt.value || ragUserPrompt.value))
const headerMetaPairs = computed(() => activeExchange.value ? [
  { dt: '文档范围', dd: activeSession.value?.selectedDocumentName || '未绑定文档' },
  { dt: '执行时间', dd: formatDateTime(activeExchange.value.editTime || activeExchange.value.createTime) },
  { dt: '总耗时', dd: activeExchange.value.totalResponseTimeMs ? `${activeExchange.value.totalResponseTimeMs} ms` : '无' },
  { dt: '引用 / 推荐', dd: `${activeExchange.value.references?.length || 0} / ${activeExchange.value.recommendations?.length || 0}` },
  { dt: '总 Token / 成本', dd: `${totalTokenCount.value} / ${totalCostText.value}` }
] : [])
const budgetItems = computed(() => evidenceBudgetSnapshot.value ? [
  { label: '总预算', value: `${evidenceBudgetSnapshot.value.totalBudget || 0} 字符` },
  { label: '单子问题预算', value: `${evidenceBudgetSnapshot.value.perSubQuestionBudget || 0} 字符` },
  { label: '已纳入', value: `${evidenceBudgetSnapshot.value.renderedReferenceCount || 0} 条`, highlight: true },
  { label: '已省略', value: `${evidenceBudgetSnapshot.value.omittedReferenceCount || 0} 条` }
] : [])

function statusBadgeClass(status) {
  const tone = statusTone(status)
  if (tone === 'completed') return 'bg-[var(--color-success)]/10 text-[var(--color-success)]'
  if (tone === 'failed') return 'bg-destructive/10 text-destructive'
  if (tone === 'stopped') return 'bg-[var(--color-warning)]/10 text-[var(--color-warning)]'
  if (tone === 'running') return 'bg-[#0d7c7c]/10 text-[#0d7c7c]'
  return 'bg-foreground/[0.06] text-[var(--color-muted-strong)]'
}
function dotClass(status) {
  const tone = statusTone(status)
  if (tone === 'completed') return 'bg-[var(--color-success)]'
  if (tone === 'failed') return 'bg-[var(--color-danger)]'
  if (tone === 'stopped') return 'bg-[var(--color-warning)]'
  if (tone === 'running') return 'bg-[#0d7c7c]'
  return 'bg-border-strong'
}
function summaryChipClass(tone) {
  if (tone === 'success') return 'bg-[var(--color-success)]/10 text-[var(--color-success)]'
  if (tone === 'danger') return 'bg-destructive/10 text-destructive'
  if (tone === 'warning') return 'bg-[var(--color-warning)]/10 text-[var(--color-warning)]'
  return 'bg-foreground/[0.06] text-[var(--color-muted-strong)]'
}
function benchmarkLevelClass(level) {
  if (level === 'excellent') return 'bg-green-500/[0.12] text-green-700'
  if (level === 'good') return 'bg-[var(--color-success)]/10 text-[var(--color-success)]'
  if (level === 'warning') return 'bg-amber-500/[0.14] text-amber-700'
  return 'bg-red-500/[0.12] text-red-700'
}
function fusionStatusClass(tone) {
  if (tone === 'success') return 'bg-[var(--color-success)]/10 text-[var(--color-success)]'
  if (tone === 'warning') return 'bg-amber-500/[0.14] text-amber-700'
  return 'bg-foreground/[0.06] text-[var(--color-muted-strong)]'
}
function channelMetrics(exec) {
  return [
    { label: '召回数', value: exec.recalledCount },
    { label: '闸门后', value: exec.acceptedCount },
    { label: '命中证据候选', value: exec.finalSelectedCount, highlight: true },
    { label: '耗时', value: exec.durationMs ? `${exec.durationMs} ms` : '-' },
    { label: '平均分', value: formatScore(exec.avgScore) },
    { label: '分数区间', value: `${formatScore(exec.minScore)}~${formatScore(exec.maxScore)}` }
  ]
}
function tableEvidencePairs(item) {
  return [
    { label: '表格 ID / 编号', value: [item.tableId ? `ID ${item.tableId}` : '', item.tableNo ? `T#${item.tableNo}` : ''].filter(Boolean).join(' / ') || '无' },
    { label: '查询操作', value: item.operationText },
    { label: '命中行数', value: item.matchedRowCount === '' ? '无' : `${item.matchedRowCount} 行` },
    { label: '证据行号', value: item.rowsText },
    { label: '证据列', value: item.columnsText },
    { label: '单元格坐标', value: item.cellsText },
    { label: '页码/位置', value: item.locationText },
    { label: 'bbox 状态', value: item.bboxText }
  ]
}
function tableEvidenceDocumentRoute(item) {
  return {
    name: 'AdminDocumentDetail',
    params: { documentId: item.documentId },
    query: {
      section: 'rag',
      highlightTableId: item.tableId || undefined,
      highlightTableNo: item.tableNo || undefined,
      highlightRows: item.rowNos.length ? item.rowNos.join(',') : undefined,
      highlightColumns: item.columnNames.length ? item.columnNames.join(',') : undefined,
      highlightCells: item.cellCoordinates.length ? item.cellCoordinates.join(',') : undefined
    }
  }
}
function citationDocumentRoute(item) {
  return {
    name: 'AdminDocumentDetail',
    params: { documentId: item.documentId },
    query: {
      section: 'rag',
      highlightChunkId: item.chunkId || undefined,
      highlightParentBlockId: item.parentBlockId || undefined,
      highlightTableId: item.tableId || undefined,
      highlightPageNo: item.pageNo || undefined
    }
  }
}
function benchmarkCols(trace) {
  const bm = findBenchmark(trace.stageCode, trace.executionMode)
  if (!bm) return []
  return [
    { label: 'P50', value: `${bm.p50DurationMs || '-'} ms` },
    { label: 'P90', value: `${bm.p90DurationMs || '-'} ms` },
    { label: 'P99', value: `${bm.p99DurationMs || '-'} ms` },
    { label: '样本数', value: bm.sampleCount }
  ]
}
async function loadStageBenchmarks() {
  loadingBenchmarks.value = true
  try { stageBenchmarks.value = await chatApi.getStageBenchmarks() || [] } catch { stageBenchmarks.value = [] } finally { loadingBenchmarks.value = false }
}
function findBenchmark(stageCode, executionMode) {
  return stageBenchmarks.value.find((b) => b.stageCode === stageCode && b.executionMode === executionMode) || null
}
function formatBenchmarkComparison(actualMs, benchmark) {
  if (!benchmark || !actualMs) return null
  const { p50DurationMs: p50 = 0, p90DurationMs: p90 = 0, p99DurationMs: p99 = 0 } = benchmark
  if (actualMs <= p50) return { level: 'excellent', text: '优秀（≤ P50）' }
  if (actualMs <= p90) return { level: 'good', text: '良好（P50-P90）' }
  if (actualMs <= p99) return { level: 'warning', text: '偏慢（P90-P99）' }
  return { level: 'slow', text: '异常慢（> P99）' }
}
async function loadRetrievalObserveData() {
  if (!conversationId.value || !exchangeId.value) return
  loadingRetrievalData.value = true
  try {
    const [results, executions] = await Promise.all([chatApi.getRetrievalResults(conversationId.value, exchangeId.value), chatApi.getChannelExecutions(conversationId.value, exchangeId.value)])
    retrievalResults.value = results || []
    channelExecutions.value = executions || []
  } catch { retrievalResults.value = []; channelExecutions.value = [] } finally { loadingRetrievalData.value = false }
}
async function loadPage() {
  if (!conversationId.value || !exchangeId.value) return
  loadingPage.value = true; pageError.value = ''
  try {
    const [session, exchangeDetail] = await Promise.all([chatApi.getSession(conversationId.value), chatApi.getExchangeDetail(conversationId.value, exchangeId.value)])
    activeSession.value = session; activeExchangeDetail.value = exchangeDetail
    selectedTraceStageId.value = String(exchangeDetail?.stageTraces?.[0]?.stageId || '')
    loadRetrievalObserveData(); loadStageBenchmarks()
  } catch (error) { activeSession.value = null; activeExchangeDetail.value = null; pageError.value = normalizeError(error, '加载轮次详情失败') }
  finally { loadingPage.value = false }
}
function openTraceDetail(stageId) { selectedTraceStageId.value = String(stageId || ''); overlayInspector.value = buildTraceStageInspector(activeTraceStage.value, activeExchange.value); traceDetailOpen.value = true }
function closeTraceDetail() { traceDetailOpen.value = false; overlayInspector.value = null }
function traceBarWidth(trace) { const d = Number(trace?.durationMs || 0); const m = maxTraceDuration.value; if (!d || !m) return '6%'; return `${Math.max((d / m) * 100, 6)}%` }
function findStageTrace(stageTitle) {
  if (!stageTitle) return null
  if (stageTitle.includes('检索执行')) return stageTraces.value.find((item) => item.stageCode === 'RAG_RETRIEVE' || item.stageCode === 'REACT_AGENT') || null
  if (stageTitle.includes('前置编排')) return stageTraces.value.find((item) => item.stageCode === 'INTENT') || null
  if (stageTitle.includes('请求入口')) return stageTraces.value.find((item) => item.stageCode === 'ROUTE') || null
  if (stageTitle.includes('生成回答') || stageTitle.includes('模型使用')) return stageTraces.value.find((item) => item.stageCode === 'ANSWER_GENERATE') || null
  if (stageTitle.includes('结果与诊断')) return stageTraces.value.find((item) => item.stageCode === 'FINALIZE') || null
  return null
}
function canOpenStage(stage) { return stage?.key === 'usage' || Boolean(findStageTrace(stage?.title)) }
function openSummaryStage(stage) {
  if (!stage) return
  if (stage.key === 'usage') { overlayInspector.value = buildUsageStageInspector(activeExchange.value); traceDetailOpen.value = true; return }
  const trace = findStageTrace(stage.title)
  if (!trace) return
  selectedTraceStageId.value = String(trace.stageId); overlayInspector.value = buildTraceStageInspector(trace, activeExchange.value); traceDetailOpen.value = true
}
watch([conversationId, exchangeId], () => { activeSession.value = null; activeExchangeDetail.value = null; traceDetailOpen.value = false; overlayInspector.value = null; selectedTraceStageId.value = ''; loadPage() }, { immediate: true })
watchEffect(() => {
  if (typeof window === 'undefined') return
  window.__obsDetailState = { loadingPage: loadingPage.value, hasSession: Boolean(activeSession.value), hasExchangeDetail: Boolean(activeExchangeDetail.value), conversationId: conversationId.value, exchangeId: exchangeId.value, selectedTraceStageId: selectedTraceStageId.value, traceDetailOpen: traceDetailOpen.value, overlayTitle: overlayInspector.value?.title || '' }
})
</script>
