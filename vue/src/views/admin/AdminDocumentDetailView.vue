<template>
  <section class="flex flex-col gap-4">
    <transition name="drawer-fade"><div v-if="logDrawerOpen" class="fixed inset-0 z-50 bg-[rgba(15,23,42,0.3)]" @click="closeLogDrawer"></div></transition>
    <transition name="drawer-fade"><div v-if="chunkDetailDrawerOpen" class="fixed inset-0 z-50 bg-[rgba(15,23,42,0.3)]" @click="closeChunkDetailDrawer"></div></transition>

    <transition name="build-mask-fade">
      <div v-if="showBuildBlockingOverlay" class="fixed inset-0 z-50 flex items-center justify-center bg-[rgba(15,23,42,0.6)]">
        <div class="w-[min(520px,90vw)] rounded-xl border border-border bg-card p-6 shadow-xl">
          <div class="mb-4 flex items-start gap-4">
            <span class="build-overlay-spinner shrink-0" aria-hidden="true"></span>
            <div><h3 class="text-base font-semibold text-foreground">{{ buildOverlayTitle }}</h3><p class="mt-1 text-[13px] text-[var(--color-muted-strong)]">{{ buildOverlayDescription }}</p></div>
          </div>
          <div class="mb-4 flex flex-wrap gap-3 text-xs text-muted-foreground"><span>任务 {{ buildTaskSnapshot?.taskId || activeBuildTaskId || '创建中' }}</span><span>当前阶段 {{ activeBuildStageLabel || '准备启动' }}</span></div>
          <div class="grid grid-cols-2 gap-2">
            <article v-for="stage in buildStageItems" :key="`overlay-stage-${stage.code}`" class="flex items-center gap-3 rounded-lg border p-3" :class="buildOverlayStageClass(stage.status)">
              <span class="grid h-7 w-7 shrink-0 place-items-center rounded-full text-xs font-bold"><span v-if="stage.status === 'current'" class="stage-spinner" aria-hidden="true"></span><span v-else>{{ stage.order }}</span></span>
              <div><strong class="block text-[13px] text-foreground">{{ stage.label }}</strong><span class="text-xs text-muted-foreground">{{ stage.statusLabel }}</span></div>
            </article>
          </div>
          <p class="mt-4 text-xs text-muted-foreground">执行期间页面已暂时锁定，避免重复发起构建或误改当前策略链路。</p>
        </div>
      </div>
    </transition>

    <transition name="drawer-slide">
      <aside v-if="logDrawerOpen" class="fixed bottom-0 right-0 top-0 z-[51] flex w-[480px] max-w-[90vw] flex-col bg-card shadow-[-4px_0_24px_rgba(15,23,42,0.12)]">
        <div class="flex items-start justify-between border-b border-border px-6 py-5">
          <div><h3 class="text-base font-semibold text-foreground">任务执行详情</h3><p class="mt-0.5 text-xs text-muted-foreground">任务 {{ documentDetail?.latestTaskId || '-' }} · {{ documentDetail?.latestTaskTypeName || '暂无任务类型' }}</p></div>
          <button class="grid h-9 w-9 place-items-center rounded-md border border-border bg-card text-foreground hover:bg-secondary" type="button" @click="closeLogDrawer"><XMarkIcon class="h-4 w-4" /></button>
        </div>
        <div class="flex flex-wrap gap-3 border-b border-border px-6 py-3">
          <div class="flex items-center gap-2 text-xs text-muted-foreground"><span>当前状态</span><AdminStatusBadge :label="documentDetail?.latestTaskStatusName || '暂无状态'" :code="documentDetail?.latestTaskStatus" type="task" /></div>
          <div class="flex items-center gap-2 text-xs text-muted-foreground"><span>索引状态</span><AdminStatusBadge :label="documentDetail?.indexStatusName || '暂无状态'" :code="documentDetail?.indexStatus" type="index" /></div>
        </div>
        <div v-if="logLoading" class="flex-1 py-8 text-center text-sm text-muted-foreground">正在加载任务日志...</div>
        <div v-else-if="!taskLogs.length" class="flex-1 py-8 text-center text-sm text-muted-foreground">当前任务还没有日志记录。</div>
        <div v-else class="flex-1 overflow-y-auto px-6 py-5">
          <article v-for="log in taskLogs" :key="log.id" class="flex gap-3 border-b border-black/[0.04] pb-4 pt-3 last:border-0">
            <div class="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-primary/60"></div>
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between gap-2 text-xs"><strong class="text-foreground">{{ log.stageTypeName }} · {{ log.eventTypeName }}</strong><span class="shrink-0 text-muted-foreground">{{ formatDateTime(log.createTime) }}</span></div>
              <p class="mt-1 text-[13px] text-[var(--color-muted-strong)]">{{ log.content }}</p>
              <pre v-if="log.detailJson" class="mt-2 overflow-auto rounded-md bg-[#0f172a] p-2.5 text-xs text-[#e2e8f0]">{{ log.detailJson }}</pre>
            </div>
          </article>
        </div>
      </aside>
    </transition>

    <transition name="drawer-slide">
      <aside v-if="chunkDetailDrawerOpen" class="fixed bottom-0 right-0 top-0 z-[51] flex w-[560px] max-w-[95vw] flex-col bg-card shadow-[-4px_0_24px_rgba(15,23,42,0.12)]">
        <div class="flex items-start justify-between border-b border-border px-6 py-5">
          <div><h3 class="text-base font-semibold text-foreground">Chunk 详情</h3>
            <p class="mt-0.5 text-xs text-muted-foreground"><template v-if="chunkDetail?.chunk">子块 C#{{ chunkDetail.chunk.chunkNo || '-' }} · 父块 P#{{ chunkDetail.parentBlock?.parentBlockNo || '-' }}</template><template v-else>正在读取切块详情</template></p>
          </div>
          <button class="grid h-9 w-9 place-items-center rounded-md border border-border bg-card text-foreground hover:bg-secondary" type="button" @click="closeChunkDetailDrawer"><XMarkIcon class="h-4 w-4" /></button>
        </div>
        <div v-if="chunkDetailLoading" class="flex-1 py-8 text-center text-sm text-muted-foreground">正在加载 chunk 详情...</div>
        <div v-else-if="!chunkDetail?.chunk" class="flex-1 py-8 text-center text-sm text-muted-foreground">当前没有可展示的 chunk 详情。</div>
        <div v-else class="flex-1 overflow-y-auto px-6 py-5">
          <div class="mb-4 flex flex-wrap gap-3">
            <div class="flex items-center gap-2 text-xs text-muted-foreground"><span>当前子块</span><strong class="text-foreground">C#{{ chunkDetail.chunk.chunkNo || '-' }}</strong></div>
            <div class="flex items-center gap-2 text-xs text-muted-foreground"><span>所属父块</span><strong class="text-foreground">P#{{ chunkDetail.parentBlock?.parentBlockNo || '-' }}</strong></div>
            <div class="flex items-center gap-2 text-xs text-muted-foreground"><span>同父子块</span><strong class="text-foreground">{{ chunkDetail.parentBlock?.childCount || chunkDetail.siblingChunks?.length || 0 }}</strong></div>
          </div>
          <div class="mb-4 rounded-lg border border-primary/20 bg-primary/[0.04] p-4">
            <div class="mb-2 flex items-center justify-between gap-2"><div class="flex items-center gap-2"><span class="rounded bg-primary px-2 py-0.5 text-[11px] font-bold text-white">Child Evidence</span><h4 class="text-sm font-semibold text-foreground">当前子块 C#{{ chunkDetail.chunk.chunkNo || '-' }}</h4></div><span class="text-xs text-muted-foreground">{{ buildChunkRelationText(chunkDetail.chunk) }}</span></div>
            <div class="mb-2 flex flex-wrap gap-3 text-xs text-muted-foreground"><span>章节：{{ chunkDetail.chunk.sectionPath || '未识别章节' }}</span><span>字符：{{ formatCount(chunkDetail.chunk.charCount) }}</span><span>Token：{{ formatCount(chunkDetail.chunk.tokenCount) }}</span></div>
            <pre class="overflow-auto whitespace-pre-wrap break-words text-[13px] text-foreground">{{ chunkDetail.chunk.chunkText }}</pre>
          </div>
          <div v-if="chunkDetail.parentBlock" class="mb-4 rounded-lg border border-[#0f766e]/20 bg-[#0f766e]/[0.04] p-4">
            <div class="mb-2 flex items-center justify-between gap-2"><div class="flex items-center gap-2"><span class="rounded bg-[#0f766e] px-2 py-0.5 text-[11px] font-bold text-white">Parent Context</span><h4 class="text-sm font-semibold text-foreground">所属父块 P#{{ chunkDetail.parentBlock.parentBlockNo || '-' }}</h4></div><span class="text-xs text-muted-foreground">子块范围 C#{{ chunkDetail.parentBlock.startChunkNo || '-' }} - C#{{ chunkDetail.parentBlock.endChunkNo || '-' }}</span></div>
            <div class="mb-2 flex flex-wrap gap-3 text-xs text-muted-foreground"><span>章节：{{ chunkDetail.parentBlock.sectionPath || '未识别章节' }}</span><span>字符：{{ formatCount(chunkDetail.parentBlock.charCount) }}</span><span>Token：{{ formatCount(chunkDetail.parentBlock.tokenCount) }}</span></div>
            <pre class="overflow-auto whitespace-pre-wrap break-words text-[13px] text-foreground">{{ chunkDetail.parentBlock.parentText }}</pre>
          </div>
          <div v-if="Array.isArray(chunkDetail.siblingChunks) && chunkDetail.siblingChunks.length" class="rounded-lg border border-border bg-secondary p-4">
            <div class="mb-2 flex items-center justify-between gap-2"><h4 class="text-sm font-semibold text-foreground">同父子块关系</h4><span class="text-xs text-muted-foreground">点击可切换查看其他子块</span></div>
            <p class="mb-3 text-xs text-muted-foreground">当前父块 P#{{ chunkDetail.parentBlock?.parentBlockNo || '-' }} 内包含 {{ formatChunkCodeList(chunkDetail.siblingChunks) }} 这些子块，当前命中的是 C#{{ chunkDetail.chunk.chunkNo || '-' }}。</p>
            <div class="mb-3 flex flex-wrap items-center gap-2">
              <template v-for="(item, index) in chunkDetail.siblingChunks" :key="`track-${item.chunkId}`">
                <button class="flex flex-col items-center rounded-lg border px-3 py-2 text-center transition-colors" :class="isCurrentChunk(item) ? 'border-primary bg-primary/[0.08]' : 'border-border bg-card hover:border-primary/20'" type="button" @click="openChunkDetail(item.chunkId)"><strong class="text-[13px] text-foreground">C#{{ item.chunkNo || '-' }}</strong><span class="text-[11px] text-muted-foreground">{{ buildSiblingOrderLabel(index, chunkDetail.siblingChunks.length) }}</span></button>
                <div v-if="index < chunkDetail.siblingChunks.length - 1" class="h-0.5 w-4 rounded bg-border"></div>
              </template>
            </div>
            <div class="grid gap-2" style="grid-template-columns:repeat(auto-fill,minmax(180px,1fr))">
              <button v-for="item in chunkDetail.siblingChunks" :key="`sibling-${item.chunkId}`" class="grid gap-1 rounded-lg border p-3 text-left transition-colors" :class="normalizeCode(item.chunkId) === normalizeCode(chunkDetail.chunk.chunkId) ? 'border-primary/30 bg-primary/[0.06]' : 'border-border bg-card hover:border-primary/20'" type="button" @click="openChunkDetail(item.chunkId)">
                <div class="flex items-center justify-between gap-2"><strong class="text-[13px] text-foreground">子块 C#{{ item.chunkNo || '-' }}</strong><span class="text-xs text-muted-foreground">{{ buildChunkRelationText(item) }}</span></div>
                <p class="text-xs text-muted-foreground">{{ item.sectionPath || '未识别章节' }}</p>
                <span class="line-clamp-2 text-xs text-foreground">{{ item.chunkText }}</span>
              </button>
            </div>
          </div>
        </div>
      </aside>
    </transition>

    <div class="flex items-center justify-between gap-4">
      <div>
        <div class="mb-1 flex items-center gap-2 text-sm text-muted-foreground">
          <button class="inline-flex items-center gap-1.5 rounded-md border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="goBack"><ArrowLeftIcon class="h-4 w-4" />返回文档列表</button>
          <span>文档接入</span><span>/</span><strong class="text-foreground">文档工作台</strong>
        </div>
        <p class="text-xs text-muted-foreground">围绕单个文档完成策略确认、索引构建、验证 Chunk 结果与任务追踪。</p>
      </div>
      <button class="rounded-full border border-border bg-card px-4 py-2 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60" type="button" :disabled="loading" @click="loadAll">{{ loading ? '刷新中...' : '刷新详情' }}</button>
    </div>

    <div v-if="pageNotice.message" class="rounded-md px-4 py-3 text-sm font-medium" :class="noticeClass(pageNotice.type)">{{ pageNotice.message }}</div>

    <article v-if="documentDetail" class="rounded-lg border border-border bg-card shadow-sm">
      <nav class="flex overflow-x-auto border-b border-border" aria-label="文档工作台章节导航">
        <button v-for="item in workbenchSections" :key="`workbench-nav-${item.key}`"
          class="flex shrink-0 items-center gap-3 border-r border-border px-5 py-4 last:border-r-0 transition-colors"
          :class="activeWorkbenchSection === item.key ? 'bg-primary/[0.06] text-primary' : 'text-foreground hover:bg-secondary'"
          type="button" @click="scrollToWorkbenchSection(item.key)">
          <span class="grid h-7 w-7 shrink-0 place-items-center rounded-full text-xs font-bold"
            :class="activeWorkbenchSection === item.key ? 'bg-primary text-white' : 'bg-foreground/[0.08] text-muted-foreground'">{{ item.step }}</span>
          <span class="flex flex-col items-start">
            <strong class="text-[13px] font-semibold">{{ item.label }}</strong>
            <span class="text-xs text-muted-foreground">{{ item.caption }}</span>
          </span>
          <em class="hidden whitespace-nowrap text-[11px] not-italic text-muted-foreground md:block">{{ item.status }}</em>
        </button>
      </nav>

      <div class="p-5">
        <section v-show="activeWorkbenchSection === 'overview'" ref="overviewSectionRef" data-workbench-section="overview">
          <div class="mb-4 flex items-start justify-between gap-3">
            <div><h2 class="mt-1 text-lg font-semibold text-foreground">文档概览</h2><p class="mt-0.5 text-[13px] text-muted-foreground">先确认文档状态、关键指标和当前工作焦点，再进入下方流程。</p></div>
            <span class="inline-flex rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ workflowCurrentPhase.shortLabel }}</span>
          </div>
          <div class="mb-4 flex items-start justify-between gap-4 rounded-xl border border-border bg-secondary p-4 max-md:flex-col">
            <div><h3 class="mt-1 text-base font-semibold text-foreground">{{ documentDetail.documentName }}</h3><p v-if="showOriginalFileName" class="mt-0.5 text-xs text-muted-foreground">{{ documentDetail.originalFileName }}</p></div>
            <div class="flex flex-col items-end gap-2">
              <div class="flex flex-wrap gap-1.5"><AdminStatusBadge :label="documentDetail.parseStatusName" :code="documentDetail.parseStatus" type="parse" /><AdminStatusBadge :label="documentDetail.strategyStatusName" :code="documentDetail.strategyStatus" type="strategy" /><AdminStatusBadge :label="documentDetail.indexStatusName" :code="documentDetail.indexStatus" type="index" /></div>
              <span class="text-xs text-muted-foreground">{{ workflowCurrentPhase.title }}</span>
            </div>
          </div>
          <div class="mb-4 grid grid-cols-2 gap-3 max-sm:grid-cols-1">
            <article class="grid gap-2 rounded-xl border p-4" :class="guidanceCardClass(workflowCurrentPhase.tone)">

              <strong class="text-sm text-foreground">{{ workflowCurrentPhase.title }}</strong>
              <p class="text-xs text-muted-foreground">{{ workflowCurrentPhase.description }}</p>
            </article>
            <article class="grid gap-2 rounded-xl border border-border bg-secondary p-4">

              <strong class="text-sm text-foreground">{{ workflowNextAction.title }}</strong>
              <p class="text-xs text-muted-foreground">{{ workflowNextAction.description }}</p>
            </article>
          </div>
          <div class="rounded-xl border border-border bg-secondary p-4">
            <div class="flex flex-wrap gap-2">
              <button class="rounded-full border border-border bg-card px-4 py-2 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="scrollToWorkbenchSection('strategy')">查看策略配置</button>
              <button class="rounded-full border border-border bg-card px-4 py-2 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="scrollToWorkbenchSection('execution')">前往确认与构建</button>
              <button class="rounded-full border border-border bg-card px-4 py-2 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="scrollToWorkbenchSection('chunk')">检查 Chunk 结果</button>
            </div>
          </div>
        </section>

        <section v-show="activeWorkbenchSection === 'strategy'" ref="strategySectionRef" class="border-t border-border pt-5 mt-5" data-workbench-section="strategy">
          <div class="mb-4 flex items-start justify-between gap-3">
            <div><h2 class="mt-1 text-lg font-semibold text-foreground">配置策略</h2><p class="mt-0.5 text-[13px] text-muted-foreground">先阅读系统推荐，再分别调整父块和子块流水线，形成最终执行方案。</p></div>
            <span class="inline-flex rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ strategySectionStatusText }}</span>
          </div>
          <div v-if="documentDetail.parseErrorMsg" class="mb-3 rounded-md border border-destructive/10 bg-destructive/[0.06] px-3 py-2.5 text-sm text-destructive">{{ documentDetail.parseErrorMsg }}</div>
          <div v-if="strategySystemStages.length" class="mb-4 grid gap-2" :style="`grid-template-columns:repeat(${strategySystemStages.length},minmax(0,1fr))`">
            <article v-for="item in strategySystemStages" :key="`strategy-stage-${item.code}`" class="flex items-center gap-2 rounded-lg border p-3" :class="strategyStatusStepClass(item.status)">
              <div class="grid h-7 w-7 shrink-0 place-items-center rounded-full text-xs font-bold" :class="item.status === 'done' ? 'bg-[var(--color-success)] text-white' : item.status === 'current' ? 'bg-primary text-white' : 'bg-foreground/[0.08] text-muted-foreground'">{{ item.order }}</div>
              <div><strong class="block text-[13px] text-foreground">{{ item.label }}</strong><span class="text-xs text-muted-foreground">{{ item.description }}</span></div>
            </article>
          </div>
          <div v-if="planLoading" class="py-6 text-center text-sm text-muted-foreground">正在读取策略详情...</div>
          <div v-else-if="!strategyPlan?.planReady" class="rounded-md border border-dashed border-border py-6 text-center text-sm text-muted-foreground">当前文档尚未生成策略方案，解析完成后可点击刷新查看。</div>
          <template v-else>
            <div class="mb-4 rounded-lg border border-border bg-secondary p-4">
              <p class="text-[13px] text-[var(--color-muted-strong)]">{{ strategyPlan.plan?.recommendReason || '系统已生成推荐策略，可以根据业务需要再做补充。' }}</p>
            </div>
            <div class="mb-5 grid grid-cols-2 gap-3 max-md:grid-cols-1">
              <section v-for="pipeline in strategyPipelineLibrary" :key="`recommended-${pipeline.key}`" class="rounded-lg border p-4" :class="pipeline.key === 'parent' ? 'border-primary/20 bg-primary/[0.03]' : 'border-[#0f766e]/20 bg-[#0f766e]/[0.03]'">
                <p class="text-[11px] font-medium" :class="pipeline.key === 'parent' ? 'text-primary' : 'text-[#0f766e]'">{{ pipeline.key === 'parent' ? '父块流水线（回答上下文）' : '子块流水线（检索召回）' }}</p>
                <h5 class="mt-1 mb-3 text-sm font-semibold text-foreground">{{ pipeline.label }}</h5>
                <div v-if="resolvePlanPipeline(strategyPlan.plan, pipeline.key)?.steps?.length" class="flex flex-col gap-2">
                  <template v-for="(step, index) in resolvePlanPipeline(strategyPlan.plan, pipeline.key).steps" :key="`${strategyPlan.plan.planId}-${pipeline.key}-${step.stepNo}`">
                    <article class="flex items-start gap-3 rounded-md border border-border bg-card p-3"><div class="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-foreground/[0.08] text-xs font-bold text-muted-foreground">{{ String(step.stepNo).padStart(2,'0') }}</div><div><strong class="block text-[13px] text-foreground">{{ step.strategyName }}</strong><p class="mt-0.5 text-xs text-muted-foreground">{{ step.recommendReason || step.strategyRoleName }}</p></div></article>
                    <div v-if="index < resolvePlanPipeline(strategyPlan.plan, pipeline.key).steps.length - 1" class="flex justify-center text-muted-foreground">↓</div>
                  </template>
                </div>
                <div v-else class="text-sm text-muted-foreground">当前方案还没有 {{ pipeline.label }} 配置。</div>
              </section>
            </div>
            <div class="mb-5">
              <div class="mb-3 border-b border-border pb-3">
                <h5 class="text-sm font-semibold text-foreground">双流水线调整</h5>
                <p class="mt-0.5 text-xs text-muted-foreground">分别配置父块回答流水线和子块召回流水线，并通过上移 / 下移调整顺序。</p>
              </div>
              <div class="grid grid-cols-2 gap-3 max-md:grid-cols-1">
                <section v-for="pipeline in strategyPipelineLibrary" :key="`editor-${pipeline.key}`" class="rounded-lg border-t-2 border border-border p-4" :class="pipeline.key === 'parent' ? 'border-t-primary' : 'border-t-teal-700'">
                  <p class="text-sm font-semibold" :class="pipeline.key === 'parent' ? 'text-primary' : 'text-teal-700'">{{ pipeline.key === 'parent' ? '父块流水线（回答上下文）' : '子块流水线（检索召回）' }}</p>
                  <h5 class="mt-1 mb-3 text-sm font-semibold text-foreground">{{ pipeline.label }}</h5>
                  <div class="mb-3 rounded-md border p-3" :class="pipeline.key === 'parent' ? 'border-primary/20 bg-primary/[0.03]' : 'border-[#0f766e]/20 bg-[#0f766e]/[0.03]'">
                    <p class="mb-2 text-xs font-semibold" :class="pipeline.key === 'parent' ? 'text-primary' : 'text-[#0f766e]'">当前配置</p>
                    <div v-if="getSelectedStrategyPreview(pipeline.key).length" class="flex flex-col gap-2">
                      <template v-for="(row, rowIndex) in getSelectedStrategyRows(pipeline.key)" :key="`row-${pipeline.key}-${rowIndex}`">
                        <div class="flex items-stretch gap-2">
                          <article v-if="row.leftItem" class="flex flex-1 items-center gap-2 rounded-md border border-border bg-card p-2.5"><div class="grid h-6 w-6 shrink-0 place-items-center rounded-full text-white text-xs font-bold" :class="pipeline.key==='parent'?'bg-primary':'bg-teal-700'">{{ row.leftItem.order }}</div><div class="min-w-0 flex-1"><strong class="block text-[13px]">{{ row.leftItem.label }}</strong></div><div class="flex flex-col gap-1"><button class="rounded px-1.5 py-0.5 text-[11px] font-semibold disabled:opacity-40" :class="pipeline.key==='parent'?'text-primary':'text-teal-700'" type="button" :disabled="row.leftItem.index===0" @click="moveStrategy(row.leftItem.type,-1,pipeline.key)">上移</button><button class="rounded px-1.5 py-0.5 text-[11px] font-semibold disabled:opacity-40" :class="pipeline.key==='parent'?'text-primary':'text-teal-700'" type="button" :disabled="row.leftItem.index===getSelectedStrategyPreview(pipeline.key).length-1" @click="moveStrategy(row.leftItem.type,1,pipeline.key)">下移</button></div></article>
                          <div v-else class="flex-1"></div>
                          <div class="flex w-5 items-center justify-center text-xs text-muted-foreground">{{ row.leftItem && row.rightItem ? (row.direction==='rtl'?'←':'→') : '' }}</div>
                          <article v-if="row.rightItem" class="flex flex-1 items-center gap-2 rounded-md border border-border bg-card p-2.5"><div class="grid h-6 w-6 shrink-0 place-items-center rounded-full text-white text-xs font-bold" :class="pipeline.key==='parent'?'bg-primary':'bg-teal-700'">{{ row.rightItem.order }}</div><div class="min-w-0 flex-1"><strong class="block text-[13px]">{{ row.rightItem.label }}</strong></div><div class="flex flex-col gap-1"><button class="rounded px-1.5 py-0.5 text-[11px] font-semibold disabled:opacity-40" :class="pipeline.key==='parent'?'text-primary':'text-teal-700'" type="button" :disabled="row.rightItem.index===0" @click="moveStrategy(row.rightItem.type,-1,pipeline.key)">上移</button><button class="rounded px-1.5 py-0.5 text-[11px] font-semibold disabled:opacity-40" :class="pipeline.key==='parent'?'text-primary':'text-teal-700'" type="button" :disabled="row.rightItem.index===getSelectedStrategyPreview(pipeline.key).length-1" @click="moveStrategy(row.rightItem.type,1,pipeline.key)">下移</button></div></article>
                          <div v-else class="flex-1"></div>
                        </div>
                        <div v-if="rowIndex < getSelectedStrategyRows(pipeline.key).length-1" class="flex" :class="row.downColumn==='right'?'justify-end pr-8':row.downColumn==='left'?'justify-start pl-8':'justify-center'"><span class="text-sm text-muted-foreground">↓</span></div>
                      </template>
                    </div>
                    <p v-else class="text-xs text-muted-foreground">{{ pipeline.label }}至少选择一个拆分策略，已选策略会在这里形成清晰的箭头处理链路。</p>
                  </div>
                  <div class="mb-3 grid gap-2" style="grid-template-columns:repeat(auto-fill,minmax(130px,1fr))">
                    <button v-for="item in strategyLibrary" :key="`${pipeline.key}-${item.type}`" class="grid gap-1 rounded-lg border p-3 text-left transition-colors" :class="getSelectedStrategyTypes(pipeline.key).includes(item.type) ? (pipeline.key==='parent' ? 'border-primary/30 bg-primary/[0.06]' : 'border-teal-700/30 bg-teal-700/[0.06]') : (pipeline.key==='parent' ? 'border-border bg-secondary hover:border-primary/20' : 'border-border bg-secondary hover:border-teal-700/20')" type="button" @click="toggleStrategy(item.type,pipeline.key)">
                      <div class="flex items-center justify-between gap-1"><span class="text-[11px]" :class="getSelectedStrategyTypes(pipeline.key).includes(item.type) ? (pipeline.key==='parent'?'text-primary':'text-teal-700') : 'text-muted-foreground'">{{ getSelectedStrategyTypes(pipeline.key).includes(item.type)?'已选中':'点击添加' }}</span><CheckCircleIcon v-if="getSelectedStrategyTypes(pipeline.key).includes(item.type)" class="h-4 w-4" :class="pipeline.key==='parent'?'text-primary':'text-teal-700'" /></div>
                      <strong class="text-[13px] text-foreground">{{ item.label }}</strong><span class="text-xs text-muted-foreground">{{ item.description }}</span>
                    </button>
                  </div>
                  <div class="rounded-md p-3 text-xs" :class="pipeline.key==='parent'?'bg-primary/[0.06]':'bg-[#0f766e]/[0.06]'">
                    <span class="mb-1.5 block font-semibold" :class="pipeline.key==='parent'?'text-primary':'text-[#0f766e]'">{{ pipeline.label }}最终提交顺序</span>
                    <div v-if="getSelectedStrategyPreview(pipeline.key).length" class="flex flex-wrap items-center gap-1.5"><template v-for="(item,index) in getSelectedStrategyPreview(pipeline.key)" :key="`preview-${pipeline.key}-${item.type}`"><span class="rounded bg-background px-2 py-0.5 text-foreground">{{ item.label }}</span><ArrowRightIcon v-if="index < getSelectedStrategyPreview(pipeline.key).length-1" class="h-3 w-3 text-muted-foreground" /></template></div>
                    <p v-else class="text-muted-foreground">还没有选中策略，无法生成当前流水线的最终提交顺序。</p>
                  </div>
                </section>
              </div>
            </div>
          </template>
        </section>

        <section v-show="activeWorkbenchSection === 'execution'" ref="executionSectionRef" class="border-t border-border pt-5 mt-5" data-workbench-section="execution">
          <div class="mb-4 flex items-start justify-between gap-3">
            <div><h2 class="mt-1 text-lg font-semibold text-foreground">确认并构建</h2><p class="mt-0.5 text-[13px] text-muted-foreground">先确认策略方案，再执行构建索引，并在同一处查看执行轨迹。</p></div>
            <span class="inline-flex rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ executionSectionStatusText }}</span>
          </div>
          <div class="mb-4 grid grid-cols-3 gap-3 max-sm:grid-cols-1">
            <div class="grid gap-1 rounded-lg border border-border bg-secondary p-3.5"><span class="text-xs text-muted-foreground">策略确认</span><strong class="text-sm text-foreground">{{ confirmStepBadge }}</strong><p class="text-xs text-muted-foreground">{{ hasConfirmedStrategy ? '当前方案已进入确认流程。' : '还未完成最终确认。' }}</p></div>
            <div class="grid gap-1 rounded-lg border border-border bg-secondary p-3.5"><span class="text-xs text-muted-foreground">构建执行</span><strong class="text-sm text-foreground">{{ buildStepBadge }}</strong><p class="text-xs text-muted-foreground">{{ hasBuildInFlightStatus ? '系统正在执行构建，请留意下方轨迹。' : '确认完成后即可发起构建。' }}</p></div>
            <div class="grid gap-1 rounded-lg border border-border bg-secondary p-3.5"><span class="text-xs text-muted-foreground">当前任务</span><strong class="text-sm text-foreground">{{ activeBuildTaskId || documentDetail.latestTaskId || '-' }}</strong><p class="text-xs text-muted-foreground">{{ activeBuildStageLabel || '当前还没有正在执行的构建任务。' }}</p></div>
          </div>
          <div class="mb-4">
            <input v-model="adjustNote" class="mb-3 h-9 w-full rounded-md border border-border bg-card px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20" type="text" placeholder="补充说明，例如：增加大模型智能切块用于复杂段落" />
            <div class="grid grid-cols-2 gap-3 max-sm:grid-cols-1">
              <article class="grid gap-2 rounded-xl border p-4" :class="actionStageClass(confirmStepState)">
                <div class="flex items-center gap-2"><span class="rounded bg-foreground/[0.08] px-2 py-0.5 font-mono text-xs font-bold">01</span><span class="text-xs font-semibold text-muted-foreground">{{ confirmStepBadge }}</span></div>
                <strong class="text-sm text-foreground">先确认策略方案</strong><p class="text-xs text-muted-foreground">{{ confirmStepDescription }}</p>
                <button class="inline-flex items-center gap-2 rounded-full bg-primary px-4 py-2 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed" type="button" :disabled="!canConfirmStrategyAction" @click="submitConfirmStrategy"><span>{{ confirmButtonLabel }}</span><CheckCircleIcon class="h-4 w-4" /></button>
              </article>
              <article class="grid gap-2 rounded-xl border p-4" :class="actionStageClass(buildStepState)">
                <div class="flex items-center gap-2"><span class="rounded bg-foreground/[0.08] px-2 py-0.5 font-mono text-xs font-bold">02</span><span class="text-xs font-semibold text-muted-foreground">{{ buildStepBadge }}</span></div>
                <strong class="text-sm text-foreground">再执行构建索引</strong><p class="text-xs text-muted-foreground">{{ buildStepDescription }}</p>
                <button class="inline-flex items-center gap-2 rounded-full bg-primary px-4 py-2 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed" type="button" :disabled="!canBuildIndexAction" @click="submitBuildIndex"><span>{{ buildButtonLabel }}</span><ArrowRightIcon class="h-4 w-4" /></button>
              </article>
            </div>
          </div>
          <div v-if="showBuildTracker" ref="buildTrackerRef" class="rounded-xl border border-border bg-secondary p-4">
            <div class="mb-4 flex items-start justify-between gap-3">
              <div><strong class="block text-sm text-foreground">{{ buildTrackerTitle }}</strong><p class="mt-0.5 text-[13px] text-[var(--color-muted-strong)]">{{ buildTrackerDescription }}</p></div>
              <span class="inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-semibold" :class="isBuildPolling?'bg-[#0d7c7c]/10 text-[#0d7c7c]':'bg-foreground/[0.06] text-muted-foreground'">{{ isBuildPolling?'实时轮询中':'轨迹已保留' }}</span>
            </div>
            <div class="flex flex-col gap-2">
              <template v-for="(row,rowIndex) in buildStageRows" :key="`build-row-${rowIndex}`">
                <div class="flex items-stretch gap-2">
                  <article v-if="row.leftItem" class="flex flex-1 items-center gap-3 rounded-lg border p-3" :class="buildStageClass(row.leftItem.status)">
                    <div class="grid h-8 w-8 shrink-0 place-items-center rounded-full text-xs font-bold" :class="row.leftItem.status==='done'?'bg-[var(--color-success)] text-white':row.leftItem.status==='current'?'bg-primary text-white':'bg-foreground/[0.08] text-muted-foreground'"><span v-if="row.leftItem.status==='current'" class="stage-spinner" aria-hidden="true"></span><span v-else>{{ row.leftItem.order }}</span></div>
                    <div><strong class="block text-[13px] text-foreground">{{ row.leftItem.label }}</strong><em class="mt-0.5 block text-xs not-italic text-muted-foreground">{{ row.leftItem.statusLabel }}</em></div>
                  </article>
                  <div v-else class="flex-1"></div>
                  <div class="flex w-6 items-center justify-center text-xs text-muted-foreground">{{ row.leftItem && row.rightItem ? (row.direction==='rtl'?'←':'→') : '' }}</div>
                  <article v-if="row.rightItem" class="flex flex-1 items-center gap-3 rounded-lg border p-3" :class="buildStageClass(row.rightItem.status)">
                    <div class="grid h-8 w-8 shrink-0 place-items-center rounded-full text-xs font-bold" :class="row.rightItem.status==='done'?'bg-[var(--color-success)] text-white':row.rightItem.status==='current'?'bg-primary text-white':'bg-foreground/[0.08] text-muted-foreground'"><span v-if="row.rightItem.status==='current'" class="stage-spinner" aria-hidden="true"></span><span v-else>{{ row.rightItem.order }}</span></div>
                    <div><strong class="block text-[13px] text-foreground">{{ row.rightItem.label }}</strong><em class="mt-0.5 block text-xs not-italic text-muted-foreground">{{ row.rightItem.statusLabel }}</em></div>
                  </article>
                  <div v-else class="flex-1"></div>
                </div>
                <div v-if="rowIndex < buildStageRows.length-1" class="flex" :class="row.downColumn==='right'?'justify-end pr-8':row.downColumn==='left'?'justify-start pl-8':'justify-center'"><span class="text-sm text-muted-foreground">↓</span></div>
              </template>
            </div>
            <div class="mt-4 flex flex-wrap gap-4 border-t border-border pt-3 text-xs text-muted-foreground">
              <span>任务 {{ buildTaskSnapshot?.taskId || activeBuildTaskId || '-' }}</span>
              <span>状态 {{ buildTaskSnapshot?.taskStatusName || (hasCode(documentDetail.indexStatus, 3) ? '成功' : '未知') }}</span>
              <span>耗时 {{ formatDuration(buildTaskSnapshot?.costMillis) }}</span>
            </div>
          </div>
        </section>

        <section v-show="activeWorkbenchSection === 'chunk'" ref="chunkSectionRef" class="border-t border-border pt-5 mt-5" data-workbench-section="chunk">
          <div class="mb-4 flex items-start justify-between gap-3 max-md:flex-col">
            <div><h2 class="mt-1 text-lg font-semibold text-foreground">验证 Chunk 结果</h2><p class="mt-0.5 text-[13px] text-muted-foreground">在这里检查父子分块结构、分页浏览内容，并抽样验证切块是否符合预期。</p></div>
            <div class="flex flex-wrap items-center gap-2">
              <span class="inline-flex rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ chunkSectionStatusText }}</span>
              <span class="text-xs text-muted-foreground">{{ chunkQuery?.taskId ? `任务 ${chunkQuery.taskId} · ${chunkQuery.total||0} 条` : '当前还没有可展示的 chunk' }}</span>
              <div v-if="chunkRecords.length" class="flex gap-1 rounded-lg border border-border bg-secondary p-1">
                <button v-for="m in [{k:'grouped',l:'按父块分组'},{k:'flat',l:'平铺列表'}]" :key="m.k" class="rounded-md px-3 py-1.5 text-xs font-semibold transition-colors" :class="chunkDisplayMode===m.k?'bg-card text-foreground shadow-sm':'text-muted-foreground hover:text-foreground'" type="button" @click="chunkDisplayMode=m.k">{{ m.l }}</button>
                <button v-if="chunkDisplayMode==='grouped'" class="rounded-md px-2.5 py-1.5 text-xs font-semibold text-muted-foreground hover:text-foreground" type="button" @click="setAllChunkGroupsCollapsed(false)">展开全部</button>
                <button v-if="chunkDisplayMode==='grouped'" class="rounded-md px-2.5 py-1.5 text-xs font-semibold text-muted-foreground hover:text-foreground" type="button" @click="setAllChunkGroupsCollapsed(true)">折叠全部</button>
              </div>
            </div>
          </div>
          <div v-if="chunkLoading" class="py-6 text-center text-sm text-muted-foreground">正在加载 Chunk 列表...</div>
          <div v-else-if="!chunkRecords.length" class="rounded-md border border-dashed border-border py-6 text-center text-sm text-muted-foreground">当前文档还没有 Chunk 数据。请先完成索引构建，或等待构建任务继续执行。</div>
          <div v-else>
            <div class="mb-4 flex flex-wrap gap-2">
              <div v-for="s in chunkStats" :key="s.label" class="grid gap-1 rounded-lg border border-border bg-secondary px-4 py-3">
                <span class="text-xs text-muted-foreground">{{ s.label }}</span><strong class="text-sm text-foreground">{{ s.value }}</strong>
              </div>
            </div>
            <div v-if="chunkDisplayMode === 'grouped'" class="flex flex-col gap-3">
              <article v-for="group in chunkGroupedRecords" :key="`parent-group-${group.parentBlockId||group.parentBlockNo}`" class="rounded-lg border border-border bg-card overflow-hidden">
                <div class="flex items-start justify-between gap-3 p-4 max-md:flex-col">
                  <div><strong class="block text-sm text-foreground">父块 P#{{ group.parentBlockNo || '-' }}</strong><p class="mt-0.5 text-xs text-muted-foreground">{{ group.sectionPath || '未识别章节' }}</p></div>
                  <div class="flex flex-wrap items-center gap-2">
                    <div class="text-xs text-muted-foreground"><span>子块 {{ group.items.length }}/{{ group.parentChildCount||group.items.length }}</span><span class="ml-2">范围 C#{{ group.parentStartChunkNo||'-' }} - C#{{ group.parentEndChunkNo||'-' }}</span></div>
                    <button class="rounded-full border border-border bg-secondary px-3 py-1.5 text-xs font-semibold text-foreground hover:bg-card" type="button" @click="openParentBlockDetail(group)">查看父块上下文</button>
                    <button class="rounded-full border border-border bg-secondary px-3 py-1.5 text-xs font-semibold text-foreground hover:bg-card" type="button" @click="toggleChunkGroup(group.groupKey)">{{ isChunkGroupCollapsed(group.groupKey)?'展开子块':'折叠子块' }}</button>
                  </div>
                </div>
                <template v-if="!isChunkGroupCollapsed(group.groupKey)">
                  <div class="flex flex-wrap gap-2 border-t border-border bg-secondary p-3">
                    <button v-for="item in group.items" :key="`group-track-${item.chunkId}`" class="flex flex-col items-center rounded-lg border border-border bg-card px-3 py-2 text-center text-xs hover:border-primary/20" type="button" @click="openChunkDetail(item.chunkId)"><strong class="text-foreground">#{{ item.chunkNo||'-' }}</strong><span class="text-muted-foreground">{{ formatCount(item.tokenCount) }} T</span></button>
                  </div>
                  <div class="overflow-x-auto">
                    <table class="w-full min-w-[640px] border-collapse text-sm">
                      <thead><tr class="border-t border-border bg-secondary"><th v-for="h in chunkTableHeads" :key="h" class="px-4 py-3 text-left text-xs font-semibold text-muted-foreground">{{ h }}</th></tr></thead>
                      <tbody>
                        <tr v-for="item in group.items" :key="`group-row-${item.chunkId}`" class="cursor-pointer border-t border-border transition-colors hover:bg-primary/[0.03]" @click="openChunkDetail(item.chunkId)">
                          <td class="p-4"><strong class="block text-[13px] text-foreground">子块 C#{{ item.chunkNo }}</strong><span class="text-xs text-muted-foreground">{{ buildChunkRelationText(item) }}</span></td>
                          <td class="p-4"><strong class="block text-[13px] text-foreground">{{ item.sectionPath||'未识别章节' }}</strong><span class="text-xs text-muted-foreground">P#{{ item.parentBlockNo||'-' }} · 共 {{ item.parentChildCount||0 }} 子块</span></td>
                          <td class="p-4"><div class="flex flex-col gap-1"><span class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-foreground">{{ item.sourceTypeName||'未知来源' }}</span><span class="inline-flex rounded-full px-2.5 py-1 text-xs font-semibold" :class="chunkChipClass(normalizeCode(item.vectorStatus)||'0')">{{ item.vectorStatusName||'未知状态' }}</span></div></td>
                          <td class="p-4 text-sm font-semibold text-foreground">{{ formatCount(item.charCount) }}</td>
                          <td class="p-4 text-sm font-semibold text-foreground">{{ formatCount(item.tokenCount) }}</td>
                          <td class="p-4"><p class="line-clamp-3 text-[13px] text-foreground">{{ item.chunkText }}</p></td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </template>
              </article>
            </div>
            <div v-else class="overflow-x-auto rounded-lg border border-border">
              <table class="w-full min-w-[640px] border-collapse text-sm">
                <thead><tr class="bg-secondary"><th v-for="h in chunkTableHeads" :key="h" class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">{{ h }}</th></tr></thead>
                <tbody>
                  <tr v-for="item in chunkRecords" :key="item.chunkId" class="cursor-pointer border-b border-border transition-colors hover:bg-primary/[0.03] last:border-0" @click="openChunkDetail(item.chunkId)">
                    <td class="p-4"><strong class="block text-[13px] text-foreground">子块 C#{{ item.chunkNo }}</strong><span class="text-xs text-muted-foreground">{{ buildChunkRelationText(item) }}</span></td>
                    <td class="p-4"><strong class="block text-[13px] text-foreground">{{ item.sectionPath||'未识别章节' }}</strong><span class="text-xs text-muted-foreground">P#{{ item.parentBlockNo||'-' }} · 共 {{ item.parentChildCount||0 }} 子块</span></td>
                    <td class="p-4"><div class="flex flex-col gap-1"><span class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-foreground">{{ item.sourceTypeName||'未知来源' }}</span><span class="inline-flex rounded-full px-2.5 py-1 text-xs font-semibold" :class="chunkChipClass(normalizeCode(item.vectorStatus)||'0')">{{ item.vectorStatusName||'未知状态' }}</span></div></td>
                    <td class="p-4 text-sm font-semibold text-foreground">{{ formatCount(item.charCount) }}</td>
                    <td class="p-4 text-sm font-semibold text-foreground">{{ formatCount(item.tokenCount) }}</td>
                    <td class="p-4"><p class="line-clamp-3 text-[13px] text-foreground">{{ item.chunkText }}</p></td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="mt-4 flex items-center justify-between gap-3 max-sm:flex-col">
              <button class="rounded-full border border-border bg-card px-4 py-2 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-50" type="button" :disabled="chunkCurrentPage<=1||chunkLoading" @click="changeChunkPage(chunkCurrentPage-1)">上一页</button>
              <div class="flex flex-wrap items-center gap-3 text-sm">
                <label class="flex items-center gap-2 text-muted-foreground">每页显示<select class="rounded-md border border-border bg-card px-2 py-1 text-foreground focus:outline-none" :value="chunkCurrentPageSize" :disabled="chunkLoading" @change="changeChunkPageSize($event.target.value)"><option v-for="size in chunkPageSizeOptions" :key="size" :value="size">{{ size }} 条</option></select></label>
                <strong class="text-foreground">第 {{ chunkCurrentPage }} / {{ chunkTotalPages }} 页</strong>
                <span class="text-muted-foreground">共 {{ chunkTotalCount }} 条</span>
              </div>
              <button class="rounded-full border border-border bg-card px-4 py-2 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-50" type="button" :disabled="chunkCurrentPage>=chunkTotalPages||chunkLoading" @click="changeChunkPage(chunkCurrentPage+1)">下一页</button>
            </div>
          </div>
        </section>

        <section v-show="activeWorkbenchSection === 'rag'" ref="ragSectionRef" class="border-t border-border pt-5 mt-5" data-workbench-section="rag">
          <div class="mb-4 flex items-start justify-between gap-3 max-md:flex-col">
            <div>
              <h2 class="mt-1 text-lg font-semibold text-foreground">RAG 学习视图</h2>
              <p class="mt-0.5 text-[13px] text-muted-foreground">把文档侧索引产物按 RAG 阶段摊开看：解析块、结构图、父子切块、表格、GraphRAG、RAPTOR 都在这里分开展示。</p>
            </div>
            <div class="flex flex-wrap items-center gap-2">
              <span class="inline-flex rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ ragSectionStatusText }}</span>
              <span class="text-xs text-muted-foreground">解析任务 {{ ragSnapshot?.parseTaskId || '-' }} · 索引任务 {{ ragSnapshot?.indexTaskId || '-' }}</span>
              <button class="rounded-full border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60" type="button" :disabled="ragSnapshotLoading" @click="loadDocumentRagSnapshot">{{ ragSnapshotLoading ? '读取中...' : '刷新快照' }}</button>
            </div>
          </div>

          <div v-if="ragSnapshotLoading" class="py-6 text-center text-sm text-muted-foreground">正在读取 RAG 学习快照...</div>
          <div v-else-if="!ragSnapshot" class="rounded-md border border-dashed border-border py-6 text-center text-sm text-muted-foreground">当前还没有可展示的 RAG 快照。请先完成文档解析和索引构建。</div>
          <div v-else class="grid gap-4">
            <div class="rounded-lg border border-primary/20 bg-primary/[0.04] p-4">
              <div class="mb-2 flex flex-wrap gap-3 text-xs text-muted-foreground">
                <span>文档 {{ ragSnapshot.documentName || documentDetail.documentName }}</span>
                <span>方案 {{ ragSnapshot.planId || '-' }}</span>
                <span>样例 {{ formatCount(ragArtifactSampleCount) }} 条</span>
              </div>
              <p class="text-[13px] text-[var(--color-muted-strong)]">{{ ragSnapshot.runtimeObservationNote }}</p>
              <div v-if="hasRagTableHighlight" class="mt-3 rounded-md border border-primary/20 bg-card px-3 py-2 text-xs text-primary">
                已从问答引用定位到表格证据：{{ ragTableHighlightSummary }}
              </div>
            </div>

            <div class="grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(150px,1fr))">
              <article v-for="metric in ragSnapshotMetrics" :key="`rag-metric-${metric.label}`" class="grid gap-1 rounded-lg border p-3.5" :class="ragMetricClass(metric.tone)">
                <span class="text-xs text-muted-foreground">{{ metric.label }}</span>
                <strong class="text-base text-foreground">{{ metric.value }}</strong>
                <p class="text-xs text-muted-foreground">{{ metric.hint }}</p>
              </article>
            </div>

            <div class="rounded-lg border border-border bg-secondary p-4">
              <div class="mb-3 flex items-center justify-between gap-2">
                <div>
                  <h3 class="text-sm font-semibold text-foreground">索引产物流转</h3>
                  <p class="mt-0.5 text-xs text-muted-foreground">这里展示的是文档侧静态产物，问答运行时链路仍然去对话观测页查看。</p>
                </div>
              </div>
              <div class="grid gap-2" style="grid-template-columns:repeat(auto-fit,minmax(210px,1fr))">
                <article v-for="stage in ragPipelineStages" :key="`rag-stage-${stage.code}`" class="rounded-lg border p-3" :class="ragStageClass(stage.statusText)">
                  <div class="mb-1 flex items-center justify-between gap-2">
                    <strong class="text-[13px] text-foreground">{{ stage.title }}</strong>
                    <span class="rounded-full bg-card px-2 py-0.5 text-[11px] text-muted-foreground">{{ stage.statusText }}</span>
                  </div>
                  <p class="text-xs text-muted-foreground">{{ stage.description }}</p>
                  <span class="mt-2 inline-flex rounded bg-foreground/[0.06] px-2 py-0.5 text-[11px] text-foreground">数量 {{ formatCount(stage.count) }}</span>
                </article>
              </div>
            </div>

            <article v-for="section in ragArtifactSections" :key="`rag-artifact-${section.key}`" class="rounded-lg border border-border bg-card p-4">
              <div class="mb-3 flex items-start justify-between gap-3 max-md:flex-col">
                <div>
                  <h3 class="text-sm font-semibold text-foreground">{{ section.title }}</h3>
                  <p class="mt-0.5 text-xs text-muted-foreground">{{ section.caption }}</p>
                </div>
                <span class="inline-flex rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ section.records.length }} 条样例</span>
              </div>
              <div v-if="!section.records.length" class="rounded-md border border-dashed border-border bg-secondary py-5 text-center text-sm text-muted-foreground">{{ section.emptyText }}</div>
              <div v-else-if="section.key === 'table'" class="grid gap-3">
                <article v-for="(record, index) in section.records" :key="`${section.key}-${index}`"
                  class="rounded-lg border bg-secondary p-3"
                  :class="isHighlightedTable(record) ? 'border-primary/40 ring-2 ring-primary/10' : 'border-border'">
                  <div class="mb-3 flex items-start justify-between gap-3 max-md:flex-col">
                    <div>
                      <div class="flex flex-wrap items-center gap-1.5">
                        <strong class="text-[13px] text-foreground">{{ record.title }}</strong>
                        <span v-if="isHighlightedTable(record)" class="rounded-full bg-primary/[0.08] px-2 py-0.5 text-[11px] font-semibold text-primary">引用命中</span>
                      </div>
                      <p class="mt-0.5 text-xs text-muted-foreground">{{ record.subtitle }}</p>
                    </div>
                    <div class="flex flex-wrap gap-1.5">
                      <span v-for="chip in record.chips.filter(Boolean).slice(0,4)" :key="`${section.key}-${index}-chip-${chip}`" class="rounded-full bg-card px-2 py-0.5 text-[11px] text-foreground">{{ chip }}</span>
                    </div>
                  </div>
                  <p v-if="record.meta?.length" class="mb-3 text-[11px] text-muted-foreground">{{ record.meta.join(' · ') }}</p>
                  <div class="overflow-x-auto rounded-md border border-border bg-card">
                    <table class="w-full min-w-[640px] border-collapse text-sm">
                      <thead>
                        <tr class="bg-secondary">
                          <th class="border-b border-border px-3 py-2 text-left text-xs font-semibold text-muted-foreground">行号</th>
                          <th v-for="column in record.columns" :key="`${record.tableId}-col-${column.columnNo}`"
                            class="border-b border-border px-3 py-2 text-left text-xs font-semibold"
                            :class="isHighlightedColumn(record, column) ? 'text-primary' : 'text-muted-foreground'">
                            {{ column.columnName || column.normalizedName || `C#${column.columnNo || '-'}` }}
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="row in record.rows" :key="`${record.tableId}-row-${row.rowId || row.rowNo}`"
                          class="border-b border-border last:border-0"
                          :class="isHighlightedTableRow(record, row) ? 'bg-primary/[0.04]' : ''">
                          <td class="whitespace-nowrap px-3 py-2 text-xs font-semibold" :class="isHighlightedTableRow(record, row) ? 'text-primary' : 'text-muted-foreground'">R#{{ row.rowNo || '-' }}</td>
                          <td v-for="(column, ci) in record.columns" :key="`${record.tableId}-row-${row.rowId || row.rowNo}-cell-${column.columnNo || ci}`"
                            class="px-3 py-2 text-[13px] transition-colors"
                            :class="tableCellClass(record, row, cellForColumn(row, column, ci), column)">
                            <div class="flex min-w-[120px] flex-col gap-0.5">
                              <span class="break-words">{{ cellForColumn(row, column, ci)?.cellText || cellForColumn(row, column, ci)?.text || '-' }}</span>
                              <span v-if="cellForColumn(row, column, ci)?.sourceCellRef || cellForColumn(row, column, ci)?.bboxJson" class="text-[11px]" :class="isHighlightedTableCell(record, row, cellForColumn(row, column, ci), column) ? 'text-primary' : 'text-muted-foreground'">
                                {{ compactList([cellForColumn(row, column, ci)?.sourceCellRef, cellForColumn(row, column, ci)?.bboxJson ? 'bbox' : '']).join(' · ') }}
                              </span>
                            </div>
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                  <p v-if="record.sampleNote" class="mt-2 text-[11px] text-muted-foreground">{{ record.sampleNote }}</p>
                </article>
              </div>
              <div v-else class="grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(260px,1fr))">
                <article v-for="(record, index) in section.records" :key="`${section.key}-${index}`" class="grid gap-2 rounded-lg border border-border bg-secondary p-3">
                  <div>
                    <strong class="block text-[13px] text-foreground">{{ record.title }}</strong>
                    <p class="mt-0.5 line-clamp-1 text-xs text-muted-foreground">{{ record.subtitle }}</p>
                  </div>
                  <div v-if="record.chips?.length" class="flex flex-wrap gap-1.5">
                    <span v-for="chip in record.chips.filter(Boolean).slice(0,4)" :key="`${section.key}-${index}-chip-${chip}`" class="rounded-full bg-card px-2 py-0.5 text-[11px] text-foreground">{{ chip }}</span>
                  </div>
                  <p v-if="record.meta?.length" class="line-clamp-2 text-[11px] text-muted-foreground">{{ record.meta.join(' · ') }}</p>
                  <p class="line-clamp-4 whitespace-pre-wrap break-words text-[13px] text-foreground">{{ record.body }}</p>
                  <div v-if="record.lines?.length" class="grid gap-1 border-t border-border pt-2">
                    <p v-for="line in record.lines.slice(0,3)" :key="`${section.key}-${index}-line-${line}`" class="line-clamp-1 text-xs text-muted-foreground">{{ line }}</p>
                  </div>
                </article>
              </div>
            </article>
          </div>
        </section>

        <section v-show="activeWorkbenchSection === 'tasks'" ref="taskSectionRef" class="border-t border-border pt-5 mt-5" data-workbench-section="tasks">
          <div class="mb-4 flex items-start justify-between gap-3">
            <div><h2 class="mt-1 text-lg font-semibold text-foreground">查看任务记录</h2><p class="mt-0.5 text-[13px] text-muted-foreground">通过最近任务摘要和完整时间线快速复盘当前文档的执行过程与异常信息。</p></div>
            <div class="flex items-center gap-2">
              <span class="inline-flex rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ taskSectionStatusText }}</span>
              <button class="rounded-full border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60" type="button" :disabled="!documentDetail.latestTaskId" @click="openLogDrawer">查看完整任务时间线</button>
            </div>
          </div>
          <div v-if="logLoading" class="py-6 text-center text-sm text-muted-foreground">正在加载任务日志...</div>
          <div v-else-if="!taskLogs.length" class="rounded-md border border-dashed border-border py-6 text-center text-sm text-muted-foreground">当前文档还没有可查看的任务日志。</div>
          <div v-else class="flex flex-col gap-3">
            <article v-for="log in taskLogs.slice(0,3)" :key="log.id" class="rounded-lg border border-border bg-secondary p-3.5">
              <div class="mb-1 flex items-center justify-between gap-2 text-xs"><strong class="text-foreground">{{ log.stageTypeName }} · {{ log.eventTypeName }}</strong><span class="text-muted-foreground">{{ formatDateTime(log.createTime) }}</span></div>
              <p class="text-[13px] text-[var(--color-muted-strong)]">{{ log.content }}</p>
            </article>
          </div>
        </section>
      </div>
    </article>

    <div v-else class="rounded-md border border-dashed border-border py-10 text-center text-sm text-muted-foreground">正在加载文档详情...</div>
  </section>
</template>
<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeftIcon, ArrowRightIcon, CheckCircleIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import { APIError, manageApi } from '../../api/api'
import AdminStatusBadge from '../../components/admin/AdminStatusBadge.vue'
import { formatCount, formatDateTime, hasCode, normalizeCode } from '../../utils/manageFormat'
import {
  STRATEGY_LIBRARY,
  STRATEGY_PIPELINE_LIBRARY,
  buildPipelineStepPayload,
  buildStrategyPreview,
  buildStrategySignature,
  extractPipelineStrategyTypes,
  normalizeStrategyTypeList,
  resolvePlanPipeline
} from '../../utils/documentStrategyPipeline'

const route = useRoute()
const router = useRouter()
const OPERATOR_ID = '10001'
const DEFAULT_CHUNK_PAGE_SIZE = 20
const CHUNK_PAGE_SIZE_OPTIONS = [10, 20, 50, 100]
const WORKBENCH_SECTION_KEYS = ['overview', 'strategy', 'execution', 'chunk', 'rag', 'tasks']

const strategyLibrary = STRATEGY_LIBRARY
const strategyPipelineLibrary = STRATEGY_PIPELINE_LIBRARY

const BUILD_STAGE_LIBRARY = [
  { code: '5', order: '01', label: '切块执行', description: '按照当前策略链路生成原始 chunk' },
  { code: '6', order: '02', label: '切块后处理', description: '清洗空块并整理最终可入库片段' },
  { code: '7', order: '03', label: '向量化', description: '生成 embedding 并写入 PGVector' },
  { code: '8', order: '04', label: '入库完成', description: '回写状态并将本次索引标记为可用' }
]

const BUILD_STAGE_CODE_SET = new Set(BUILD_STAGE_LIBRARY.map((item) => item.code))

const documentDetail = ref(null)
const strategyPlan = ref(null)
const selectedParentStrategyTypes = ref([])
const selectedChildStrategyTypes = ref([])
const adjustNote = ref('')
const taskLogs = ref([])
const taskLogSnapshot = ref(null)
const buildTaskSnapshot = ref(null)
const ragSnapshot = ref(null)
const chunkQuery = ref(null)
const chunkDetail = ref(null)
const chunkDisplayMode = ref('grouped')
const chunkGroupCollapsedMap = ref({})
const chunkPageNo = ref(1)
const chunkPageSize = ref(DEFAULT_CHUNK_PAGE_SIZE)
const loading = ref(false)
const planLoading = ref(false)
const confirmLoading = ref(false)
const buildLoading = ref(false)
const logLoading = ref(false)
const chunkLoading = ref(false)
const chunkDetailLoading = ref(false)
const ragSnapshotLoading = ref(false)
const logDrawerOpen = ref(false)
const chunkDetailDrawerOpen = ref(false)
const planPollTimer = ref(null)
const buildPollTimer = ref(null)
const buildTrackerRef = ref(null)
const parentBlockSectionRef = ref(null)
const overviewSectionRef = ref(null)
const strategySectionRef = ref(null)
const executionSectionRef = ref(null)
const chunkSectionRef = ref(null)
const ragSectionRef = ref(null)
const taskSectionRef = ref(null)
const chunkDetailFocusMode = ref('chunk')
const activeWorkbenchSection = ref('overview')
const pageNotice = reactive({
  type: 'info',
  message: ''
})

const documentId = computed(() => String(route.params.documentId || ''))
const ragTableHighlightSpec = computed(() => buildRagTableHighlightSpec(route.query || {}))
const hasRagTableHighlight = computed(() => ragTableHighlightSpec.value.active)
const ragTableHighlightSummary = computed(() => {
  const spec = ragTableHighlightSpec.value
  return compactList([
    spec.tableId ? `表 ${spec.tableId}` : (spec.tableNo ? `T#${spec.tableNo}` : ''),
    spec.rowNos.length ? `行 ${spec.rowNos.join('、')}` : '',
    spec.columnNames.length ? `列 ${spec.columnNames.join('、')}` : '',
    spec.cellCoordinates.length ? `单元格 ${spec.cellCoordinates.join('、')}` : ''
  ]).join(' / ') || '已定位表格证据'
})
const showOriginalFileName = computed(() => {
  const documentName = String(documentDetail.value?.documentName || '').trim()
  const originalFileName = String(documentDetail.value?.originalFileName || '').trim()
  return Boolean(originalFileName) && originalFileName !== documentName
})
const isBuildPolling = computed(() => buildPollTimer.value != null)
const selectedParentStrategyPreview = computed(() => buildStrategyPreview(selectedParentStrategyTypes.value, strategyLibrary))
const selectedChildStrategyPreview = computed(() => buildStrategyPreview(selectedChildStrategyTypes.value, strategyLibrary))
const selectedParentStrategyRows = computed(() => buildSequenceRows(selectedParentStrategyPreview.value))
const selectedChildStrategyRows = computed(() => buildSequenceRows(selectedChildStrategyPreview.value))
const confirmedParentStrategyTypes = computed(() => extractPipelineStrategyTypes(strategyPlan.value?.plan, 'parent', strategyLibrary))
const confirmedChildStrategyTypes = computed(() => extractPipelineStrategyTypes(strategyPlan.value?.plan, 'child', strategyLibrary))
const chunkRecords = computed(() => Array.isArray(chunkQuery.value?.records) ? chunkQuery.value.records : [])
const chunkTotalCount = computed(() => Number(chunkQuery.value?.total || chunkRecords.value.length || 0))
const chunkCurrentPage = computed(() => Number(chunkQuery.value?.pageNo || chunkPageNo.value || 1))
const chunkCurrentPageSize = computed(() => Number(chunkQuery.value?.pageSize || chunkPageSize.value || DEFAULT_CHUNK_PAGE_SIZE))
const chunkPageSizeOptions = computed(() => {
  return Array.from(new Set([...CHUNK_PAGE_SIZE_OPTIONS, chunkCurrentPageSize.value]))
    .sort((left, right) => left - right)
})
const chunkTotalPages = computed(() => {
  return Math.max(1, Math.ceil(chunkTotalCount.value / Math.max(1, chunkCurrentPageSize.value)))
})
const chunkParentCount = computed(() => {
  return new Set(
    chunkRecords.value
      .map((item) => normalizeCode(item.parentBlockId))
      .filter(Boolean)
  ).size
})
const chunkVectorReadyCount = computed(() => {
  return chunkRecords.value.filter((item) => normalizeCode(item.vectorStatus) === '3').length
})
const chunkVectorPendingCount = computed(() => {
  return chunkRecords.value.filter((item) => normalizeCode(item.vectorStatus) !== '3').length
})
const chunkAverageTokens = computed(() => {
  if (!chunkRecords.value.length) {
    return 0
  }

  const totalTokens = chunkRecords.value.reduce((sum, item) => sum + Number(item.tokenCount || 0), 0)
  return Math.round(totalTokens / chunkRecords.value.length)
})
const chunkGroupedRecords = computed(() => {
  const groupMap = new Map()
  chunkRecords.value.forEach((item) => {
    const parentKey = normalizeCode(item.parentBlockId) || `unbound-${normalizeCode(item.chunkId)}`
    if (!groupMap.has(parentKey)) {
      groupMap.set(parentKey, {
        parentBlockId: item.parentBlockId,
        parentBlockNo: item.parentBlockNo,
        parentChildCount: item.parentChildCount,
        parentStartChunkNo: item.parentStartChunkNo,
        parentEndChunkNo: item.parentEndChunkNo,
        sectionPath: item.sectionPath,
        items: []
      })
    }
    groupMap.get(parentKey).items.push(item)
  })
  return Array.from(groupMap.values())
    .map((group) => ({
      ...group,
      groupKey: normalizeCode(group.parentBlockId) || `unbound-${normalizeCode(group.items[0]?.chunkId)}`,
      items: [...group.items].sort((left, right) => Number(left.chunkNo || 0) - Number(right.chunkNo || 0))
    }))
    .sort((left, right) => Number(left.parentBlockNo || 0) - Number(right.parentBlockNo || 0))
})
const ragSnapshotMetrics = computed(() => asArray(ragSnapshot.value?.metrics))
const ragPipelineStages = computed(() => asArray(ragSnapshot.value?.pipelineStages))
const ragArtifactSampleCount = computed(() => {
  return [
    'parseBlocks',
    'structureNodes',
    'parentBlocks',
    'chunks',
    'tables',
    'kgEntities',
    'kgRelations',
    'kgCommunities',
    'raptorNodes',
    'buildLogs'
  ].reduce((sum, key) => sum + asArray(ragSnapshot.value?.[key]).length, 0)
})
const ragArtifactSections = computed(() => {
  const snapshot = ragSnapshot.value || {}
  return [
    {
      key: 'parse',
      title: '解析块样例',
      caption: 'Python rag-tools 解析后交给 Java 入库的原始结构块。',
      emptyText: '当前没有解析块样例。',
      records: asArray(snapshot.parseBlocks).map((item) => ({
        title: `Block #${item.blockNo || '-'}`,
        subtitle: item.sectionPath || '未识别章节',
        chips: [item.blockType || 'block', item.pageRange || (item.pageNo ? `第 ${item.pageNo} 页` : '')],
        meta: compactList([item.bboxJson ? '有 bbox' : '', item.blockId ? `ID ${item.blockId}` : '']),
        body: item.textPreview || '暂无文本预览'
      }))
    },
    {
      key: 'structure',
      title: '文档结构图',
      caption: 'Document -> Section -> Item 层级，只负责目录、章节、条目导航。',
      emptyText: '当前没有文档结构节点样例。',
      records: asArray(snapshot.structureNodes).map((item) => ({
        title: item.title || `节点 #${item.nodeNo || '-'}`,
        subtitle: item.sectionPath || item.nodeCode || '未识别路径',
        chips: [`深度 ${valueOrDash(item.depth)}`, item.nodeCode || ''],
        meta: compactList([item.nodeType ? `类型 ${item.nodeType}` : '', item.nodeId ? `ID ${item.nodeId}` : '']),
        body: item.anchorText || '暂无锚点文本'
      }))
    },
    {
      key: 'parent',
      title: 'ParentBlock',
      caption: '父块用于回答上下文，相当于 RAGFlow mother chunk 思想。',
      emptyText: '当前没有父块样例。',
      records: asArray(snapshot.parentBlocks).map((item) => ({
        title: `父块 P#${item.parentNo || '-'}`,
        subtitle: item.sectionPath || '未识别章节',
        chips: [`子块 ${formatCount(item.childCount)}`, `C#${valueOrDash(item.startChunkNo)} - C#${valueOrDash(item.endChunkNo)}`],
        meta: compactList([item.pageRange, item.parentBlockId ? `ID ${item.parentBlockId}` : '']),
        body: item.textPreview || '暂无父块预览'
      }))
    },
    {
      key: 'chunk',
      title: 'ChildChunk',
      caption: '子块用于召回和向量/BM25 检索，命中后再提升到父块组织证据。',
      emptyText: '当前没有子块样例。',
      records: asArray(snapshot.chunks).map((item) => ({
        title: `子块 C#${item.chunkNo || '-'}`,
        subtitle: item.title || item.sectionPath || '未识别章节',
        chips: [item.vectorStatusName || '向量状态未知', item.chunkType || 'chunk', `${formatCount(item.tokenCount)} Token`],
        meta: compactList([item.pageRange, item.keywords ? `关键词：${item.keywords}` : '', item.sourceBlockIds ? '有源 block' : '']),
        body: item.textPreview || '暂无子块预览'
      }))
    },
    {
      key: 'table',
      title: '表格结构',
      caption: '表格拆成表、列、行、单元格，用于结构化问答和字段过滤。',
      emptyText: '当前没有表格结构样例。',
      records: asArray(snapshot.tables).map((item) => ({
        tableId: item.tableId,
        tableNo: item.tableNo,
        title: `表格 T#${item.tableNo || '-'}`,
        subtitle: item.title || item.sectionPath || '未命名表格',
        chips: [`${formatCount(item.rowCount)} 行`, `${formatCount(item.columnCount)} 列`, item.pageRange || ''],
        meta: compactList([item.tableId ? `ID ${item.tableId}` : '', item.pageNo ? `第 ${item.pageNo} 页` : '', item.bboxJson ? '有表格 bbox' : '']),
        body: asArray(item.columns).map((column) => column.columnName || column.normalizedName).filter(Boolean).join(' / ') || '暂无列信息',
        columns: asArray(item.columns),
        rows: normalizeTableRows(item),
        sampleNote: Number(item.rowCount || 0) > asArray(item.rows).length
          ? `当前展示 ${asArray(item.rows).length} 行样例；如果从问答引用跳转，命中行会额外并入样例。`
          : '',
        lines: asArray(item.rows).map((row) => `R#${row.rowNo || '-'} ${asArray(row.cells).filter(Boolean).join(' | ') || row.rowText || ''}`)
      }))
    },
    {
      key: 'kg-entity',
      title: 'GraphRAG 实体',
      caption: '实体属于独立 KG，不和文档结构图混用。',
      emptyText: '当前没有 GraphRAG 实体样例。',
      records: asArray(snapshot.kgEntities).map((item) => ({
        title: item.name || `实体 ${item.entityId || '-'}`,
        subtitle: item.entityType || '未分类实体',
        chips: compactList([item.entityType]),
        meta: compactList([item.entityId ? `ID ${item.entityId}` : '']),
        body: item.description || '暂无实体说明'
      }))
    },
    {
      key: 'kg-relation',
      title: 'GraphRAG 关系',
      caption: '关系连接实体，并通过 evidence 回到原文 chunk。',
      emptyText: '当前没有 GraphRAG 关系样例。',
      records: asArray(snapshot.kgRelations).map((item) => ({
        title: `${item.sourceName || item.sourceEntityId || '-'} -> ${item.targetName || item.targetEntityId || '-'}`,
        subtitle: item.relationType || '未分类关系',
        chips: compactList([item.relationType, item.weight ? `权重 ${item.weight}` : '']),
        meta: compactList([item.relationId ? `ID ${item.relationId}` : '']),
        body: item.description || '暂无关系说明'
      }))
    },
    {
      key: 'kg-community',
      title: 'GraphRAG 社区',
      caption: '社区摘要用于跨实体导航和全局问题召回。',
      emptyText: '当前没有 GraphRAG 社区样例。',
      records: asArray(snapshot.kgCommunities).map((item) => ({
        title: item.title || `社区 #${item.communityNo || '-'}`,
        subtitle: item.entityIdsJson || '暂无实体列表',
        chips: [`社区 ${valueOrDash(item.communityNo)}`],
        meta: compactList([item.communityId ? `ID ${item.communityId}` : '']),
        body: item.summary || '暂无社区摘要'
      }))
    },
    {
      key: 'raptor',
      title: 'RAPTOR 摘要树',
      caption: '摘要节点只用于导航召回，最终证据必须回到原始 chunk。',
      emptyText: '当前没有 RAPTOR 摘要节点样例。',
      records: asArray(snapshot.raptorNodes).map((item) => ({
        title: item.title || `RAPTOR N#${item.nodeNo || '-'}`,
        subtitle: item.sectionPath || `Level ${valueOrDash(item.nodeLevel)}`,
        chips: [`L${valueOrDash(item.nodeLevel)}`, item.pageRange || '', item.keywords || ''],
        meta: compactList([item.sourceChunkIdsJson ? `源 chunk ${item.sourceChunkIdsJson}` : '', item.nodeId ? `ID ${item.nodeId}` : '']),
        body: item.summary || '暂无摘要'
      }))
    },
    {
      key: 'build-log',
      title: '构建日志样例',
      caption: '这里展示索引构建任务的关键日志，完整日志仍在任务记录里查看。',
      emptyText: '当前没有构建日志样例。',
      records: asArray(snapshot.buildLogs).map((item) => ({
        title: `${item.stageTypeName || '阶段'} · ${item.eventTypeName || '事件'}`,
        subtitle: formatDateTime(item.createTime),
        chips: compactList([item.logLevelName]),
        meta: compactList([item.id ? `ID ${item.id}` : '']),
        body: item.content || item.detailJson || '暂无日志内容'
      }))
    }
  ]
})
const hasBuildTaskSnapshot = computed(() => hasCode(buildTaskSnapshot.value?.taskType, 2))
const activeBuildTaskId = computed(() => {
  if (hasCode(documentDetail.value?.latestTaskType, 2)) {
    return documentDetail.value?.latestTaskId || ''
  }
  return documentDetail.value?.lastIndexTaskId || ''
})
const hasSelectedStrategy = computed(() => selectedParentStrategyPreview.value.length > 0 && selectedChildStrategyPreview.value.length > 0)
const hasConfirmedStrategy = computed(() => Boolean(documentDetail.value?.currentPlanId) && hasCode(documentDetail.value?.strategyStatus, 3))
const hasUnconfirmedStrategyChanges = computed(() => {
  return buildStrategySignature(selectedParentStrategyTypes.value, strategyLibrary) !== buildStrategySignature(confirmedParentStrategyTypes.value, strategyLibrary)
    || buildStrategySignature(selectedChildStrategyTypes.value, strategyLibrary) !== buildStrategySignature(confirmedChildStrategyTypes.value, strategyLibrary)
    || Boolean(adjustNote.value.trim())
})
const hasBuildInFlightStatus = computed(() => {
  const taskStatus = normalizeCode(buildTaskSnapshot.value?.taskStatus)
  return buildLoading.value
    || taskStatus === '1'
    || taskStatus === '2'
    || hasCode(documentDetail.value?.indexStatus, 2)
    || (hasCode(documentDetail.value?.latestTaskType, 2) && ['1', '2'].includes(normalizeCode(documentDetail.value?.latestTaskStatus)))
})
const showBuildBlockingOverlay = computed(() => hasBuildInFlightStatus.value)

const showBuildTracker = computed(() => {
  return Boolean(activeBuildTaskId.value) || hasBuildTaskSnapshot.value
})

const activeBuildStageLabel = computed(() => {
  const currentStageItem = buildStageItems.value.find((item) => item.status === 'current')
  if (currentStageItem) {
    return currentStageItem.label
  }
  if (hasBuildInFlightStatus.value) {
    return buildTaskSnapshot.value?.currentStageName || BUILD_STAGE_LIBRARY[0].label
  }
  if ((hasBuildTaskSnapshot.value && hasCode(buildTaskSnapshot.value?.taskStatus, 3)) || hasCode(documentDetail.value?.indexStatus, 3)) {
    return BUILD_STAGE_LIBRARY[BUILD_STAGE_LIBRARY.length - 1]?.label || '入库完成'
  }
  return ''
})

const canConfirmStrategyAction = computed(() => {
  return hasSelectedStrategy.value
    && !confirmLoading.value
    && !hasBuildInFlightStatus.value
    && (!hasConfirmedStrategy.value || hasUnconfirmedStrategyChanges.value)
})

const canBuildIndexAction = computed(() => {
  return hasSelectedStrategy.value
    && hasConfirmedStrategy.value
    && !hasUnconfirmedStrategyChanges.value
    && !hasBuildInFlightStatus.value
})

const confirmStepState = computed(() => {
  if (confirmLoading.value) {
    return 'current'
  }
  if (!hasSelectedStrategy.value) {
    return 'locked'
  }
  if (hasConfirmedStrategy.value && !hasUnconfirmedStrategyChanges.value) {
    return 'completed'
  }
  return 'ready'
})

const buildStepState = computed(() => {
  if (buildLoading.value || hasBuildInFlightStatus.value) {
    return 'current'
  }
  if (!hasSelectedStrategy.value || !hasConfirmedStrategy.value || hasUnconfirmedStrategyChanges.value) {
    return 'locked'
  }
  return 'ready'
})

const strategySystemStages = computed(() => {
  const parseStatus = normalizeCode(documentDetail.value?.parseStatus)
  const parseFailed = parseStatus === '4'
  const parseReady = parseStatus === '3'
  const parentReady = Boolean(resolvePlanPipeline(strategyPlan.value?.plan, 'parent')?.steps?.length)
  const childReady = Boolean(resolvePlanPipeline(strategyPlan.value?.plan, 'child')?.steps?.length)
  const confirmed = hasConfirmedStrategy.value

  return [
    {
      code: 'parse',
      order: '01',
      label: '解析完成',
      description: parseFailed ? '解析失败，无法继续推荐。' : parseReady ? '文本已解析，可进入推荐阶段。' : '正在等待文档解析结果。',
      status: parseFailed ? 'failed' : parseReady ? 'completed' : 'pending'
    },
    {
      code: 'parent',
      order: '02',
      label: '父流水线生成',
      description: parentReady ? '系统已生成回答阶段父块边界。' : parseReady ? '正在生成父块推荐。' : '等待解析完成后生成。',
      status: parseFailed ? 'failed' : parentReady ? 'completed' : parseReady ? 'current' : 'pending'
    },
    {
      code: 'child',
      order: '03',
      label: '子流水线生成',
      description: childReady ? '系统已生成检索阶段子块边界。' : parentReady ? '正在生成子块推荐。' : '等待父流水线准备完成。',
      status: parseFailed ? 'failed' : childReady ? 'completed' : parentReady ? 'current' : 'pending'
    },
    {
      code: 'confirm',
      order: '04',
      label: confirmed ? '方案已确认' : '等待人工确认',
      description: confirmed ? '当前双流水线已成为生效方案。' : childReady ? '系统推荐已完成，请人工确认。' : '待系统完成推荐后再确认。',
      status: parseFailed ? 'failed' : confirmed ? 'completed' : childReady ? 'current' : 'pending'
    }
  ]
})

const confirmStepBadge = computed(() => {
  if (confirmLoading.value) {
    return '确认中'
  }
  if (!hasSelectedStrategy.value) {
    return '请先选择'
  }
  if (hasConfirmedStrategy.value && !hasUnconfirmedStrategyChanges.value) {
    return '已确认'
  }
  if (hasConfirmedStrategy.value && hasUnconfirmedStrategyChanges.value) {
    return '待重新确认'
  }
  return '待确认'
})

const buildStepBadge = computed(() => {
  if (buildLoading.value) {
    return '启动中'
  }
  if (hasBuildInFlightStatus.value) {
    return activeBuildStageLabel.value || '执行中'
  }
  if (!hasSelectedStrategy.value || !hasConfirmedStrategy.value) {
    return '已锁定'
  }
  if (hasUnconfirmedStrategyChanges.value) {
    return '待重新确认'
  }
  return hasCode(documentDetail.value?.indexStatus, 3) ? '可再次执行' : '已解锁'
})

const confirmStepDescription = computed(() => {
  if (!hasSelectedStrategy.value) {
    return '请先分别完成父块流水线和子块流水线配置，再提交这次最终执行方案。'
  }
  if (hasConfirmedStrategy.value && !hasUnconfirmedStrategyChanges.value) {
    return '当前双流水线已经确认完成，这一版方案可以直接用于后续索引构建。'
  }
  if (hasConfirmedStrategy.value && hasUnconfirmedStrategyChanges.value) {
    return '你刚刚调整了父块/子块流水线顺序或补充说明，需要重新确认后才会真正生效。'
  }
  return '推荐双流水线已经生成，请先确认当前方案，再继续执行索引构建。'
})

const buildStepDescription = computed(() => {
  if (buildLoading.value) {
    return '系统正在创建索引构建任务，并同步最新阶段轨迹，请稍候。'
  }
  if (hasBuildInFlightStatus.value) {
    return `当前执行到「${activeBuildStageLabel.value || '索引构建中'}」，页面已暂时锁定并会实时刷新步骤进度。`
  }
  if (!hasSelectedStrategy.value) {
    return '当前还没有完整的父块 / 子块流水线，请先从上方补齐两条流水线。'
  }
  if (!hasConfirmedStrategy.value) {
    return '这里会保持锁定，直到你先完成上一步“确认策略方案”。'
  }
  if (hasUnconfirmedStrategyChanges.value) {
    return '当前有未确认的双流水线调整，请先重新确认方案，再执行索引构建。'
  }
  if (hasCode(documentDetail.value?.indexStatus, 3)) {
    return '最近一次构建已经完成；如果方案没变，这里也支持你再次发起构建。'
  }
  return '确认完成后可直接点击，构建进度会显示在下方，无需再往上查找。'
})

const confirmButtonLabel = computed(() => {
  if (confirmLoading.value) {
    return '确认中...'
  }
  if (hasConfirmedStrategy.value && !hasUnconfirmedStrategyChanges.value) {
    return '策略方案已确认'
  }
  if (hasConfirmedStrategy.value && hasUnconfirmedStrategyChanges.value) {
    return '重新确认策略方案'
  }
  return '确认策略方案'
})

const buildButtonLabel = computed(() => {
  if (buildLoading.value) {
    return '构建启动中...'
  }
  if (hasBuildInFlightStatus.value) {
    return '索引构建执行中'
  }
  if (!hasConfirmedStrategy.value) {
    return '先确认策略方案'
  }
  if (hasUnconfirmedStrategyChanges.value) {
    return '请先重新确认'
  }
  return '构建索引执行'
})

const workflowCurrentPhase = computed(() => {
  if (documentDetail.value?.parseErrorMsg || hasCode(documentDetail.value?.parseStatus, 4)) {
    return {
      tone: 'danger',
      shortLabel: '需处理',
      title: '文档解析失败',
      description: documentDetail.value?.parseErrorMsg || '请先排查解析异常，再继续后续推荐与构建流程。'
    }
  }
  if (!hasCode(documentDetail.value?.parseStatus, 3)) {
    return {
      tone: 'neutral',
      shortLabel: '待解析',
      title: '等待文档解析',
      description: '文档刚进入处理流程，当前先等待解析完成并生成可用文本。'
    }
  }
  if (!strategyPlan.value?.planReady) {
    return {
      tone: 'primary',
      shortLabel: '待推荐',
      title: '等待策略推荐',
      description: '解析已完成，系统正在准备父块与子块的推荐策略。'
    }
  }
  if (hasBuildInFlightStatus.value) {
    return {
      tone: 'primary',
      shortLabel: '执行中',
      title: `正在${activeBuildStageLabel.value || '构建索引'}`,
      description: '索引构建正在执行，页面会持续刷新阶段轨迹与任务状态。'
    }
  }
  if (!hasConfirmedStrategy.value) {
    return {
      tone: 'warning',
      shortLabel: '待确认',
      title: '等待确认策略',
      description: '推荐方案已经生成，请先确认父块与子块的最终执行链路。'
    }
  }
  if (hasUnconfirmedStrategyChanges.value) {
    return {
      tone: 'warning',
      shortLabel: '待重确认',
      title: '存在未确认调整',
      description: '你已经修改过双流水线，需要重新确认后才能继续构建。'
    }
  }
  if (hasCode(documentDetail.value?.indexStatus, 3)) {
    return {
      tone: 'success',
      shortLabel: '已完成',
      title: '索引已可用',
      description: '最近一次索引构建已经完成，可以开始验证 Chunk 和回看任务记录。'
    }
  }
  return {
    tone: 'neutral',
    shortLabel: '待构建',
    title: '准备执行构建',
    description: '策略方案已确认完成，下一步可以直接发起索引构建。'
  }
})

const workflowNextAction = computed(() => {
  if (documentDetail.value?.parseErrorMsg || hasCode(documentDetail.value?.parseStatus, 4)) {
    return {
      title: '先查看错误并修正文档',
      description: '建议先检查解析错误和最近任务日志，解决异常后再继续后续流程。'
    }
  }
  if (!hasCode(documentDetail.value?.parseStatus, 3)) {
    return {
      title: '等待解析完成',
      description: '当前还不需要人工操作，解析完成后刷新页面查看策略推荐结果。'
    }
  }
  if (!strategyPlan.value?.planReady) {
    return {
      title: '刷新并查看系统推荐',
      description: '解析完成后系统会生成父块与子块推荐策略，先阅读推荐再做人工调整。'
    }
  }
  if (!hasSelectedStrategy.value) {
    return {
      title: '补齐双流水线配置',
      description: '请分别为父块回答链路和子块召回链路至少选择一个策略。'
    }
  }
  if (!hasConfirmedStrategy.value || hasUnconfirmedStrategyChanges.value) {
    return {
      title: '前往确认策略方案',
      description: '先完成当前双流水线方案确认，再启动索引构建。'
    }
  }
  if (hasBuildInFlightStatus.value) {
    return {
      title: '观察构建执行轨迹',
      description: '构建已经开始，重点关注下方阶段轨迹与任务状态变化。'
    }
  }
  if (!hasCode(documentDetail.value?.indexStatus, 3)) {
    return {
      title: '执行构建索引',
      description: '当前方案已确认，下一步就是进入执行区启动索引构建。'
    }
  }
  return {
    title: '验证 Chunk 与任务记录',
    description: '索引已经可用，建议先检查分块效果，再复盘任务时间线。'
  }
})

const strategySectionStatusText = computed(() => {
  if (planLoading.value) {
    return '读取中'
  }
  if (documentDetail.value?.parseErrorMsg || hasCode(documentDetail.value?.parseStatus, 4)) {
    return '不可用'
  }
  if (!strategyPlan.value?.planReady) {
    return '待推荐'
  }
  if (!hasSelectedStrategy.value) {
    return '待选择'
  }
  if (hasConfirmedStrategy.value && !hasUnconfirmedStrategyChanges.value) {
    return '已确认'
  }
  if (hasUnconfirmedStrategyChanges.value) {
    return '待重新确认'
  }
  return '可调整'
})

const executionSectionStatusText = computed(() => {
  if (buildLoading.value) {
    return '启动中'
  }
  if (hasBuildInFlightStatus.value) {
    return activeBuildStageLabel.value || '执行中'
  }
  if (!strategyPlan.value?.planReady) {
    return '待策略就绪'
  }
  if (!hasConfirmedStrategy.value) {
    return '待确认'
  }
  if (hasUnconfirmedStrategyChanges.value) {
    return '待重新确认'
  }
  if (hasCode(documentDetail.value?.indexStatus, 3)) {
    return '已完成'
  }
  return '可构建'
})

const chunkSectionStatusText = computed(() => {
  if (chunkLoading.value) {
    return '加载中'
  }
  if (chunkTotalCount.value > 0) {
    return `${chunkTotalCount.value} 条`
  }
  return '暂无数据'
})

const ragSectionStatusText = computed(() => {
  if (ragSnapshotLoading.value) {
    return '读取中'
  }
  if (ragArtifactSampleCount.value > 0) {
    return `${ragArtifactSampleCount.value} 条样例`
  }
  if (ragSnapshot.value) {
    return '已读取'
  }
  return '暂无快照'
})

const taskSectionStatusText = computed(() => {
  if (logLoading.value) {
    return '读取中'
  }
  if (taskLogs.value.length) {
    return `${taskLogs.value.length} 条日志`
  }
  if (documentDetail.value?.latestTaskId) {
    return '有记录'
  }
  return '暂无任务'
})

const workbenchSections = computed(() => {
  return [
    {
      key: 'overview',
      step: '00',
      label: '文档概览',
      caption: '先看阶段与关键指标',
      status: workflowCurrentPhase.value.shortLabel
    },
    {
      key: 'strategy',
      step: '01',
      label: '配置策略',
      caption: '推荐 + 双流水线调整',
      status: strategySectionStatusText.value
    },
    {
      key: 'execution',
      step: '02',
      label: '确认并构建',
      caption: '确认方案并执行索引',
      status: executionSectionStatusText.value
    },
    {
      key: 'chunk',
      step: '03',
      label: '验证 Chunk 结果',
      caption: '检查分块结果与分页',
      status: chunkSectionStatusText.value
    },
    {
      key: 'rag',
      step: '04',
      label: 'RAG 学习视图',
      caption: '看解析、图谱、摘要产物',
      status: ragSectionStatusText.value
    },
    {
      key: 'tasks',
      step: '05',
      label: '查看任务记录',
      caption: '复盘日志与时间线',
      status: taskSectionStatusText.value
    }
  ]
})

const buildTrackerTitle = computed(() => {
  if (!showBuildTracker.value) {
    return ''
  }
  if (hasBuildTaskSnapshot.value && hasCode(buildTaskSnapshot.value?.taskStatus, 4)) {
    return `最近一次构建在「${buildTaskSnapshot.value?.currentStageName || '未知阶段'}」失败`
  }
  if ((hasBuildTaskSnapshot.value && hasCode(buildTaskSnapshot.value?.taskStatus, 3)) || hasCode(documentDetail.value?.indexStatus, 3)) {
    return '最近一次索引构建已完成'
  }
  return `当前阶段：${hasBuildTaskSnapshot.value ? (buildTaskSnapshot.value?.currentStageName || '索引构建中') : '索引构建中'}`
})

const buildTrackerDescription = computed(() => {
  if (!showBuildTracker.value) {
    return ''
  }
  if (hasBuildTaskSnapshot.value && hasCode(buildTaskSnapshot.value?.taskStatus, 4)) {
    return buildTaskSnapshot.value?.errorMsg || '请展开右侧时间线查看失败阶段和具体报错。'
  }
  if ((hasBuildTaskSnapshot.value && hasCode(buildTaskSnapshot.value?.taskStatus, 3)) || hasCode(documentDetail.value?.indexStatus, 3)) {
    return '即使任务执行很快，这里也会保留完整阶段轨迹，方便复盘和教学演示。'
  }
  return '系统正在自动轮询任务状态，阶段完成后会保留已完成轨迹，不会一闪而过。'
})

const buildStageItems = computed(() => {
  const taskStatus = normalizeCode(buildTaskSnapshot.value?.taskStatus)
  const currentStage = normalizeCode(buildTaskSnapshot.value?.currentStage)
  const activeStage = currentStage || (hasBuildInFlightStatus.value ? BUILD_STAGE_LIBRARY[0]?.code || '' : '')
  const logs = Array.isArray(buildTaskSnapshot.value?.logs) ? buildTaskSnapshot.value.logs : []
  const completedStages = new Set()
  const failedStages = new Set()
  const touchedStages = new Set()

  logs.forEach((log) => {
    const stageCode = normalizeCode(log.stageType)
    if (!BUILD_STAGE_CODE_SET.has(stageCode)) {
      return
    }
    touchedStages.add(stageCode)
    if (hasCode(log.eventType, 2)) {
      completedStages.add(stageCode)
    }
    if (hasCode(log.eventType, 3)) {
      failedStages.add(stageCode)
    }
  })

  const currentIndex = BUILD_STAGE_LIBRARY.findIndex((item) => item.code === activeStage)
  return BUILD_STAGE_LIBRARY.map((stage, index) => {
    let status = 'pending'
    let statusLabel = '等待执行'
    if (failedStages.has(stage.code) || (taskStatus === '4' && activeStage === stage.code)) {
      status = 'failed'
      statusLabel = '执行失败'
    }
    else if (taskStatus === '3') {
      status = 'completed'
      statusLabel = '已完成'
    }
    else if ((taskStatus === '1' || taskStatus === '2' || (hasBuildInFlightStatus.value && !currentStage)) && activeStage === stage.code) {
      status = 'current'
      statusLabel = '当前阶段'
    }
    else if (completedStages.has(stage.code) || ((taskStatus === '1' || taskStatus === '2') && currentIndex > index)) {
      status = 'completed'
      statusLabel = '已完成'
    }
    else if (touchedStages.has(stage.code)) {
      status = 'completed'
      statusLabel = '已完成'
    }
    return { ...stage, status, statusLabel }
  })
})

const buildStageRows = computed(() => buildSequenceRows(buildStageItems.value))
const buildOverlayTitle = computed(() => {
  if (buildLoading.value && !activeBuildTaskId.value && !hasBuildTaskSnapshot.value) {
    return '正在发起索引构建任务'
  }
  return activeBuildStageLabel.value ? `当前执行到「${activeBuildStageLabel.value}」` : '索引构建执行中'
})

const buildOverlayDescription = computed(() => {
  if (buildLoading.value && !activeBuildTaskId.value && !hasBuildTaskSnapshot.value) {
    return '系统正在锁定当前确认方案并创建异步任务，稍后会自动进入四个执行阶段。'
  }
  return '构建中的四个阶段会实时刷新，当前步骤会显示转圈提示，完成后自动解除页面锁定。'
})

function showNotice(message, type = 'info') {
  pageNotice.type = type
  pageNotice.message = message
}

function clearNotice() {
  pageNotice.message = ''
}

function asArray(value) {
  return Array.isArray(value) ? value : []
}

function firstQueryValue(value) {
  return Array.isArray(value) ? value[0] : value
}

function splitQueryList(value) {
  const raw = firstQueryValue(value)
  if (raw == null || raw === '') {
    return []
  }
  return String(raw)
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function buildRagTableHighlightSpec(query) {
  const rowNos = splitQueryList(query.highlightRows)
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item))
  const columnNames = splitQueryList(query.highlightColumns)
  const cellCoordinates = splitQueryList(query.highlightCells)
  const tableId = firstQueryValue(query.highlightTableId)
  const tableNo = firstQueryValue(query.highlightTableNo)
  return {
    active: Boolean(tableId || tableNo || rowNos.length || columnNames.length || cellCoordinates.length),
    tableId: tableId ? String(tableId) : '',
    tableNo: tableNo ? String(tableNo) : '',
    rowNos,
    rowNoSet: new Set(rowNos.map((item) => String(item))),
    columnNames,
    columnNameSet: new Set(columnNames.map(normalizeMatchText)),
    cellCoordinates,
    cellCoordinateSet: new Set(cellCoordinates.map(normalizeMatchText))
  }
}

function compactList(values) {
  return asArray(values)
    .map((item) => String(item || '').trim())
    .filter(Boolean)
}

function valueOrDash(value) {
  const text = String(value ?? '').trim()
  return text || '-'
}

function ragMetricClass(tone) {
  if (tone === 'success') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (tone === 'warning') return 'border-amber-500/20 bg-amber-500/[0.04]'
  if (tone === 'danger') return 'border-destructive/20 bg-destructive/[0.04]'
  return 'border-border bg-secondary'
}

function ragStageClass(statusText) {
  if (String(statusText || '').includes('已生成')) return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (String(statusText || '').includes('观测')) return 'border-primary/20 bg-primary/[0.04]'
  return 'border-border bg-card'
}

function normalizeMatchText(value) {
  return String(value ?? '').trim().toLowerCase()
}

function normalizeTableRows(table) {
  return asArray(table.rows).map((row) => {
    const cellItems = asArray(row.cellItems)
    return {
      ...row,
      cellItems: cellItems.length
        ? cellItems
        : asArray(row.cells).map((cellText, index) => ({
            columnNo: asArray(table.columns)[index]?.columnNo || index + 1,
            cellText,
            text: cellText
          }))
    }
  })
}

function isHighlightedTable(record) {
  const spec = ragTableHighlightSpec.value
  if (!spec.active) {
    return false
  }
  if (spec.tableId) {
    return normalizeCode(record?.tableId) === spec.tableId
  }
  if (spec.tableNo) {
    return normalizeCode(record?.tableNo) === spec.tableNo
  }
  return false
}

function isHighlightedColumn(record, column) {
  const spec = ragTableHighlightSpec.value
  if (!isHighlightedTable(record) || !spec.columnNameSet.size) {
    return false
  }
  return columnMatchCandidates(column).some((item) => spec.columnNameSet.has(item))
}

function isHighlightedTableRow(record, row) {
  const spec = ragTableHighlightSpec.value
  return isHighlightedTable(record) && spec.rowNoSet.has(String(row?.rowNo || ''))
}

function isHighlightedTableCell(record, row, cell, column) {
  const spec = ragTableHighlightSpec.value
  if (!isHighlightedTable(record) || !cell) {
    return false
  }
  const coordinateMatched = cellCoordinateCandidates(row, cell, column).some((item) => spec.cellCoordinateSet.has(item))
  if (coordinateMatched) {
    return true
  }
  const rowMatched = spec.rowNoSet.has(String(row?.rowNo || ''))
  const columnMatched = columnMatchCandidates(column).some((item) => spec.columnNameSet.has(item))
  if (rowMatched && columnMatched) {
    return true
  }
  if (rowMatched && !spec.columnNameSet.size && !spec.cellCoordinateSet.size) {
    return true
  }
  return columnMatched && !spec.rowNoSet.size && !spec.cellCoordinateSet.size
}

function tableCellClass(record, row, cell, column) {
  if (isHighlightedTableCell(record, row, cell, column)) {
    return 'border-l-2 border-primary bg-primary/[0.10] font-semibold text-primary'
  }
  if (isHighlightedTableRow(record, row)) {
    return 'bg-primary/[0.04] text-foreground'
  }
  return 'text-foreground'
}

function cellForColumn(row, column, fallbackIndex) {
  const cells = asArray(row?.cellItems)
  return cells.find((cell) => normalizeCode(cell.columnId) === normalizeCode(column?.columnId))
    || cells.find((cell) => normalizeCode(cell.columnNo) === normalizeCode(column?.columnNo))
    || cells[fallbackIndex]
    || null
}

function columnMatchCandidates(column) {
  return compactList([
    column?.columnName,
    column?.normalizedName,
    column?.columnNo ? `C#${column.columnNo}` : '',
    column?.columnNo ? `C${column.columnNo}` : ''
  ]).map(normalizeMatchText)
}

function cellCoordinateCandidates(row, cell, column) {
  return compactList([
    cell?.sourceCellRef,
    cell?.sourceRowNo && cell?.sourceColumnNo ? `R${cell.sourceRowNo}C${cell.sourceColumnNo}` : '',
    cell?.rowNo && cell?.columnNo ? `R${cell.rowNo}C${cell.columnNo}` : '',
    row?.rowNo && column?.columnNo ? `R${row.rowNo}C${column.columnNo}` : '',
    row?.rowNo && column?.columnNo ? `${row.rowNo}:${column.columnNo}` : ''
  ]).map(normalizeMatchText)
}

function buildChunkRelationText(chunk) {
  if (!chunk) {
    return '父子关系未知'
  }
  const parentNo = chunk.parentBlockNo || '-'
  const total = Number(chunk.parentChildCount || 0)
  const currentChunkNo = Number(chunk.chunkNo || 0)
  const startChunkNo = Number(chunk.parentStartChunkNo || 0)
  if (total > 0 && currentChunkNo > 0 && startChunkNo > 0) {
    const siblingIndex = currentChunkNo - startChunkNo + 1
    return `父块 P#${parentNo} · 同父第 ${siblingIndex}/${total} 子块`
  }
  return `父块 P#${parentNo} · 共 ${total || 0} 子块`
}

function isCurrentChunk(chunk) {
  return normalizeCode(chunk?.chunkId) === normalizeCode(chunkDetail.value?.chunk?.chunkId)
}

function buildSiblingOrderLabel(index, total) {
  const current = Number(index || 0) + 1
  return `第${current}/${total || 0}子块`
}

function formatChunkCodeList(chunks) {
  const chunkList = Array.isArray(chunks) ? chunks : []
  return chunkList
    .map((item) => `C#${item?.chunkNo || '-'}`)
    .join('、')
}

function isChunkGroupCollapsed(groupKey) {
  return Boolean(chunkGroupCollapsedMap.value[groupKey])
}

function toggleChunkGroup(groupKey) {
  chunkGroupCollapsedMap.value = {
    ...chunkGroupCollapsedMap.value,
    [groupKey]: !chunkGroupCollapsedMap.value[groupKey]
  }
}

function setAllChunkGroupsCollapsed(collapsed) {
  const nextMap = {}
  chunkGroupedRecords.value.forEach((group) => {
    nextMap[group.groupKey] = collapsed
  })
  chunkGroupCollapsedMap.value = nextMap
}

function scrollToWorkbenchSection(key) {
  activeWorkbenchSection.value = key
}

function goBack() {
  router.push({ name: 'AdminDocuments' })
}

function buildSequenceRows(items) {
  const sourceList = Array.isArray(items) ? items : []
  const rows = []
  for (let index = 0; index < sourceList.length; index += 2) {
    const pair = sourceList.slice(index, index + 2)
    const rowIndex = rows.length
    const direction = rowIndex % 2 === 0 ? 'ltr' : 'rtl'
    const leftItem = direction === 'ltr' ? pair[0] || null : pair[1] || null
    const rightItem = direction === 'ltr' ? pair[1] || null : pair[0] || null
    rows.push({
      direction,
      leftItem,
      rightItem,
      downColumn: direction === 'ltr' ? 'right' : 'left'
    })
  }
  return rows
}

function getSelectedStrategyTypes(pipelineKey) {
  return pipelineKey === 'parent' ? selectedParentStrategyTypes.value : selectedChildStrategyTypes.value
}

function setSelectedStrategyTypes(pipelineKey, nextList) {
  const normalizedList = normalizeStrategyTypeList(nextList, strategyLibrary)
  if (pipelineKey === 'parent') {
    selectedParentStrategyTypes.value = normalizedList
    return
  }
  selectedChildStrategyTypes.value = normalizedList
}

function getSelectedStrategyPreview(pipelineKey) {
  return pipelineKey === 'parent' ? selectedParentStrategyPreview.value : selectedChildStrategyPreview.value
}

function getSelectedStrategyRows(pipelineKey) {
  return pipelineKey === 'parent' ? selectedParentStrategyRows.value : selectedChildStrategyRows.value
}

function toggleStrategy(type, pipelineKey) {
  if (hasBuildInFlightStatus.value) {
    return
  }
  const normalizedType = normalizeCode(type)
  if (!normalizedType) {
    return
  }
  const currentTypes = getSelectedStrategyTypes(pipelineKey)
  if (currentTypes.includes(normalizedType)) {
    setSelectedStrategyTypes(pipelineKey, currentTypes.filter((item) => item !== normalizedType))
    return
  }
  setSelectedStrategyTypes(pipelineKey, [...currentTypes, normalizedType])
}

function moveStrategy(type, direction, pipelineKey) {
  if (hasBuildInFlightStatus.value) {
    return
  }
  const sourceType = normalizeCode(type)
  const orderedTypes = normalizeStrategyTypeList(getSelectedStrategyTypes(pipelineKey), strategyLibrary)
  const sourceIndex = orderedTypes.indexOf(sourceType)
  if (sourceIndex < 0) {
    return
  }
  const targetIndex = sourceIndex + direction
  if (targetIndex < 0 || targetIndex >= orderedTypes.length) {
    return
  }
  const nextList = [...orderedTypes]
  ;[nextList[sourceIndex], nextList[targetIndex]] = [nextList[targetIndex], nextList[sourceIndex]]
  setSelectedStrategyTypes(pipelineKey, nextList)
}

function focusBuildTracker() {
  nextTick(() => {
    buildTrackerRef.value?.scrollIntoView({
      behavior: 'smooth',
      block: 'center'
    })
  })
}

async function loadDocumentDetail() {
  documentDetail.value = await manageApi.queryDocumentDetail(documentId.value)
}

async function loadStrategyPlan() {
  planLoading.value = true
  try {
    strategyPlan.value = await manageApi.queryStrategyPlan(documentId.value)
    selectedParentStrategyTypes.value = extractPipelineStrategyTypes(strategyPlan.value?.plan, 'parent', strategyLibrary)
    selectedChildStrategyTypes.value = extractPipelineStrategyTypes(strategyPlan.value?.plan, 'child', strategyLibrary)
    adjustNote.value = ''
  } finally {
    planLoading.value = false
  }
}

async function loadTaskLogs() {
  const latestTaskId = documentDetail.value?.latestTaskId
  if (!latestTaskId) {
    taskLogs.value = []
    taskLogSnapshot.value = null
    return
  }
  logLoading.value = true
  try {
    const data = await manageApi.queryTaskLogs({
      taskId: latestTaskId,
      pageNo: '1',
      pageSize: '30'
    })
    taskLogSnapshot.value = data || null
    taskLogs.value = Array.isArray(data?.logs) ? data.logs : []
  } catch (error) {
    console.error('读取任务日志失败', error)
    taskLogSnapshot.value = null
    taskLogs.value = []
  } finally {
    logLoading.value = false
  }
}

async function loadBuildTaskLogs() {
  const buildTaskId = activeBuildTaskId.value
  if (!buildTaskId) {
    buildTaskSnapshot.value = null
    return
  }
  try {
    const data = await manageApi.queryTaskLogs({
      taskId: buildTaskId,
      pageNo: '1',
      pageSize: '30'
    })
    buildTaskSnapshot.value = data || null
  } catch (error) {
    console.error('读取构建任务日志失败', error)
    buildTaskSnapshot.value = null
  }
}

async function loadDocumentChunks(page = chunkCurrentPage.value, options = {}) {
  const {
    resetCollapse = true,
    resetChunkDetail = false
  } = options

  chunkLoading.value = true
  try {
    if (resetChunkDetail) {
      chunkDetail.value = null
      chunkDetailDrawerOpen.value = false
      chunkDetailFocusMode.value = 'chunk'
    }
    chunkQuery.value = await manageApi.queryDocumentChunks({
      documentId: documentId.value,
      pageNo: page,
      pageSize: chunkPageSize.value
    })
    chunkPageNo.value = Number(chunkQuery.value?.pageNo || page || 1)
    chunkPageSize.value = Number(chunkQuery.value?.pageSize || chunkPageSize.value || DEFAULT_CHUNK_PAGE_SIZE)
    if (resetCollapse) {
      chunkGroupCollapsedMap.value = {}
    }
  } catch (error) {
    console.error('读取 chunk 列表失败', error)
    chunkQuery.value = null
  } finally {
    chunkLoading.value = false
  }
}

async function loadDocumentRagSnapshot() {
  ragSnapshotLoading.value = true
  try {
    ragSnapshot.value = await manageApi.queryDocumentRagSnapshot(documentId.value, buildRagSnapshotHighlightPayload())
  } catch (error) {
    console.error('读取 RAG 学习快照失败', error)
    ragSnapshot.value = null
  } finally {
    ragSnapshotLoading.value = false
  }
}

function buildRagSnapshotHighlightPayload() {
  const spec = ragTableHighlightSpec.value
  if (!spec.active) {
    return {}
  }
  return {
    highlightTableId: spec.tableId || undefined,
    highlightTableNo: spec.tableNo || undefined,
    highlightRowNos: spec.rowNos,
    highlightColumnNames: spec.columnNames,
    highlightCellCoordinates: spec.cellCoordinates
  }
}

function applyRouteWorkbenchFocus() {
  const section = firstQueryValue(route.query?.section)
  if (section && WORKBENCH_SECTION_KEYS.includes(section)) {
    activeWorkbenchSection.value = section
    return
  }
  if (hasRagTableHighlight.value) {
    activeWorkbenchSection.value = 'rag'
  }
}

function changeChunkPage(page) {
  if (page < 1 || page > chunkTotalPages.value || page === chunkCurrentPage.value || chunkLoading.value) {
    return
  }
  loadDocumentChunks(page, {
    resetCollapse: true,
    resetChunkDetail: true
  })
}

function changeChunkPageSize(pageSize) {
  const nextPageSize = Number(pageSize || DEFAULT_CHUNK_PAGE_SIZE)
  if (!Number.isFinite(nextPageSize) || nextPageSize <= 0 || nextPageSize === chunkCurrentPageSize.value || chunkLoading.value) {
    return
  }
  chunkPageSize.value = nextPageSize
  chunkPageNo.value = 1
  loadDocumentChunks(1, {
    resetCollapse: true,
    resetChunkDetail: true
  })
}

async function loadAll() {
  loading.value = true
  clearNotice()
  try {
    await loadDocumentDetail()
    await Promise.all([
      loadStrategyPlan(),
      loadTaskLogs(),
      loadBuildTaskLogs(),
      loadDocumentChunks(),
      loadDocumentRagSnapshot()
    ])
  } catch (error) {
    console.error('读取文档详情失败', error)
    showNotice(normalizeError(error, '读取文档详情失败'), 'danger')
  } finally {
    loading.value = false
  }
}

async function submitConfirmStrategy() {
  if (!strategyPlan.value?.plan?.planId) {
    showNotice('当前还没有可确认的策略方案。', 'danger')
    return
  }
  if (!hasSelectedStrategy.value) {
    showNotice('请先分别配置父块流水线和子块流水线，再确认当前方案。', 'danger')
    return
  }
  if (hasBuildInFlightStatus.value) {
    showNotice('索引构建执行中，暂时不能修改或确认策略方案。', 'danger')
    return
  }

  confirmLoading.value = true
  clearNotice()
  try {
    await manageApi.confirmStrategy({
      documentId: documentId.value,
      basePlanId: strategyPlan.value.plan.planId,
      adjustNote: adjustNote.value.trim(),
      operatorId: OPERATOR_ID,
      parentSteps: buildPipelineStepPayload(selectedParentStrategyTypes.value, strategyLibrary),
      childSteps: buildPipelineStepPayload(selectedChildStrategyTypes.value, strategyLibrary)
    })
    showNotice('策略方案已确认，接下来可以直接构建索引。', 'success')
    await loadAll()
  } catch (error) {
    console.error('确认策略失败', error)
    showNotice(normalizeError(error, '确认策略失败'), 'danger')
  } finally {
    confirmLoading.value = false
  }
}

async function submitBuildIndex() {
  if (!hasSelectedStrategy.value) {
    showNotice('请先选择并确认父块 / 子块双流水线，再执行索引构建。', 'danger')
    return
  }
  if (!hasConfirmedStrategy.value || !documentDetail.value?.currentPlanId) {
    showNotice('请先点击“确认策略方案”，确认后才能执行索引构建。', 'danger')
    return
  }
  if (hasUnconfirmedStrategyChanges.value) {
    showNotice('当前双流水线有未确认的改动，请先重新确认方案。', 'danger')
    return
  }
  if (hasBuildInFlightStatus.value) {
    showNotice('索引构建正在执行中，请等待当前任务完成。', 'info')
    return
  }

  buildLoading.value = true
  clearNotice()
  try {
    const result = await manageApi.buildIndex({
      documentId: documentId.value,
      planId: documentDetail.value.currentPlanId,
      operatorId: OPERATOR_ID
    })
    showNotice(`索引任务 ${result.taskId} 已创建，系统正在异步构建中。`, 'success')
    await loadAll()
    startBuildPolling()
    focusBuildTracker()
  } catch (error) {
    console.error('构建索引失败', error)
    showNotice(normalizeError(error, '构建索引失败'), 'danger')
  } finally {
    buildLoading.value = false
  }
}

async function openChunkDetail(chunkId, focusMode = 'chunk') {
  if (!chunkId) {
    return
  }
  chunkDetailDrawerOpen.value = true
  chunkDetailLoading.value = true
  chunkDetailFocusMode.value = focusMode
  try {
    chunkDetail.value = await manageApi.queryDocumentChunkDetail({
      documentId: documentId.value,
      taskId: chunkQuery.value?.taskId || null,
      chunkId
    })
    if (focusMode === 'parent') {
      await nextTick()
      parentBlockSectionRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  } catch (error) {
    console.error('读取 chunk 详情失败', error)
    showNotice(normalizeError(error, '读取 chunk 详情失败'), 'danger')
    chunkDetail.value = null
  } finally {
    chunkDetailLoading.value = false
  }
}

function openParentBlockDetail(group) {
  if (!group?.items?.length) {
    return
  }
  openChunkDetail(group.items[0].chunkId, 'parent')
}

function openLogDrawer() {
  logDrawerOpen.value = true
  loadTaskLogs()
}

function closeLogDrawer() {
  logDrawerOpen.value = false
}

function closeChunkDetailDrawer() {
  chunkDetailDrawerOpen.value = false
  chunkDetailFocusMode.value = 'chunk'
}

function clearBuildPolling() {
  if (buildPollTimer.value) {
    window.clearInterval(buildPollTimer.value)
    buildPollTimer.value = null
  }
}

function startBuildPolling() {
  clearBuildPolling()
  let pollCount = 0
  buildPollTimer.value = window.setInterval(async () => {
    pollCount += 1
    try {
      await loadAll()
      const building = hasCode(documentDetail.value?.indexStatus, 2)
        || (hasCode(documentDetail.value?.latestTaskType, 2) && ['1', '2'].includes(normalizeCode(documentDetail.value?.latestTaskStatus)))
      if (!building || pollCount >= 30) {
        clearBuildPolling()
      }
    } catch (error) {
      console.error('轮询索引构建状态失败', error)
      clearBuildPolling()
    }
  }, 3000)
}

function startPlanPolling() {
  if (planPollTimer.value) {
    window.clearInterval(planPollTimer.value)
  }
  let pollCount = 0
  planPollTimer.value = window.setInterval(async () => {
    pollCount += 1
    try {
      await loadDocumentDetail()
      await loadStrategyPlan()
      if (strategyPlan.value?.planReady || normalizeCode(strategyPlan.value?.parseStatus) === '4' || pollCount >= 8) {
        window.clearInterval(planPollTimer.value)
        planPollTimer.value = null
      }
    } catch (error) {
      console.error('轮询策略结果失败', error)
      window.clearInterval(planPollTimer.value)
      planPollTimer.value = null
    }
  }, 2500)
}

function formatDuration(value) {
  const millis = Number(value || 0)
  if (!Number.isFinite(millis) || millis <= 0) {
    return '-'
  }
  if (millis < 1000) {
    return `${millis} ms`
  }
  if (millis < 60_000) {
    return `${(millis / 1000).toFixed(1)} s`
  }
  return `${(millis / 60_000).toFixed(1)} min`
}

function normalizeError(error, fallbackMessage) {
  if (error instanceof APIError && error.message) {
    return error.message
  }
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallbackMessage
}

watch(() => route.params.documentId, async (value, oldValue) => {
  if (!value || value === oldValue) {
    return
  }
  activeWorkbenchSection.value = 'overview'
  chunkPageNo.value = 1
  chunkPageSize.value = DEFAULT_CHUNK_PAGE_SIZE
  chunkGroupCollapsedMap.value = {}
  chunkDetail.value = null
  chunkDetailDrawerOpen.value = false
  chunkDetailFocusMode.value = 'chunk'
  await loadAll()
  applyRouteWorkbenchFocus()
  await nextTick()
})

watch(() => route.query, async () => {
  applyRouteWorkbenchFocus()
  if (documentId.value) {
    await loadDocumentRagSnapshot()
  }
}, { deep: true })

watch(documentDetail, (value) => {
  if (!value) {
    clearBuildPolling()
    return
  }
  const building = hasCode(value.indexStatus, 2)
    || (hasCode(value.latestTaskType, 2) && ['1', '2'].includes(normalizeCode(value.latestTaskStatus)))
  if (building && !buildPollTimer.value) {
    startBuildPolling()
    return
  }
  if (!building && buildPollTimer.value) {
    clearBuildPolling()
  }
})

onMounted(async () => {
  await loadAll()
  applyRouteWorkbenchFocus()
  await nextTick()
  if (!strategyPlan.value?.planReady && normalizeCode(strategyPlan.value?.parseStatus) !== '4') {
    startPlanPolling()
  }
})

onBeforeUnmount(() => {
  if (planPollTimer.value) {
    window.clearInterval(planPollTimer.value)
    planPollTimer.value = null
  }
  clearBuildPolling()
})

const chunkTableHeads = ['Chunk', '章节 / 标识', '来源 / 状态', '字符', 'Token', '内容预览']
const chunkStats = computed(() => [
  { label: '父块数', value: formatCount(chunkParentCount.value) },
  { label: '总片段', value: formatCount(chunkTotalCount.value) },
  { label: '向量可用', value: formatCount(chunkVectorReadyCount.value) },
  { label: '待处理', value: formatCount(chunkVectorPendingCount.value) },
  { label: '平均 Token', value: formatCount(chunkAverageTokens.value) }
])
function noticeClass(type) {
  if (type === 'success') return 'bg-[#ecfdf3] text-[#027a48]'
  if (type === 'danger') return 'bg-[#fef3f2] text-[#b42318]'
  return 'bg-primary/[0.08] text-primary'
}
function guidanceCardClass(tone) {
  if (tone === 'success') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (tone === 'warning') return 'border-amber-500/20 bg-amber-500/[0.04]'
  if (tone === 'danger') return 'border-destructive/20 bg-destructive/[0.04]'
  return 'border-primary/20 bg-primary/[0.04]'
}
function actionStageClass(state) {
  if (state === 'done') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (state === 'ready') return 'border-primary/20 bg-primary/[0.04]'
  if (state === 'blocked') return 'border-amber-500/20 bg-amber-500/[0.04]'
  return 'border-border bg-secondary'
}
function strategyStatusStepClass(status) {
  if (status === 'done') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (status === 'current') return 'border-primary/20 bg-primary/[0.04]'
  return 'border-border bg-secondary'
}
function buildStageClass(status) {
  if (status === 'done') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (status === 'current') return 'border-primary/20 bg-primary/[0.04]'
  if (status === 'error') return 'border-destructive/20 bg-destructive/[0.04]'
  return 'border-border bg-card'
}
function buildOverlayStageClass(status) {
  if (status === 'done') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (status === 'current') return 'border-primary/20 bg-primary/[0.04]'
  return 'border-border bg-card/50'
}
function chunkChipClass(code) {
  if (code === '2') return 'bg-[var(--color-success)]/10 text-[var(--color-success)]'
  if (code === '3') return 'bg-destructive/10 text-destructive'
  return 'bg-foreground/[0.06] text-muted-foreground'
}
</script>

<style scoped>
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity 0.25s ease; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-slide-enter-active, .drawer-slide-leave-active { transition: transform 0.25s ease; }
.drawer-slide-enter-from, .drawer-slide-leave-to { transform: translateX(100%); }
.build-mask-fade-enter-active, .build-mask-fade-leave-active { transition: opacity 0.2s ease; }
.build-mask-fade-enter-from, .build-mask-fade-leave-to { opacity: 0; }
@keyframes spin { to { transform: rotate(360deg); } }
.stage-spinner, .build-overlay-spinner { display: inline-block; width: 14px; height: 14px; border: 2px solid currentColor; border-top-color: transparent; border-radius: 50%; animation: spin 0.7s linear infinite; }
.build-overlay-spinner { width: 28px; height: 28px; border-width: 3px; color: var(--color-primary); }
</style>
