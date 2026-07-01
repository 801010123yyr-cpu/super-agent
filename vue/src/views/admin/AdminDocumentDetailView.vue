<template>
  <section class="flex flex-col gap-4">
    <transition name="drawer-fade"><div v-if="logDrawerOpen" class="fixed inset-0 z-50 bg-[rgba(15,23,42,0.3)]" @click="closeLogDrawer"></div></transition>
    <transition name="drawer-fade"><div v-if="chunkDetailDrawerOpen" class="fixed inset-0 z-50 bg-[rgba(15,23,42,0.3)]" @click="closeChunkDetailDrawer"></div></transition>
    <transition name="drawer-fade"><div v-if="artifactPreviewDrawerOpen" class="fixed inset-0 z-50 bg-[rgba(15,23,42,0.3)]" @click="closeArtifactPreviewDrawer"></div></transition>

    <DocumentParseRouteProgressDialog
      v-model="parseRouteDialogOpen"
      :document-id="documentId"
      :task-id="parseRouteDialogTaskId"
      @completed="handleParseRouteCompleted"
      @failed="handleParseRouteFailed"
      @open-strategy="handleOpenParseRouteStrategy"
      @open-logs="openLogDrawer"
    />

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
              <span class="grid h-7 w-7 shrink-0 place-items-center rounded-full text-xs font-bold" :class="stage.status === 'completed' ? 'bg-[var(--color-success)] text-white' : stage.status === 'failed' ? 'bg-destructive text-white' : stage.status === 'current' ? 'bg-primary text-white' : 'bg-foreground/[0.08] text-muted-foreground'"><span v-if="stage.status === 'current'" class="stage-spinner" aria-hidden="true"></span><span v-else>{{ stage.order }}</span></span>
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

    <transition name="drawer-slide">
      <aside v-if="artifactPreviewDrawerOpen" class="fixed bottom-0 right-0 top-0 z-[51] flex w-[760px] max-w-[96vw] flex-col bg-card shadow-[-4px_0_24px_rgba(15,23,42,0.12)]">
        <div class="flex items-start justify-between gap-4 border-b border-border px-6 py-5">
          <div class="min-w-0">
            <h3 class="text-base font-semibold text-foreground">解析产物预览</h3>
            <p class="mt-0.5 truncate text-xs text-muted-foreground">
              <template v-if="artifactPreviewItem">{{ artifactPreviewItem.artifactTypeName || artifactPreviewItem.artifactType }} · {{ artifactPreviewItem.fileName || '-' }}</template>
              <template v-else>正在读取解析产物</template>
            </p>
          </div>
          <div class="flex shrink-0 items-center gap-2">
            <button v-if="artifactPreviewItem" class="inline-flex h-9 items-center gap-1.5 rounded-md border border-border bg-card px-3 text-xs font-semibold text-foreground hover:bg-secondary disabled:opacity-50" type="button" :disabled="artifactDownloadLoading" @click="downloadParseArtifact(artifactPreviewItem)">
              <ArrowDownTrayIcon class="h-4 w-4" />
              {{ artifactDownloadLoading ? '下载中' : '下载' }}
            </button>
            <button class="grid h-9 w-9 place-items-center rounded-md border border-border bg-card text-foreground hover:bg-secondary" type="button" aria-label="关闭解析产物预览" @click="closeArtifactPreviewDrawer"><XMarkIcon class="h-4 w-4" /></button>
          </div>
        </div>
        <div v-if="artifactContentLoading" class="flex-1 py-8 text-center text-sm text-muted-foreground">正在加载解析产物...</div>
        <div v-else-if="!artifactContent" class="flex-1 py-8 text-center text-sm text-muted-foreground">当前没有可预览的解析产物。</div>
        <div v-else class="flex-1 overflow-y-auto px-6 py-5">
          <div class="mb-4 grid gap-2 sm:grid-cols-2">
            <div class="rounded-md border border-border bg-secondary p-3">
              <span class="text-[11px] text-muted-foreground">对象路径</span>
              <div class="mt-1 flex items-start gap-2">
                <code class="min-w-0 flex-1 break-all text-xs text-foreground">{{ artifactPreviewItem?.objectName || '-' }}</code>
                <button class="grid h-7 w-7 shrink-0 place-items-center rounded border border-border bg-card text-muted-foreground hover:text-foreground" type="button" title="复制对象路径" @click="copyText(artifactPreviewItem?.objectName || '')">
                  <ClipboardDocumentIcon class="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
            <div class="grid grid-cols-2 gap-2">
              <div class="rounded-md border border-border bg-secondary p-3"><span class="text-[11px] text-muted-foreground">大小</span><strong class="mt-1 block text-sm text-foreground">{{ formatBytes(artifactPreviewItem?.size || artifactContent.contentLength) }}</strong></div>
              <div class="rounded-md border border-border bg-secondary p-3"><span class="text-[11px] text-muted-foreground">Hash</span><strong class="mt-1 block truncate text-sm text-foreground">{{ artifactPreviewItem?.contentHash || '-' }}</strong></div>
            </div>
          </div>

          <template v-if="artifactIsImage">
            <div v-if="artifactImageSrc" class="max-h-[68vh] overflow-auto rounded-md border border-border bg-secondary p-3">
              <img class="mx-auto block max-h-[64vh] max-w-full rounded border border-border bg-white object-contain" :src="artifactImageSrc" :alt="artifactPreviewItem?.fileName || 'parse artifact image'" />
            </div>
            <div v-else class="rounded-md border border-dashed border-border py-8 text-center text-sm text-muted-foreground">当前图片内容为空。</div>
          </template>
          <template v-else>
            <div class="mb-3 flex flex-wrap items-center gap-2">
              <label class="relative min-w-[220px] flex-1">
                <MagnifyingGlassIcon class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                <input v-model="artifactSearchKeyword" class="h-9 w-full rounded-md border border-border bg-card pl-9 pr-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20" type="search" placeholder="搜索 jobId、layouts、pos 或字段路径" />
              </label>
              <span class="rounded-full bg-secondary px-3 py-1.5 text-xs text-muted-foreground">匹配 {{ artifactSearchMatchCount }} 处</span>
              <button v-if="artifactCanCollapse" class="rounded-md border border-border bg-card px-3 py-1.5 text-xs font-semibold text-foreground hover:bg-secondary" type="button" @click="artifactPreviewCollapsed = !artifactPreviewCollapsed">{{ artifactPreviewCollapsed ? '展开全文' : '折叠预览' }}</button>
              <button class="inline-flex items-center gap-1.5 rounded-md border border-border bg-card px-3 py-1.5 text-xs font-semibold text-foreground hover:bg-secondary" type="button" @click="copyText(formattedArtifactContent)">
                <ClipboardDocumentIcon class="h-3.5 w-3.5" />
                复制内容
              </button>
            </div>

            <div v-if="artifactJsonPathRows.length" class="mb-3 rounded-md border border-border bg-secondary p-3">
              <div class="mb-2 flex items-center justify-between gap-2">
                <strong class="text-xs text-foreground">JSON 字段路径</strong>
                <span class="text-[11px] text-muted-foreground">{{ artifactSearchKeyword ? '按搜索词过滤' : '顶层字段' }}</span>
              </div>
              <div class="grid max-h-40 gap-1.5 overflow-y-auto">
                <button v-for="row in artifactJsonPathRows" :key="row.path" class="flex items-start gap-2 rounded-md border border-border bg-card px-2.5 py-2 text-left hover:border-primary/30" type="button" @click="copyText(row.path)">
                  <code class="shrink-0 text-[11px] font-semibold text-primary">{{ row.path }}</code>
                  <span class="line-clamp-1 min-w-0 text-[11px] text-muted-foreground">{{ row.preview }}</span>
                </button>
              </div>
            </div>

            <pre class="max-h-[62vh] overflow-auto rounded-md border border-border bg-[#0f172a] p-4 text-xs leading-5 text-[#e2e8f0]">{{ visibleArtifactContent }}</pre>
            <p v-if="artifactPreviewCollapsed" class="mt-2 text-xs text-muted-foreground">内容较大，已默认折叠显示前 {{ formatCount(ARTIFACT_PREVIEW_LIMIT) }} 个字符，可展开全文或直接下载。</p>
          </template>
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
              <button v-if="shouldShowParseRouteProgressEntry" class="rounded-full border border-primary/[0.16] bg-primary/[0.08] px-4 py-2 text-sm font-semibold text-primary hover:bg-primary/[0.12]" type="button" @click="openParseRouteDialog()">查看解析进度</button>
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
              <div class="grid h-7 w-7 shrink-0 place-items-center rounded-full text-xs font-bold" :class="item.status === 'completed' ? 'bg-[var(--color-success)] text-white' : item.status === 'failed' ? 'bg-destructive text-white' : item.status === 'current' ? 'bg-primary text-white' : 'bg-foreground/[0.08] text-muted-foreground'">{{ item.order }}</div>
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
                    <div class="grid h-8 w-8 shrink-0 place-items-center rounded-full text-xs font-bold" :class="row.leftItem.status==='completed'?'bg-[var(--color-success)] text-white':row.leftItem.status==='current'?'bg-primary text-white':row.leftItem.status==='failed'?'bg-destructive text-white':'bg-foreground/[0.08] text-muted-foreground'"><span v-if="row.leftItem.status==='current'" class="stage-spinner" aria-hidden="true"></span><span v-else>{{ row.leftItem.order }}</span></div>
                    <div><strong class="block text-[13px] text-foreground">{{ row.leftItem.label }}</strong><em class="mt-0.5 block text-xs not-italic text-muted-foreground">{{ row.leftItem.statusLabel }}</em></div>
                  </article>
                  <div v-else class="flex-1"></div>
                  <div class="flex w-6 items-center justify-center text-xs text-muted-foreground">{{ row.leftItem && row.rightItem ? (row.direction==='rtl'?'←':'→') : '' }}</div>
                  <article v-if="row.rightItem" class="flex flex-1 items-center gap-3 rounded-lg border p-3" :class="buildStageClass(row.rightItem.status)">
                    <div class="grid h-8 w-8 shrink-0 place-items-center rounded-full text-xs font-bold" :class="row.rightItem.status==='completed'?'bg-[var(--color-success)] text-white':row.rightItem.status==='current'?'bg-primary text-white':row.rightItem.status==='failed'?'bg-destructive text-white':'bg-foreground/[0.08] text-muted-foreground'"><span v-if="row.rightItem.status==='current'" class="stage-spinner" aria-hidden="true"></span><span v-else>{{ row.rightItem.order }}</span></div>
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
              <span>耗时 {{ formatDuration(buildTaskSnapshot?.elapsedMillis || buildTaskSnapshot?.costMillis) }}</span>
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
              <article v-for="group in chunkGroupedRecords" :key="`parent-group-${group.parentBlockId||group.parentBlockNo}`" class="rounded-lg border border-border bg-primary/[0.04] overflow-hidden">
                <div class="flex items-start justify-between gap-3 p-4 max-md:flex-col">
                  <div><strong class="block text-sm text-foreground">父块 P#{{ group.parentBlockNo || '-' }}</strong><p class="mt-0.5 text-xs text-muted-foreground">{{ group.sectionPath || '未识别章节' }}</p></div>
                  <div class="flex flex-wrap items-center gap-2">
                    <div class="text-xs text-muted-foreground"><span>子块 {{ group.items.length }}/{{ group.parentChildCount||group.items.length }}</span><span class="ml-2">范围 C#{{ group.parentStartChunkNo||'-' }} - C#{{ group.parentEndChunkNo||'-' }}</span></div>
                    <button class="rounded-full border border-border bg-secondary px-3 py-1.5 text-xs font-semibold text-foreground hover:bg-card" type="button" @click="openParentBlockDetail(group)">查看父块上下文</button>
                    <button class="rounded-full border border-border bg-secondary px-3 py-1.5 text-xs font-semibold text-foreground hover:bg-card" type="button" @click="toggleChunkGroup(group.groupKey)">{{ isChunkGroupCollapsed(group.groupKey)?'展开子块':'折叠子块' }}</button>
                  </div>
                </div>
                <template v-if="!isChunkGroupCollapsed(group.groupKey)">
                  <div class="flex flex-wrap gap-2 border-t border-border bg-primary/[0.03] p-3">
                    <button v-for="item in group.items" :key="`group-track-${item.chunkId}`" class="flex flex-col items-center rounded-lg border border-border bg-card px-3 py-2 text-center text-xs hover:border-primary/20" type="button" @click="openChunkDetail(item.chunkId)"><strong class="text-foreground">#{{ item.chunkNo||'-' }}</strong><span class="text-muted-foreground">{{ formatCount(item.tokenCount) }} T</span></button>
                  </div>
                  <div class="overflow-x-auto">
                    <table class="w-full min-w-[640px] border-collapse text-sm table-fixed">
                      <colgroup>
                        <col style="width:130px">
                        <col style="width:200px">
                        <col style="width:120px">
                        <col style="width:68px">
                        <col style="width:68px">
                        <col>
                      </colgroup>
                      <thead><tr class="border-t border-border bg-primary/[0.03]">
                        <th class="px-4 py-3 text-left text-xs font-semibold text-muted-foreground">Chunk</th>
                        <th class="px-4 py-3 text-left text-xs font-semibold text-muted-foreground">章节 / 标识</th>
                        <th class="px-4 py-3 text-left text-xs font-semibold text-muted-foreground">来源 / 状态</th>
                        <th class="px-4 py-3 text-left text-xs font-semibold text-muted-foreground">字符</th>
                        <th class="px-4 py-3 text-left text-xs font-semibold text-muted-foreground">Token</th>
                        <th class="px-4 py-3 text-left text-xs font-semibold text-muted-foreground">内容预览</th>
                      </tr></thead>
                      <tbody>
                        <tr v-for="item in group.items" :key="`group-row-${item.chunkId}`" class="cursor-pointer border-t border-border bg-primary/[0.03] transition-colors hover:bg-primary/[0.06]" @click="openChunkDetail(item.chunkId)">
                          <td class="p-4"><strong class="block text-[13px] text-foreground">子块 C#{{ item.chunkNo }}</strong><span class="text-xs text-muted-foreground">{{ buildChunkRelationText(item) }}</span></td>
                          <td class="p-4"><strong class="block text-[13px] text-foreground">{{ item.sectionPath||'未识别章节' }}</strong><span class="text-xs text-muted-foreground">P#{{ item.parentBlockNo||'-' }} · 共 {{ item.parentChildCount||0 }} 子块</span></td>
                          <td class="px-3 py-4"><div class="flex flex-col gap-0.5"><span class="truncate text-xs text-muted-foreground">{{ item.sourceTypeName||'-' }}</span><div class="flex items-center gap-1"><component :is="chunkStatusIcon(normalizeCode(item.vectorStatus)||'0')" class="h-3.5 w-3.5 shrink-0" :class="chunkStatusTextClass(normalizeCode(item.vectorStatus)||'0')" /><span class="text-xs font-medium" :class="chunkStatusTextClass(normalizeCode(item.vectorStatus)||'0')">{{ item.vectorStatusName||'-' }}</span></div></div></td>
                          <td class="p-4 text-sm font-semibold text-foreground">{{ formatCount(item.charCount) }}</td>
                          <td class="p-4 text-sm font-semibold text-foreground">{{ formatCount(item.tokenCount) }}</td>
                          <td class="p-4"><p class="line-clamp-2 text-[13px] text-foreground">{{ item.chunkText }}</p></td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </template>
              </article>
            </div>
            <div v-else class="overflow-x-auto rounded-lg border border-border">
              <table class="w-full min-w-[640px] border-collapse text-sm table-fixed">
                <colgroup>
                  <col style="width:130px">
                  <col style="width:200px">
                  <col style="width:120px">
                  <col style="width:68px">
                  <col style="width:68px">
                  <col>
                </colgroup>
                <thead><tr class="bg-primary/[0.03]">
                  <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">Chunk</th>
                  <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">章节 / 标识</th>
                  <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">来源 / 状态</th>
                  <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">字符</th>
                  <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">Token</th>
                  <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">内容预览</th>
                </tr></thead>
                <tbody>
                  <tr v-for="item in chunkRecords" :key="item.chunkId" class="cursor-pointer border-b border-border bg-primary/[0.03] transition-colors hover:bg-primary/[0.06] last:border-0" @click="openChunkDetail(item.chunkId)">
                    <td class="p-4"><strong class="block text-[13px] text-foreground">子块 C#{{ item.chunkNo }}</strong><span class="text-xs text-muted-foreground">{{ buildChunkRelationText(item) }}</span></td>
                    <td class="p-4"><strong class="block text-[13px] text-foreground">{{ item.sectionPath||'未识别章节' }}</strong><span class="text-xs text-muted-foreground">P#{{ item.parentBlockNo||'-' }} · 共 {{ item.parentChildCount||0 }} 子块</span></td>
                    <td class="px-3 py-4"><div class="flex flex-col gap-0.5"><span class="truncate text-xs text-muted-foreground">{{ item.sourceTypeName||'-' }}</span><div class="flex items-center gap-1"><component :is="chunkStatusIcon(normalizeCode(item.vectorStatus)||'0')" class="h-3.5 w-3.5 shrink-0" :class="chunkStatusTextClass(normalizeCode(item.vectorStatus)||'0')" /><span class="truncate text-xs font-medium" :class="chunkStatusTextClass(normalizeCode(item.vectorStatus)||'0')">{{ item.vectorStatusName||'-' }}</span></div></div></td>
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
              <span class="text-xs text-muted-foreground">解析任务 {{ ragSnapshot?.parseTaskId || parseArtifactQuery?.taskId || '-' }} · 索引任务 {{ ragSnapshot?.indexTaskId || '-' }}</span>
              <button class="rounded-full border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60" type="button" :disabled="ragSnapshotLoading" @click="loadDocumentRagSnapshot">{{ ragSnapshotLoading ? '读取中...' : '刷新快照' }}</button>
            </div>
          </div>

          <section class="mb-4 rounded-lg border border-border bg-card p-4">
            <div class="mb-3 flex items-start justify-between gap-3 max-md:flex-col">
              <div>
                <h3 class="text-sm font-semibold text-foreground">解析产物</h3>
                <p class="mt-0.5 text-xs text-muted-foreground">查看 Document Mind 原始结果、layout 标准化结果、标准 JSON 和 Markdown 投影。</p>
              </div>
              <div class="flex flex-wrap items-center gap-2">
                <span class="rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ parseArtifacts.length }} 个 artifact</span>
                <button class="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60" type="button" :disabled="parseArtifactsLoading" @click="loadParseArtifacts">
                  <ArrowPathIcon class="h-4 w-4" />
                  {{ parseArtifactsLoading ? '读取中...' : '刷新产物' }}
                </button>
              </div>
            </div>
            <div v-if="parseArtifactsLoading" class="rounded-md border border-dashed border-border bg-secondary py-5 text-center text-sm text-muted-foreground">正在读取解析产物...</div>
            <div v-else-if="!parseArtifacts.length" class="rounded-md border border-dashed border-border bg-secondary py-5 text-center text-sm text-muted-foreground">当前文档还没有可查看的解析产物，等待解析任务完成后再刷新。</div>
            <div v-else>
              <div class="mb-3 grid gap-2" style="grid-template-columns:repeat(auto-fit,minmax(140px,1fr))">
                <article v-for="stat in parseArtifactStats" :key="`parse-artifact-stat-${stat.label}`" class="rounded-md border border-border bg-secondary px-3 py-2.5">
                  <span class="text-[11px] text-muted-foreground">{{ stat.label }}</span>
                  <strong class="mt-1 block text-sm text-foreground">{{ stat.value }}</strong>
                </article>
              </div>
              <div class="overflow-x-auto rounded-lg border border-border">
                <table class="w-full min-w-[780px] border-collapse text-sm table-fixed">
                  <colgroup>
                    <col style="width:180px">
                    <col style="width:170px">
                    <col>
                    <col style="width:120px">
                    <col style="width:170px">
                  </colgroup>
                  <thead>
                    <tr class="bg-secondary">
                      <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">类型</th>
                      <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">解析器</th>
                      <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">对象 / Hash</th>
                      <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">大小</th>
                      <th class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="item in parseArtifacts" :key="item.artifactId" class="border-b border-border last:border-0">
                      <td class="px-4 py-3">
                        <strong class="block text-[13px] text-foreground">{{ item.artifactTypeName || item.artifactType }}</strong>
                        <span class="text-[11px] text-muted-foreground">{{ formatDateTime(item.createTime) }}</span>
                      </td>
                      <td class="px-4 py-3">
                        <span class="block truncate text-[13px] text-foreground">{{ item.parserName || '-' }}</span>
                        <span class="text-[11px] text-muted-foreground">{{ item.parserVersion || 'version -' }}</span>
                      </td>
                      <td class="px-4 py-3">
                        <code class="line-clamp-1 break-all text-[11px] text-foreground">{{ item.objectName || '-' }}</code>
                        <span class="mt-1 block truncate text-[11px] text-muted-foreground">{{ item.contentHash || 'hash -' }}</span>
                      </td>
                      <td class="px-4 py-3 text-[13px] font-semibold text-foreground">{{ formatBytes(item.size) }}</td>
                      <td class="px-4 py-3">
                        <div class="flex flex-wrap gap-1.5">
                          <button class="inline-flex items-center gap-1 rounded-md border border-border bg-card px-2.5 py-1.5 text-xs font-semibold text-foreground hover:bg-secondary disabled:opacity-50" type="button" :disabled="!item.viewable || artifactContentLoading" @click="openParseArtifact(item)">
                            <EyeIcon class="h-3.5 w-3.5" />
                            查看
                          </button>
                          <button class="inline-flex items-center gap-1 rounded-md border border-border bg-card px-2.5 py-1.5 text-xs font-semibold text-foreground hover:bg-secondary disabled:opacity-50" type="button" :disabled="artifactDownloadLoading" @click="downloadParseArtifact(item)">
                            <ArrowDownTrayIcon class="h-3.5 w-3.5" />
                            下载
                          </button>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </section>

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

            <section v-if="parserTrace" class="rounded-lg border border-[#0d7c7c]/20 bg-[#0d7c7c]/[0.04] p-4">
              <div class="mb-3 flex items-start justify-between gap-3 max-md:flex-col">
                <div>
                  <h3 class="text-sm font-semibold text-foreground">解析观测</h3>
                  <p class="mt-0.5 text-xs text-muted-foreground">{{ parserTraceSummary }}</p>
                </div>
                <div class="flex flex-wrap gap-1.5">
                  <span class="rounded-full bg-card px-2 py-0.5 text-[11px] text-foreground">{{ parserTrace.providerName || 'parser' }}</span>
                  <span v-if="parserTrace.jobId" class="rounded-full bg-card px-2 py-0.5 text-[11px] text-muted-foreground">Job {{ parserTrace.jobId }}</span>
                </div>
              </div>
              <div class="mb-3 grid gap-2" style="grid-template-columns:repeat(auto-fit,minmax(130px,1fr))">
                <article v-for="item in parserTraceStats" :key="`parser-trace-stat-${item.label}`" class="grid gap-1 rounded-md border border-border bg-card px-3 py-2.5">
                  <span class="text-[11px] text-muted-foreground">{{ item.label }}</span>
                  <strong class="text-sm text-foreground">{{ item.value }}</strong>
                  <span class="text-[11px] text-muted-foreground">{{ item.hint }}</span>
                </article>
              </div>
              <div class="grid gap-2 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
                <div class="rounded-md border border-border bg-card p-3">
                  <div class="mb-2 flex items-center justify-between gap-2">
                    <strong class="text-xs text-foreground">bbox 覆盖</strong>
                    <span class="text-[11px] text-muted-foreground">block / cell</span>
                  </div>
                  <div class="grid gap-2">
                    <div v-for="item in parserTraceCoverage" :key="`parser-trace-coverage-${item.label}`" class="grid grid-cols-[74px_minmax(0,1fr)_82px] items-center gap-2 text-xs">
                      <span class="text-muted-foreground">{{ item.label }}</span>
                      <div class="h-1.5 overflow-hidden rounded-full bg-secondary">
                        <div class="h-full rounded-full" :class="item.tone" :style="{ width: qualityBarWidth(item.value) }"></div>
                      </div>
                      <div class="text-right">
                        <strong class="block text-foreground">{{ formatPercent(item.value) }}</strong>
                        <span class="text-[10px] text-muted-foreground">{{ item.hint }}</span>
                      </div>
                    </div>
                  </div>
                </div>
                <div class="rounded-md border border-border bg-card p-3">
                  <div class="mb-2 flex items-center justify-between gap-2">
                    <strong class="text-xs text-foreground">Block 类型</strong>
                    <span class="text-[11px] text-muted-foreground">{{ formatCount(parserTrace.blockCount) }} 个 block</span>
                  </div>
                  <div v-if="parserTraceBlockTypes.length" class="flex flex-wrap gap-1.5">
                    <span v-for="item in parserTraceBlockTypes" :key="`parser-trace-type-${item.type}`" class="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-foreground">{{ item.type }} {{ formatCount(item.count) }}</span>
                  </div>
                  <p v-else class="text-xs text-muted-foreground">暂无类型分布。</p>
                </div>
              </div>
              <div v-if="parserTraceWarnings.length" class="mt-3 rounded-md border border-amber-500/20 bg-amber-500/[0.05] p-3">
                <strong class="mb-1.5 block text-xs text-foreground">解析 warning</strong>
                <p v-for="(item, index) in parserTraceWarnings" :key="`parser-trace-warning-${index}`" class="text-xs leading-5 text-muted-foreground">{{ index + 1 }}. {{ item }}</p>
              </div>
            </section>

            <section v-if="pageOverlays.length" ref="pageOverlaySectionRef" class="rounded-lg border border-border bg-card p-4">
              <div class="mb-3 flex items-start justify-between gap-3 max-md:flex-col">
                <div>
                  <h3 class="text-sm font-semibold text-foreground">页面定位</h3>
                  <p class="mt-0.5 text-xs text-muted-foreground">按 PAGE_IMAGE 页面底图查看 block 和 table 的 bbox 覆盖层，支持从样例卡片反向定位。</p>
                </div>
                <div class="flex flex-wrap items-center gap-2">
                  <span class="rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ pageOverlays.length }} 页</span>
                  <span class="rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ pageOverlayTotalCount }} 个 overlay</span>
                  <button v-if="selectedPageOverlay?.pageImageArtifactId" class="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60" type="button" :disabled="artifactContentLoading" @click="openPageOverlayArtifact(selectedPageOverlay)">
                    <EyeIcon class="h-4 w-4" />
                    查看页面图片
                  </button>
                </div>
              </div>

              <div class="grid gap-3 xl:grid-cols-[190px_minmax(0,1fr)_300px]">
                <aside class="rounded-md border border-border bg-secondary p-3">
                  <div class="mb-3">
                    <strong class="mb-2 block text-xs text-foreground">页码</strong>
                    <div class="grid max-h-56 gap-1.5 overflow-y-auto pr-1">
                      <button v-for="page in pageOverlays" :key="`page-overlay-tab-${page.pageNo}`"
                        class="flex items-center justify-between gap-2 rounded-md border px-2.5 py-2 text-left text-xs transition-colors"
                        :class="normalizeCode(selectedPageOverlayNo) === normalizeCode(page.pageNo) ? 'border-primary bg-primary/[0.08] text-primary' : 'border-border bg-card text-foreground hover:border-primary/30'"
                        type="button"
                        @click="selectPageOverlay(page.pageNo)">
                        <span class="font-semibold">第 {{ displayPageOverlayNo(page) }} 页</span>
                        <span class="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-muted-foreground">{{ asArray(page.overlays).length }}</span>
                      </button>
                    </div>
                  </div>
                  <div>
                    <strong class="mb-2 block text-xs text-foreground">类型筛选</strong>
                    <div class="flex flex-wrap gap-1.5">
                      <button v-for="option in PAGE_OVERLAY_TYPE_OPTIONS" :key="`page-overlay-type-${option.type}`"
                        class="inline-flex items-center gap-1 rounded-md border px-2.5 py-1.5 text-[11px] font-semibold transition-colors"
                        :class="pageOverlayTypeButtonClass(option.type)"
                        type="button"
                        @click="togglePageOverlayType(option.type)">
                        <span class="h-2 w-2 rounded-full" :class="pageOverlayTypeDotClass(option.type)"></span>
                        {{ option.label }}
                        <span class="font-normal opacity-75">{{ pageOverlayTypeCount(option.type) }}</span>
                      </button>
                    </div>
                  </div>
                </aside>

                <div class="rounded-md border border-border bg-secondary p-3">
                  <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
                    <div class="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                      <span>当前第 {{ displayPageOverlayNo(selectedPageOverlay) }} 页</span>
                      <span>{{ filteredPageOverlayRegions.length }} / {{ asArray(selectedPageOverlay?.overlays).length }} 个 overlay</span>
                      <span v-if="selectedPageOverlay?.pageWidth && selectedPageOverlay?.pageHeight">{{ formatDecimal(selectedPageOverlay.pageWidth) }} x {{ formatDecimal(selectedPageOverlay.pageHeight) }}</span>
                    </div>
                    <span v-if="selectedOverlayRegion" class="rounded-full bg-primary/[0.08] px-2.5 py-1 text-[11px] font-semibold text-primary">{{ selectedOverlayRegion.label || selectedOverlayRegion.overlayId }}</span>
                  </div>
                  <div v-if="pageOverlayImageLoading" class="grid min-h-[360px] place-items-center rounded-md border border-dashed border-border bg-card text-sm text-muted-foreground">正在读取页面底图...</div>
                  <div v-else-if="pageOverlayImageSrc" class="max-h-[72vh] overflow-auto rounded-md border border-border bg-card p-3">
                    <div class="relative mx-auto w-full max-w-[860px] overflow-hidden rounded border border-border bg-white shadow-sm" :style="{ aspectRatio: pageOverlayAspectRatio }">
                      <img class="absolute inset-0 h-full w-full object-fill" :src="pageOverlayImageSrc" :alt="`第 ${displayPageOverlayNo(selectedPageOverlay)} 页页面图像`" />
                      <button v-for="region in filteredPageOverlayRegions" :key="region.overlayId"
                        class="absolute rounded-[2px] border-2 outline-none transition-colors focus:ring-2 focus:ring-ring/40"
                        :class="pageOverlayRegionClass(region)"
                        :style="pageOverlayRegionStyle(region)"
                        type="button"
                        :title="pageOverlayRegionTitle(region)"
                        :aria-label="pageOverlayRegionTitle(region)"
                        @click="selectOverlayRegion(region)">
                      </button>
                    </div>
                  </div>
                  <div v-else class="grid min-h-[360px] place-items-center rounded-md border border-dashed border-border bg-card px-4 text-center text-sm text-muted-foreground">当前页没有可加载的 PAGE_IMAGE 底图，overlay 元数据仍可在右侧查看。</div>
                </div>

                <aside class="rounded-md border border-border bg-secondary p-3">
                  <div class="mb-2 flex items-center justify-between gap-2">
                    <strong class="text-xs text-foreground">当前页 overlay</strong>
                    <span class="text-[11px] text-muted-foreground">{{ filteredPageOverlayRegions.length }} 条</span>
                  </div>
                  <div v-if="!filteredPageOverlayRegions.length" class="rounded-md border border-dashed border-border bg-card py-5 text-center text-xs text-muted-foreground">当前筛选条件下没有 overlay。</div>
                  <div v-else class="grid max-h-[72vh] gap-2 overflow-y-auto pr-1">
                    <button v-for="region in filteredPageOverlayRegions" :key="`page-overlay-row-${region.overlayId}`"
                      class="rounded-md border p-2.5 text-left transition-colors"
                      :class="selectedOverlayId === region.overlayId ? 'border-primary bg-primary/[0.08]' : 'border-border bg-card hover:border-primary/30'"
                      type="button"
                      @click="selectOverlayRegion(region)">
                      <div class="mb-1 flex items-start justify-between gap-2">
                        <div class="min-w-0">
                          <strong class="block truncate text-xs text-foreground">{{ region.label || region.overlayId }}</strong>
                          <span class="text-[11px] text-muted-foreground">{{ region.sectionPath || region.source || '-' }}</span>
                        </div>
                        <span class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" :class="pageOverlayTypeBadgeClass(region.type)">{{ pageOverlayTypeLabel(region.type) }}</span>
                      </div>
                      <p class="line-clamp-2 text-[11px] leading-4 text-muted-foreground">{{ region.textPreview || region.bboxJson || '暂无预览' }}</p>
                    </button>
                  </div>
                </aside>
              </div>
            </section>

            <section v-if="artifactGraphNodes.length" class="rounded-lg border border-border bg-card p-4">
              <div class="mb-3 flex items-start justify-between gap-3 max-md:flex-col">
                <div>
                  <h3 class="text-sm font-semibold text-foreground">RAG 产物联动图</h3>
                  <p class="mt-0.5 text-xs text-muted-foreground">从解析块、父块、子块一路看到表格、KG evidence 和 RAPTOR 摘要来源。</p>
                </div>
                <div class="flex flex-wrap items-center gap-2">
                  <span v-for="metric in artifactGraphMetrics" :key="`artifact-graph-metric-${metric.label}`" class="rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ metric.label }} {{ metric.value }}</span>
                </div>
              </div>
              <div class="grid gap-3 xl:grid-cols-[minmax(0,1.4fr)_minmax(320px,0.8fr)]">
                <div class="grid gap-3">
                  <article v-for="group in artifactGraphNodeGroups" :key="`artifact-graph-group-${group.type}`" class="rounded-md border border-border bg-secondary p-3">
                    <div class="mb-2 flex items-center justify-between gap-2">
                      <div class="flex items-center gap-2">
                        <span class="h-2.5 w-2.5 rounded-full" :class="group.tone"></span>
                        <strong class="text-xs text-foreground">{{ group.label }}</strong>
                      </div>
                      <span class="text-[11px] text-muted-foreground">{{ formatCount(group.items.length) }} / {{ formatCount(artifactGraphTypeCount(group.type)) }}</span>
                    </div>
                    <div class="grid gap-2" style="grid-template-columns:repeat(auto-fill,minmax(190px,1fr))">
                      <button v-for="node in group.items" :key="`artifact-graph-node-${node.nodeId}`"
                        class="min-h-[92px] rounded-md border p-2.5 text-left transition-colors"
                        :class="normalizeCode(selectedArtifactGraphNode?.nodeId) === normalizeCode(node.nodeId) ? 'border-primary bg-primary/[0.08]' : 'border-border bg-card hover:border-primary/30'"
                        type="button"
                        @click="selectArtifactGraphNode(node)">
                        <div class="mb-1 flex items-start justify-between gap-2">
                          <strong class="line-clamp-1 text-xs text-foreground">{{ node.label || node.nodeId }}</strong>
                          <span class="shrink-0 rounded-full bg-secondary px-1.5 py-0.5 text-[10px] text-muted-foreground">{{ artifactGraphNodeTypeLabel(node.nodeType) }}</span>
                        </div>
                        <p class="line-clamp-1 text-[11px] text-muted-foreground">{{ node.subtitle || node.sectionPath || '-' }}</p>
                        <p class="mt-1 line-clamp-2 text-[11px] leading-4 text-muted-foreground">{{ node.textPreview || node.pageRange || '暂无预览' }}</p>
                      </button>
                    </div>
                  </article>
                </div>
                <aside class="rounded-md border border-border bg-secondary p-3">
                  <div class="mb-3 flex items-start justify-between gap-2">
                    <div class="min-w-0">
                      <strong class="block truncate text-sm text-foreground">{{ selectedArtifactGraphNode?.label || '未选择节点' }}</strong>
                      <p class="mt-0.5 text-xs text-muted-foreground">{{ artifactGraphNodeTypeLabel(selectedArtifactGraphNode?.nodeType) }} · {{ selectedArtifactGraphNode?.sectionPath || selectedArtifactGraphNode?.subtitle || '-' }}</p>
                    </div>
                    <button v-if="selectedArtifactGraphNode?.overlayId" class="inline-flex shrink-0 items-center gap-1 rounded-md border border-border bg-card px-2.5 py-1.5 text-xs font-semibold text-foreground hover:bg-secondary" type="button" @click="focusPageOverlay(selectedArtifactGraphNode.overlayId, selectedArtifactGraphNode.pageNo)">
                      <MagnifyingGlassIcon class="h-3.5 w-3.5" />
                      定位
                    </button>
                  </div>
                  <div v-if="selectedArtifactGraphNode" class="mb-3 grid gap-1.5 rounded-md border border-border bg-card p-3 text-xs">
                    <p class="text-muted-foreground">节点 ID：<span class="text-foreground">{{ selectedArtifactGraphNode.nodeId }}</span></p>
                    <p class="text-muted-foreground">来源 ID：<span class="text-foreground">{{ selectedArtifactGraphNode.sourceId || '-' }}</span></p>
                    <p class="text-muted-foreground">页码范围：<span class="text-foreground">{{ selectedArtifactGraphNode.pageRange || valueOrDash(selectedArtifactGraphNode.pageNo) }}</span></p>
                    <p class="line-clamp-4 text-muted-foreground">预览：<span class="text-foreground">{{ selectedArtifactGraphNode.textPreview || '-' }}</span></p>
                  </div>
                  <div>
                    <div class="mb-2 flex items-center justify-between gap-2">
                      <strong class="text-xs text-foreground">相邻关系</strong>
                      <span class="text-[11px] text-muted-foreground">{{ formatCount(selectedArtifactGraphEdges.length) }} 条</span>
                    </div>
                    <div v-if="selectedArtifactGraphNeighbors.length" class="grid max-h-[420px] gap-2 overflow-y-auto pr-1">
                      <button v-for="item in selectedArtifactGraphNeighbors" :key="`artifact-neighbor-${item.edge.edgeId}`" class="rounded-md border border-border bg-card p-2.5 text-left hover:border-primary/30" type="button" @click="selectArtifactGraphNode(item.node)">
                        <div class="mb-1 flex items-center justify-between gap-2">
                          <strong class="line-clamp-1 text-xs text-foreground">{{ item.node.label }}</strong>
                          <span class="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-muted-foreground">{{ item.direction === 'out' ? '下游' : '上游' }}</span>
                        </div>
                        <p class="line-clamp-1 text-[11px] text-muted-foreground">{{ item.edge.label || item.edge.edgeType }} · {{ artifactGraphNodeTypeLabel(item.node.nodeType) }}</p>
                      </button>
                    </div>
                    <p v-else class="rounded-md border border-dashed border-border bg-card py-5 text-center text-xs text-muted-foreground">当前节点没有相邻关系样例。</p>
                  </div>
                </aside>
              </div>
            </section>

            <section v-if="kgGraphNodes.length || kgGraphEdges.length || kgGraphEvidences.length" class="rounded-lg border border-border bg-card p-4">
              <div class="mb-3 flex items-start justify-between gap-3 max-md:flex-col">
                <div>
                  <h3 class="text-sm font-semibold text-foreground">GraphRAG 图谱</h3>
                  <p class="mt-0.5 text-xs text-muted-foreground">展示当前文档 KG 实体、关系、社区和证据；没有关系时仍保留实体与 evidence。</p>
                </div>
                <div class="flex flex-wrap items-center gap-2">
                  <span v-for="metric in kgGraphMetrics" :key="`kg-graph-metric-${metric.label}`" class="rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ metric.label }} {{ metric.value }}</span>
                </div>
              </div>
              <div class="mb-3 flex flex-wrap gap-2">
                <select v-model="selectedKgEntityType" class="h-9 rounded-md border border-border bg-card px-3 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20">
                  <option v-for="option in kgEntityTypeOptions" :key="`kg-type-${option.value}`" :value="option.value">{{ option.label }}</option>
                </select>
                <select v-model="selectedKgCommunityFilter" class="h-9 rounded-md border border-border bg-card px-3 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20">
                  <option v-for="option in kgCommunityOptions" :key="`kg-community-filter-${option.value}`" :value="option.value">{{ option.label }}</option>
                </select>
                <select v-model="selectedKgQualityLevel" class="h-9 rounded-md border border-border bg-card px-3 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20">
                  <option v-for="option in kgQualityLevelOptions" :key="`kg-quality-${option.value}`" :value="option.value">{{ option.label }}</option>
                </select>
              </div>
              <div class="grid gap-3 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
                <div class="grid gap-3">
                  <div class="rounded-md border border-border bg-secondary p-3">
                    <div class="mb-2 flex items-center justify-between gap-2">
                      <strong class="text-xs text-foreground">实体节点</strong>
                      <span class="text-[11px] text-muted-foreground">{{ formatCount(filteredKgGraphNodes.length) }} / {{ formatCount(kgGraphNodes.length) }}</span>
                    </div>
                    <div v-if="filteredKgGraphNodes.length" class="grid max-h-[430px] gap-2 overflow-y-auto pr-1" style="grid-template-columns:repeat(auto-fill,minmax(210px,1fr))">
                      <button v-for="node in filteredKgGraphNodes" :key="`kg-node-${node.nodeId}`"
                        class="rounded-md border p-2.5 text-left transition-colors"
                        :class="normalizeCode(selectedKgGraphNode?.nodeId) === normalizeCode(node.nodeId) && !selectedKgGraphEdge ? 'border-primary bg-primary/[0.08]' : 'border-border bg-card hover:border-primary/30'"
                        type="button"
                        @click="selectKgGraphNode(node)">
                        <div class="mb-1 flex items-start justify-between gap-2">
                          <strong class="line-clamp-1 text-xs text-foreground">{{ node.name || node.nodeId }}</strong>
                          <span class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" :class="kgQualityClass(node.qualityLevel)">{{ kgQualityLabel(node.qualityLevel) }}</span>
                        </div>
                        <p class="line-clamp-1 text-[11px] text-muted-foreground">{{ node.entityType || '未分类实体' }} · evidence {{ formatCount(node.evidenceCount) }}</p>
                        <p class="mt-1 line-clamp-2 text-[11px] leading-4 text-muted-foreground">{{ node.description || '暂无描述' }}</p>
                      </button>
                    </div>
                    <p v-else class="rounded-md border border-dashed border-border bg-card py-5 text-center text-xs text-muted-foreground">当前筛选条件下没有实体。</p>
                  </div>
                  <div class="rounded-md border border-border bg-secondary p-3">
                    <div class="mb-2 flex items-center justify-between gap-2">
                      <strong class="text-xs text-foreground">关系边</strong>
                      <span class="text-[11px] text-muted-foreground">{{ formatCount(filteredKgGraphEdges.length) }} / {{ formatCount(kgGraphEdges.length) }}</span>
                    </div>
                    <div v-if="filteredKgGraphEdges.length" class="grid gap-2">
                      <button v-for="edge in filteredKgGraphEdges.slice(0, 20)" :key="`kg-edge-${edge.edgeId}`"
                        class="rounded-md border p-2.5 text-left transition-colors"
                        :class="normalizeCode(selectedKgGraphEdge?.edgeId) === normalizeCode(edge.edgeId) ? 'border-primary bg-primary/[0.08]' : 'border-border bg-card hover:border-primary/30'"
                        type="button"
                        @click="selectKgGraphEdge(edge)">
                        <div class="mb-1 flex items-center justify-between gap-2">
                          <strong class="line-clamp-1 text-xs text-foreground">{{ kgEntityName(edge.sourceEntityId) }} -> {{ kgEntityName(edge.targetEntityId) }}</strong>
                          <span class="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-muted-foreground">{{ edge.relationType || 'relation' }}</span>
                        </div>
                        <p class="line-clamp-1 text-[11px] text-muted-foreground">权重 {{ edge.weight || '-' }} · evidence {{ formatCount(edge.evidenceCount) }} · {{ kgQualityLabel(edge.qualityLevel) }}</p>
                      </button>
                    </div>
                    <p v-else class="rounded-md border border-dashed border-border bg-card py-5 text-center text-xs text-muted-foreground">当前没有真实关系边；实体和 evidence 仍可用于排查抽取结果。</p>
                  </div>
                </div>
                <aside class="grid gap-3">
                  <div class="rounded-md border border-border bg-secondary p-3">
                    <div class="mb-2 flex items-start justify-between gap-2">
                      <div class="min-w-0">
                        <strong class="block truncate text-sm text-foreground">{{ selectedKgGraphEdge ? '关系证据' : (selectedKgGraphNode?.name || '实体证据') }}</strong>
                        <p class="mt-0.5 text-xs text-muted-foreground">
                          <template v-if="selectedKgGraphEdge">{{ kgEntityName(selectedKgGraphEdge.sourceEntityId) }} -> {{ kgEntityName(selectedKgGraphEdge.targetEntityId) }} · {{ selectedKgGraphEdge.relationType || 'relation' }}</template>
                          <template v-else>{{ selectedKgGraphNode?.entityType || '-' }} · {{ kgQualityLabel(selectedKgGraphNode?.qualityLevel) }}</template>
                        </p>
                      </div>
                      <span class="shrink-0 rounded-full bg-card px-2 py-0.5 text-[11px] text-muted-foreground">{{ formatCount(selectedKgGraphEvidences.length) }} evidence</span>
                    </div>
                    <div v-if="selectedKgGraphEvidences.length" class="grid max-h-[430px] gap-2 overflow-y-auto pr-1">
                      <article v-for="evidence in selectedKgGraphEvidences" :key="`kg-evidence-${evidence.evidenceId}`" class="rounded-md border border-border bg-card p-2.5">
                        <div class="mb-1 flex items-start justify-between gap-2">
                          <strong class="text-xs text-foreground">Evidence #{{ evidence.evidenceId || '-' }}</strong>
                          <div class="flex shrink-0 gap-1">
                            <button v-if="evidence.chunkId" class="rounded border border-border bg-secondary px-2 py-1 text-[11px] font-semibold text-foreground hover:bg-card" type="button" @click="openChunkDetail(evidence.chunkId)">Chunk</button>
                            <button v-if="evidence.bboxJson" class="rounded border border-border bg-secondary px-2 py-1 text-[11px] font-semibold text-foreground hover:bg-card" type="button" @click="focusKgEvidenceOverlay(evidence)">定位</button>
                          </div>
                        </div>
                        <p class="line-clamp-3 text-[11px] leading-4 text-muted-foreground">{{ evidence.quoteText || '暂无 quoteText' }}</p>
                        <p class="mt-1 line-clamp-1 text-[11px] text-muted-foreground">{{ compactList([evidence.sectionPath, evidence.pageRange, evidence.sourceType, evidence.grounded === true ? 'grounded' : '']).join(' · ') }}</p>
                      </article>
                    </div>
                    <p v-else class="rounded-md border border-dashed border-border bg-card py-5 text-center text-xs text-muted-foreground">当前选择没有 evidence 样例。</p>
                  </div>
                  <div class="rounded-md border border-border bg-secondary p-3">
                    <div class="mb-2 flex items-center justify-between gap-2">
                      <strong class="text-xs text-foreground">Community report</strong>
                      <span class="text-[11px] text-muted-foreground">{{ formatCount(kgGraphCommunities.length) }} 个</span>
                    </div>
                    <div v-if="kgGraphCommunities.length" class="grid gap-2">
                      <button v-for="community in kgGraphCommunities.slice(0, 8)" :key="`kg-community-${community.communityId}`"
                        class="rounded-md border p-2.5 text-left transition-colors"
                        :class="normalizeCode(selectedKgGraphCommunity?.communityId) === normalizeCode(community.communityId) ? 'border-primary bg-primary/[0.08]' : 'border-border bg-card hover:border-primary/30'"
                        type="button"
                        @click="selectKgGraphCommunity(community)">
                        <div class="mb-1 flex items-start justify-between gap-2">
                          <strong class="line-clamp-1 text-xs text-foreground">{{ community.title || `社区 #${valueOrDash(community.communityNo)}` }}</strong>
                          <span class="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-muted-foreground">rank {{ community.rankScore || '-' }}</span>
                        </div>
                        <p class="line-clamp-2 text-[11px] text-muted-foreground">{{ community.summary || '暂无社区摘要' }}</p>
                        <p class="mt-1 text-[11px] text-muted-foreground">实体 {{ formatCount(community.entityCount) }} · 关系 {{ formatCount(community.relationCount) }} · 证据 {{ formatCount(community.evidenceCount) }}</p>
                      </button>
                    </div>
                    <p v-else class="rounded-md border border-dashed border-border bg-card py-5 text-center text-xs text-muted-foreground">当前文档还没有 community report。</p>
                  </div>
                </aside>
              </div>
            </section>

            <section v-if="raptorTreeNodes.length" class="rounded-lg border border-border bg-card p-4">
              <div class="mb-3 flex items-start justify-between gap-3 max-md:flex-col">
                <div>
                  <h3 class="text-sm font-semibold text-foreground">RAPTOR 摘要树</h3>
                  <p class="mt-0.5 text-xs text-muted-foreground">按层级查看摘要节点，选中节点后下钻到 source chunks 和 ParentBlock。</p>
                </div>
                <div class="flex flex-wrap items-center gap-2">
                  <span v-for="metric in raptorTreeMetrics" :key="`raptor-tree-metric-${metric.label}`" class="rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ metric.label }} {{ metric.value }}</span>
                </div>
              </div>
              <div class="mb-3 flex flex-wrap gap-2">
                <select v-model="selectedRaptorQualityLevel" class="h-9 rounded-md border border-border bg-card px-3 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20">
                  <option v-for="option in raptorQualityLevelOptions" :key="`raptor-filter-quality-${option.value}`" :value="option.value">{{ option.label }}</option>
                </select>
                <select v-model="selectedRaptorSummaryStatus" class="h-9 rounded-md border border-border bg-card px-3 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20">
                  <option v-for="option in raptorSummaryStatusOptions" :key="`raptor-filter-status-${option.value}`" :value="option.value">{{ option.label }}</option>
                </select>
                <select v-model="selectedRaptorSummaryStrategy" class="h-9 rounded-md border border-border bg-card px-3 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20">
                  <option v-for="option in raptorSummaryStrategyOptions" :key="`raptor-filter-strategy-${option.value}`" :value="option.value">{{ option.label }}</option>
                </select>
              </div>
              <div class="grid gap-3 xl:grid-cols-[minmax(0,1.05fr)_minmax(330px,0.95fr)]">
                <div class="rounded-md border border-border bg-secondary p-3">
                  <div class="mb-2 flex items-center justify-between gap-2">
                    <strong class="text-xs text-foreground">层级树</strong>
                    <span class="text-[11px] text-muted-foreground">{{ formatCount(filteredRaptorTreeNodes.length) }} / {{ formatCount(raptorTreeNodes.length) }}</span>
                  </div>
                  <div v-if="raptorTreeLevelGroups.length" class="grid max-h-[620px] gap-3 overflow-y-auto pr-1">
                    <article v-for="group in raptorTreeLevelGroups" :key="`raptor-level-group-${group.label}`" class="rounded-md border border-border bg-card p-3">
                      <div class="mb-2 flex items-center justify-between gap-2">
                        <strong class="text-xs text-foreground">{{ group.label }}</strong>
                        <span class="text-[11px] text-muted-foreground">{{ formatCount(group.items.length) }} 节点</span>
                      </div>
                      <div class="grid gap-2">
                        <button v-for="node in group.items" :key="`raptor-tree-node-${node.nodeId}`"
                          class="rounded-md border p-2.5 text-left transition-colors"
                          :class="normalizeCode(selectedRaptorTreeNode?.nodeId) === normalizeCode(node.nodeId) ? 'border-primary bg-primary/[0.08]' : 'border-border bg-secondary hover:border-primary/30'"
                          type="button"
                          @click="selectRaptorTreeNode(node)">
                          <div class="mb-1 flex items-start justify-between gap-2">
                            <strong class="line-clamp-1 text-xs text-foreground">{{ node.title || `RAPTOR N#${valueOrDash(node.nodeNo)}` }}</strong>
                            <span class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" :class="raptorNodeQualityClass(node.qualityLevel)">{{ raptorNodeQualityLabel(node.qualityLevel) || valueOrDash(node.qualityLevel) }}</span>
                          </div>
                          <p class="line-clamp-2 text-[11px] leading-4 text-muted-foreground">{{ node.summary || '暂无摘要' }}</p>
                          <p class="mt-1 text-[11px] text-muted-foreground">{{ raptorScopeLabel(node.scopeType) }} · source chunk {{ formatCount(node.sourceChunkCount) }} · child {{ formatCount(node.childNodeCount) }}</p>
                        </button>
                      </div>
                    </article>
                  </div>
                  <p v-else class="rounded-md border border-dashed border-border bg-card py-5 text-center text-xs text-muted-foreground">当前筛选条件下没有 RAPTOR 节点。</p>
                </div>
                <aside class="rounded-md border border-border bg-secondary p-3">
                  <div class="mb-3 flex items-start justify-between gap-2">
                    <div class="min-w-0">
                      <strong class="block truncate text-sm text-foreground">{{ selectedRaptorTreeNode?.title || '未选择摘要节点' }}</strong>
                      <p class="mt-0.5 text-xs text-muted-foreground">{{ selectedRaptorTreeNode ? `${raptorScopeLabel(selectedRaptorTreeNode.scopeType)} · L${valueOrDash(selectedRaptorTreeNode.nodeLevel)} · N#${valueOrDash(selectedRaptorTreeNode.nodeNo)}` : '-' }}</p>
                    </div>
                    <span v-if="selectedRaptorTreeNode" class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold" :class="raptorNodeQualityClass(selectedRaptorTreeNode.qualityLevel)">{{ raptorNodeQualityLabel(selectedRaptorTreeNode.qualityLevel) || valueOrDash(selectedRaptorTreeNode.qualityLevel) }}</span>
                  </div>
                  <div v-if="selectedRaptorTreeNode" class="grid gap-3">
                    <div class="rounded-md border border-border bg-card p-3">
                      <div class="mb-2 flex items-center justify-between gap-2 text-xs">
                        <span class="text-muted-foreground">摘要质量</span>
                        <strong class="text-foreground">{{ formatPercent(selectedRaptorTreeNode.qualityScore) }}</strong>
                      </div>
                      <div class="mb-2 h-1.5 overflow-hidden rounded-full bg-secondary">
                        <div class="h-full rounded-full" :class="raptorNodeQualityBarClass(selectedRaptorTreeNode.qualityLevel)" :style="{ width: qualityBarWidth(selectedRaptorTreeNode.qualityScore) }"></div>
                      </div>
                      <p v-if="selectedRaptorTreeNode.qualityRisk" class="text-[11px] leading-4 text-muted-foreground">{{ selectedRaptorTreeNode.qualityRisk }}</p>
                      <div class="mt-2 flex flex-wrap gap-1.5">
                        <span v-for="chip in raptorTreeNodeChips(selectedRaptorTreeNode)" :key="`selected-raptor-chip-${chip}`" class="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-foreground">{{ chip }}</span>
                      </div>
                    </div>
                    <div class="rounded-md border border-border bg-card p-3">
                      <strong class="mb-1 block text-xs text-foreground">摘要内容</strong>
                      <p class="whitespace-pre-wrap break-words text-[13px] leading-6 text-foreground">{{ selectedRaptorTreeNode.summary || '暂无摘要' }}</p>
                      <p v-if="selectedRaptorTreeNode.treePath" class="mt-2 line-clamp-2 text-[11px] text-muted-foreground">树路径：{{ selectedRaptorTreeNode.treePath }}</p>
                    </div>
                    <div class="grid gap-2 md:grid-cols-2">
                      <div class="rounded-md border border-border bg-card p-3">
                        <div class="mb-2 flex items-center justify-between gap-2">
                          <strong class="text-xs text-foreground">Source chunks</strong>
                          <span class="text-[11px] text-muted-foreground">{{ formatCount(asArray(selectedRaptorTreeNode.sourceChunks).length) }}</span>
                        </div>
                        <div v-if="asArray(selectedRaptorTreeNode.sourceChunks).length" class="grid max-h-56 gap-2 overflow-y-auto pr-1">
                          <button v-for="chunk in asArray(selectedRaptorTreeNode.sourceChunks)" :key="`raptor-tree-source-chunk-${chunk.chunkId}`" class="rounded-md border border-border bg-secondary p-2 text-left hover:border-primary/30" type="button" @click="openChunkDetail(chunk.chunkId)">
                            <span class="block text-xs font-semibold text-foreground">C#{{ valueOrDash(chunk.chunkNo) }} · {{ chunk.sectionPath || '未识别章节' }}</span>
                            <span class="mt-1 line-clamp-2 text-[11px] text-muted-foreground">{{ chunk.textPreview || '暂无原文预览' }}</span>
                          </button>
                        </div>
                        <p v-else class="text-xs text-muted-foreground">暂无 source chunk 样例。</p>
                      </div>
                      <div class="rounded-md border border-border bg-card p-3">
                        <div class="mb-2 flex items-center justify-between gap-2">
                          <strong class="text-xs text-foreground">Source parents</strong>
                          <span class="text-[11px] text-muted-foreground">{{ formatCount(asArray(selectedRaptorTreeNode.sourceParentBlocks).length) }}</span>
                        </div>
                        <div v-if="asArray(selectedRaptorTreeNode.sourceParentBlocks).length" class="grid max-h-56 gap-2 overflow-y-auto pr-1">
                          <article v-for="parent in asArray(selectedRaptorTreeNode.sourceParentBlocks)" :key="`raptor-tree-source-parent-${parent.parentBlockId}`" class="rounded-md border border-border bg-secondary p-2">
                            <span class="block text-xs font-semibold text-foreground">P#{{ valueOrDash(parent.parentNo) }} · {{ parent.sectionPath || '未识别章节' }}</span>
                            <span class="mt-1 line-clamp-2 text-[11px] text-muted-foreground">{{ parent.textPreview || '暂无父块预览' }}</span>
                          </article>
                        </div>
                        <p v-else class="text-xs text-muted-foreground">暂无 source parent 样例。</p>
                      </div>
                    </div>
                    <div class="rounded-md border border-border bg-card p-3">
                      <div class="mb-2 flex items-center justify-between gap-2">
                        <strong class="text-xs text-foreground">下级摘要节点</strong>
                        <span class="text-[11px] text-muted-foreground">{{ formatCount(selectedRaptorTreeChildren.length) }}</span>
                      </div>
                      <div v-if="selectedRaptorTreeChildren.length" class="grid gap-2">
                        <button v-for="child in selectedRaptorTreeChildren" :key="`raptor-tree-child-${child.nodeId}`" class="rounded-md border border-border bg-secondary p-2 text-left hover:border-primary/30" type="button" @click="selectRaptorTreeNode(child)">
                          <span class="block text-xs font-semibold text-foreground">L{{ valueOrDash(child.nodeLevel) }} · {{ child.title || `N#${valueOrDash(child.nodeNo)}` }}</span>
                          <span class="mt-1 line-clamp-1 text-[11px] text-muted-foreground">{{ child.summary || '-' }}</span>
                        </button>
                      </div>
                      <p v-else class="text-xs text-muted-foreground">当前节点没有下级摘要节点，或下级节点被筛选条件隐藏。</p>
                    </div>
                  </div>
                </aside>
              </div>
            </section>

            <section v-if="raptorQualityReport" class="rounded-lg border p-4" :class="raptorQualityPanelClass(raptorQualityReport.qualityLevel)">
              <div class="mb-3 flex items-start justify-between gap-3 max-md:flex-col">
                <div>
                  <h3 class="text-sm font-semibold text-foreground">RAPTOR 摘要质量评测</h3>
                  <p class="mt-0.5 text-xs text-muted-foreground">{{ raptorQualityReport.summary }}</p>
                </div>
                <span class="inline-flex rounded-full px-3 py-1.5 text-xs font-semibold" :class="raptorQualityBadgeClass(raptorQualityReport.qualityLevel)">{{ raptorQualityLevelLabel(raptorQualityReport.qualityLevel) }}</span>
              </div>
              <div class="mb-3 grid gap-2" style="grid-template-columns:repeat(auto-fit,minmax(130px,1fr))">
                <article v-for="item in raptorQualityStats" :key="`raptor-quality-stat-${item.label}`" class="grid gap-1 rounded-md border border-border bg-card px-3 py-2.5">
                  <span class="text-[11px] text-muted-foreground">{{ item.label }}</span>
                  <strong class="text-sm text-foreground">{{ item.value }}</strong>
                  <span class="text-[11px] text-muted-foreground">{{ item.hint }}</span>
                </article>
              </div>
              <div class="mb-3 grid gap-2 md:grid-cols-[minmax(0,0.95fr)_minmax(0,1.05fr)]">
                <div class="rounded-md border border-border bg-card p-3">
                  <div class="mb-2 flex items-center justify-between gap-2">
                    <strong class="text-xs text-foreground">质量分布</strong>
                    <span class="text-[11px] text-muted-foreground">min / p10 / p50 / p90</span>
                  </div>
                  <div class="grid gap-2">
                    <div v-for="point in raptorQualityDistribution" :key="`raptor-quality-point-${point.label}`" class="grid grid-cols-[44px_minmax(0,1fr)_44px] items-center gap-2 text-xs">
                      <span class="text-muted-foreground">{{ point.label }}</span>
                      <div class="h-1.5 overflow-hidden rounded-full bg-secondary">
                        <div class="h-full rounded-full bg-primary" :style="{ width: qualityBarWidth(point.value) }"></div>
                      </div>
                      <strong class="text-right text-foreground">{{ formatPercent(point.value) }}</strong>
                    </div>
                  </div>
                </div>
                <div class="rounded-md border border-border bg-card p-3">
                  <div class="mb-2 flex items-center justify-between gap-2">
                    <strong class="text-xs text-foreground">层级质量桶</strong>
                    <span class="text-[11px] text-muted-foreground">{{ formatCount(asArray(raptorQualityReport.levelBuckets).length) }} 层</span>
                  </div>
                  <div v-if="asArray(raptorQualityReport.levelBuckets).length" class="grid gap-2">
                    <div v-for="bucket in asArray(raptorQualityReport.levelBuckets)" :key="`raptor-quality-bucket-${bucket.level}`" class="rounded-md border border-border bg-secondary px-3 py-2">
                      <div class="mb-1 flex items-center justify-between gap-2 text-xs">
                        <strong class="text-foreground">L{{ valueOrDash(bucket.level) }}</strong>
                        <span class="text-muted-foreground">{{ formatCount(bucket.nodeCount) }} 节点 · 均值 {{ formatPercent(bucket.averageQualityScore) }}</span>
                      </div>
                      <div class="h-1.5 overflow-hidden rounded-full bg-card">
                        <div class="h-full rounded-full bg-primary" :style="{ width: qualityBarWidth(bucket.averageQualityScore) }"></div>
                      </div>
                    </div>
                  </div>
                  <p v-else class="text-xs text-muted-foreground">暂无层级质量桶。</p>
                </div>
              </div>
              <div class="rounded-md border border-border bg-card p-3">
                <strong class="mb-2 block text-xs text-foreground">阈值调优建议</strong>
                <div class="grid gap-1.5">
                  <p v-for="(item, index) in asArray(raptorQualityReport.tuningSuggestions)" :key="`raptor-quality-suggestion-${index}`" class="text-xs leading-5 text-muted-foreground">{{ index + 1 }}. {{ item }}</p>
                </div>
              </div>
            </section>

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
                      <button v-if="record.canLocate" class="inline-flex items-center gap-1 rounded-md border border-border bg-card px-2.5 py-1.5 text-xs font-semibold text-foreground hover:bg-secondary" type="button" @click.stop="focusPageOverlay(record.overlayId, record.pageNo)">
                        <MagnifyingGlassIcon class="h-3.5 w-3.5" />
                        定位
                      </button>
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
              <div v-else-if="section.key === 'raptor'" class="grid gap-3">
                <article v-for="record in section.records" :key="`raptor-node-${record.nodeId || record.nodeKey}`" class="rounded-lg border border-border bg-secondary p-3">
                  <div class="grid gap-3 lg:grid-cols-[minmax(0,0.95fr)_minmax(0,1.45fr)]">
                    <div class="grid gap-2">
                      <div class="flex items-start justify-between gap-3">
                        <div>
                          <div class="flex flex-wrap items-center gap-1.5">
                            <span class="rounded-full px-2 py-0.5 text-[11px] font-semibold" :class="raptorLevelClass(record.nodeLevel)">L{{ valueOrDash(record.nodeLevel) }}</span>
                            <strong class="text-[13px] text-foreground">{{ record.title }}</strong>
                          </div>
                          <p class="mt-1 text-xs text-muted-foreground">{{ record.subtitle }}</p>
                        </div>
                        <span class="rounded-full bg-card px-2 py-0.5 text-[11px] text-foreground">N#{{ valueOrDash(record.nodeNo) }}</span>
                      </div>
                      <div class="grid gap-2 rounded-md border border-border bg-card p-2.5 text-xs">
                        <div class="flex items-center justify-between gap-2">
                          <span class="text-muted-foreground">摘要质量</span>
                          <div class="flex items-center gap-2">
                            <span class="rounded-full px-2 py-0.5 text-[11px] font-semibold" :class="raptorNodeQualityClass(record.qualityLevel)">{{ raptorNodeQualityLabel(record.qualityLevel) }}</span>
                            <strong class="text-foreground">{{ formatPercent(record.qualityScore) }}</strong>
                          </div>
                        </div>
                        <div class="h-1.5 overflow-hidden rounded-full bg-secondary">
                          <div class="h-full rounded-full" :class="raptorNodeQualityBarClass(record.qualityLevel)" :style="{ width: qualityBarWidth(record.qualityScore) }"></div>
                        </div>
                        <p v-if="record.qualityRisk" class="text-[11px] leading-4 text-muted-foreground">{{ record.qualityRisk }}</p>
                        <div class="flex flex-wrap gap-1.5">
                          <span v-for="chip in record.chips.filter(Boolean).slice(0,6)" :key="`raptor-chip-${record.nodeId}-${chip}`" class="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-foreground">{{ chip }}</span>
                        </div>
                        <div class="grid grid-cols-2 gap-1.5 text-[11px] text-muted-foreground">
                          <span>簇均值 {{ formatDecimal(record.avgClusterSize) }}</span>
                          <span>最大簇 {{ formatCount(record.maxClusterSizeObserved) }}</span>
                          <span>压缩 {{ formatPercent(record.levelCompressionRatio) }}</span>
                          <span>平衡 {{ formatPercent(record.treeBalanceScore) }}</span>
                        </div>
                      </div>
                      <div class="grid gap-1.5 text-xs text-muted-foreground">
                        <p v-if="record.treePath" class="line-clamp-2">树路径：{{ record.treePath }}</p>
                        <p>摘要节点 ID：{{ record.nodeId || '-' }}</p>
                        <p>父节点：{{ record.parentNodeId || '根节点' }} · 子节点 {{ formatCount(record.childNodeCount) }}</p>
                        <p>覆盖 source chunk {{ formatCount(record.sourceChunkCount) }} · ParentBlock {{ formatCount(record.sourceParentBlockCount) }}</p>
                      </div>
                    </div>
                    <div class="grid gap-3">
                      <div class="rounded-md border border-border bg-card p-3">
                        <div class="mb-1 flex flex-wrap items-center gap-2">
                          <strong class="text-xs text-foreground">摘要内容</strong>
                          <span v-if="record.abstractive" class="rounded-full bg-primary/[0.08] px-2 py-0.5 text-[11px] font-semibold text-primary">LLM 抽象摘要</span>
                          <span v-if="record.llmSummaryStatus" class="rounded-full bg-secondary px-2 py-0.5 text-[11px] text-muted-foreground">{{ record.llmSummaryStatus }}</span>
                        </div>
                        <p class="whitespace-pre-wrap break-words text-[13px] leading-6 text-foreground">{{ record.body }}</p>
                      </div>
                      <div class="grid gap-2 md:grid-cols-2">
                        <div class="rounded-md border border-border bg-card p-3">
                          <div class="mb-2 flex items-center justify-between gap-2">
                            <strong class="text-xs text-foreground">下钻原文 Chunk</strong>
                            <span class="text-[11px] text-muted-foreground">{{ formatCount(record.sourceChunks.length) }} 条样例</span>
                          </div>
                          <div v-if="record.sourceChunks.length" class="grid gap-2">
                            <button v-for="chunk in record.sourceChunks" :key="`raptor-source-chunk-${record.nodeId}-${chunk.chunkId}`" class="rounded-md border border-border bg-secondary p-2 text-left transition-colors hover:border-primary/30 hover:bg-primary/[0.04]" type="button" @click="openChunkDetail(chunk.chunkId)">
                              <span class="block text-xs font-semibold text-foreground">C#{{ valueOrDash(chunk.chunkNo) }} · {{ chunk.sectionPath || '未识别章节' }}</span>
                              <span class="mt-1 line-clamp-2 text-[11px] text-muted-foreground">{{ chunk.textPreview || '暂无原文预览' }}</span>
                            </button>
                          </div>
                          <p v-else class="text-xs text-muted-foreground">暂无可展示 chunk 样例。</p>
                        </div>
                        <div class="rounded-md border border-border bg-card p-3">
                          <div class="mb-2 flex items-center justify-between gap-2">
                            <strong class="text-xs text-foreground">下钻 ParentBlock</strong>
                            <span class="text-[11px] text-muted-foreground">{{ formatCount(record.sourceParentBlocks.length) }} 条样例</span>
                          </div>
                          <div v-if="record.sourceParentBlocks.length" class="grid gap-2">
                            <div v-for="parent in record.sourceParentBlocks" :key="`raptor-source-parent-${record.nodeId}-${parent.parentBlockId}`" class="rounded-md border border-border bg-secondary p-2">
                              <span class="block text-xs font-semibold text-foreground">P#{{ valueOrDash(parent.parentNo) }} · {{ parent.sectionPath || '未识别章节' }}</span>
                              <span class="mt-1 line-clamp-2 text-[11px] text-muted-foreground">{{ parent.textPreview || '暂无父块预览' }}</span>
                            </div>
                          </div>
                          <p v-else class="text-xs text-muted-foreground">暂无可展示 ParentBlock 样例。</p>
                        </div>
                      </div>
                    </div>
                  </div>
                </article>
              </div>
              <div v-else class="grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(260px,1fr))">
                <article v-for="(record, index) in section.records" :key="`${section.key}-${index}`" class="grid gap-2 rounded-lg border border-border bg-secondary p-3">
                  <div class="flex items-start justify-between gap-2">
                    <div class="min-w-0">
                      <strong class="block truncate text-[13px] text-foreground">{{ record.title }}</strong>
                      <p class="mt-0.5 line-clamp-1 text-xs text-muted-foreground">{{ record.subtitle }}</p>
                    </div>
                    <button v-if="record.canLocate" class="inline-flex shrink-0 items-center gap-1 rounded-md border border-border bg-card px-2.5 py-1.5 text-xs font-semibold text-foreground hover:bg-secondary" type="button" @click.stop="focusPageOverlay(record.overlayId, record.pageNo)">
                      <MagnifyingGlassIcon class="h-3.5 w-3.5" />
                      定位
                    </button>
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
import { ArrowDownTrayIcon, ArrowLeftIcon, ArrowPathIcon, ArrowRightIcon, CheckCircleIcon, ClipboardDocumentIcon, ClockIcon, ExclamationCircleIcon, EyeIcon, MagnifyingGlassIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import { APIError, manageApi } from '../../api/api'
import AdminStatusBadge from '../../components/admin/AdminStatusBadge.vue'
import DocumentParseRouteProgressDialog from '../../components/admin/DocumentParseRouteProgressDialog.vue'
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
const ARTIFACT_PREVIEW_LIMIT = 12000
const PAGE_OVERLAY_TYPE_OPTIONS = [
  { type: 'TITLE', label: '标题' },
  { type: 'TEXT', label: '正文' },
  { type: 'TABLE', label: '表格' },
  { type: 'FIGURE', label: '图示' },
  { type: 'IMAGE', label: '图片' }
]
const DEFAULT_PAGE_OVERLAY_TYPES = PAGE_OVERLAY_TYPE_OPTIONS.map((item) => item.type)

const strategyLibrary = STRATEGY_LIBRARY
const strategyPipelineLibrary = STRATEGY_PIPELINE_LIBRARY

const BUILD_STAGE_LIBRARY = [
  { code: '5', order: '01', label: '切块执行', description: '按照当前策略链路生成原始 chunk' },
  { code: '6', order: '02', label: '切块后处理', description: '清洗空块并整理最终可入库片段' },
  { code: '7', order: '03', label: '向量化', description: '生成 embedding 并写入 PGVector' },
  { code: '9', order: '04', label: '关键词索引', description: '写入 Elasticsearch BM25 关键词索引' },
  { code: '10', order: '05', label: 'GraphRAG', description: '抽取并保存实体、关系、证据和社区摘要' },
  { code: '11', order: '06', label: 'Graph 派生索引', description: '将图谱实体、关系、社区投影为可召回 chunk' },
  { code: '12', order: '07', label: 'RAPTOR', description: '构建长文档层级摘要树和摘要向量' },
  { code: '8', order: '08', label: '入库完成', description: '回写状态并将本次索引标记为可用' }
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
const parseArtifactQuery = ref(null)
const artifactContent = ref(null)
const pageOverlayImageContent = ref(null)
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
const parseArtifactsLoading = ref(false)
const artifactContentLoading = ref(false)
const artifactDownloadLoading = ref(false)
const pageOverlayImageLoading = ref(false)
const logDrawerOpen = ref(false)
const chunkDetailDrawerOpen = ref(false)
const artifactPreviewDrawerOpen = ref(false)
const artifactPreviewCollapsed = ref(false)
const artifactSearchKeyword = ref('')
const selectedPageOverlayNo = ref(null)
const selectedOverlayTypes = ref([...DEFAULT_PAGE_OVERLAY_TYPES])
const selectedOverlayId = ref('')
const selectedArtifactGraphNodeId = ref('')
const selectedKgGraphNodeId = ref('')
const selectedKgGraphEdgeId = ref('')
const selectedKgGraphCommunityId = ref('')
const selectedKgEntityType = ref('ALL')
const selectedKgQualityLevel = ref('ALL')
const selectedKgCommunityFilter = ref('ALL')
const selectedRaptorTreeNodeId = ref('')
const selectedRaptorQualityLevel = ref('ALL')
const selectedRaptorSummaryStatus = ref('ALL')
const selectedRaptorSummaryStrategy = ref('ALL')
const pageOverlayImageRequestSeq = ref(0)
const planPollTimer = ref(null)
const buildPollTimer = ref(null)
const parseRouteDialogOpen = ref(false)
const parseRouteDialogTaskId = ref('')
const buildProgressLatestLogId = ref(null)
const buildProgressNoticeStage = ref(null)
const buildTrackerRef = ref(null)
const parentBlockSectionRef = ref(null)
const overviewSectionRef = ref(null)
const strategySectionRef = ref(null)
const executionSectionRef = ref(null)
const chunkSectionRef = ref(null)
const ragSectionRef = ref(null)
const pageOverlaySectionRef = ref(null)
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
const parserTrace = computed(() => ragSnapshot.value?.parserTrace || null)
const parseArtifacts = computed(() => asArray(parseArtifactQuery.value?.artifacts))
const parseArtifactStats = computed(() => {
  const totalSize = parseArtifacts.value.reduce((sum, item) => sum + Number(item.size || 0), 0)
  const viewableCount = parseArtifacts.value.filter((item) => item.viewable).length
  const typeCount = new Set(parseArtifacts.value.map((item) => item.artifactType).filter(Boolean)).size
  return [
    { label: '产物类型', value: `${formatCount(typeCount)} 类` },
    { label: '可预览', value: `${formatCount(viewableCount)} 个` },
    { label: '总大小', value: formatBytes(totalSize) },
    { label: '解析任务', value: parseArtifactQuery.value?.taskId || '-' }
  ]
})
const artifactPreviewItem = computed(() => artifactContent.value?.artifact || null)
const formattedArtifactContent = computed(() => {
  const content = String(artifactContent.value?.content || '')
  if (!content || artifactContent.value?.json !== true) {
    return content
  }
  try {
    return JSON.stringify(JSON.parse(content), null, 2)
  } catch {
    return content
  }
})
const artifactCanCollapse = computed(() => formattedArtifactContent.value.length > ARTIFACT_PREVIEW_LIMIT)
const visibleArtifactContent = computed(() => {
  const content = formattedArtifactContent.value
  if (!artifactPreviewCollapsed.value || content.length <= ARTIFACT_PREVIEW_LIMIT) {
    return content
  }
  return `${content.slice(0, ARTIFACT_PREVIEW_LIMIT)}\n...`
})
const artifactSearchMatchCount = computed(() => countTextMatches(formattedArtifactContent.value, artifactSearchKeyword.value))
const artifactJsonPathRows = computed(() => buildArtifactJsonPathRows(formattedArtifactContent.value, artifactSearchKeyword.value))
const artifactIsImage = computed(() => artifactContent.value?.image === true || String(artifactPreviewItem.value?.contentType || '').toLowerCase().startsWith('image/'))
const artifactImageSrc = computed(() => {
  const directUrl = String(artifactContent.value?.dataUrl || '')
  if (directUrl) {
    return directUrl
  }
  const imageBase64 = String(artifactContent.value?.imageBase64 || '')
  if (!imageBase64) {
    return ''
  }
  const contentType = String(artifactPreviewItem.value?.contentType || 'image/png').split(';')[0] || 'image/png'
  return `data:${contentType};base64,${imageBase64}`
})
const pageOverlays = computed(() => asArray(ragSnapshot.value?.pageOverlays))
const pageOverlayTotalCount = computed(() => {
  return pageOverlays.value.reduce((sum, page) => sum + asArray(page?.overlays).length, 0)
})
const selectedPageOverlay = computed(() => {
  if (!pageOverlays.value.length) {
    return null
  }
  const selectedKey = normalizeCode(selectedPageOverlayNo.value)
  return pageOverlays.value.find((page) => normalizeCode(page.pageNo) === selectedKey) || pageOverlays.value[0]
})
const selectedPageOverlayTypeSet = computed(() => new Set(selectedOverlayTypes.value.map((item) => normalizeCode(item).toUpperCase()).filter(Boolean)))
const filteredPageOverlayRegions = computed(() => {
  const typeSet = selectedPageOverlayTypeSet.value
  return asArray(selectedPageOverlay.value?.overlays)
    .filter((region) => typeSet.has(normalizeCode(region?.type).toUpperCase()))
})
const selectedOverlayRegion = computed(() => {
  const selectedId = normalizeCode(selectedOverlayId.value)
  if (!selectedId) {
    return null
  }
  return asArray(selectedPageOverlay.value?.overlays).find((region) => normalizeCode(region?.overlayId) === selectedId) || null
})
const pageOverlayImageSrc = computed(() => {
  const directUrl = String(pageOverlayImageContent.value?.dataUrl || '')
  if (directUrl) {
    return directUrl
  }
  const imageBase64 = String(pageOverlayImageContent.value?.imageBase64 || '')
  if (!imageBase64) {
    return ''
  }
  const contentType = String(pageOverlayImageContent.value?.artifact?.contentType || 'image/png').split(';')[0] || 'image/png'
  return `data:${contentType};base64,${imageBase64}`
})
const pageOverlayAspectRatio = computed(() => {
  const width = Number(selectedPageOverlay.value?.pageWidth || 0)
  const height = Number(selectedPageOverlay.value?.pageHeight || 0)
  if (Number.isFinite(width) && width > 0 && Number.isFinite(height) && height > 0) {
    return `${width} / ${height}`
  }
  return '1 / 1.414'
})
const parserTraceWarnings = computed(() => asArray(parserTrace.value?.warnings).slice(0, 5))
const parserTraceBlockTypes = computed(() => {
  const counts = parserTrace.value?.blockTypeCounts || {}
  return Object.entries(counts)
    .map(([type, count]) => ({ type, count: Number(count || 0) }))
    .filter((item) => item.count > 0)
    .sort((left, right) => right.count - left.count)
})
const parserTraceSummary = computed(() => {
  if (!parserTrace.value) {
    return '暂无解析观测数据。'
  }
  return compactList([
    parserTrace.value.pageCount ? `${formatCount(parserTrace.value.pageCount)} 页` : '',
    parserTrace.value.blockCount ? `${formatCount(parserTrace.value.blockCount)} 个 block` : '',
    parserTrace.value.tableCount ? `${formatCount(parserTrace.value.tableCount)} 个表格` : '',
    parserTrace.value.figureCount ? `${formatCount(parserTrace.value.figureCount)} 个图示` : '',
    parserTrace.value.elapsedMs ? `总耗时 ${formatDuration(parserTrace.value.elapsedMs)}` : ''
  ]).join(' · ') || '解析 trace 已记录，等待更多统计字段。'
})
const parserTraceStats = computed(() => {
  const trace = parserTrace.value || {}
  return [
    {
      label: '页数 / OCR',
      value: `${formatCount(trace.pageCount)}/${formatCount(trace.ocrPageCount)}`,
      hint: 'pageCount / ocrPageCount'
    },
    {
      label: 'Layout 区域',
      value: formatCount(trace.rawLayoutCount || trace.blockCount),
      hint: `标准 block ${formatCount(trace.blockCount)}`
    },
    {
      label: '表格 / 图示',
      value: `${formatCount(trace.tableCount)}/${formatCount(trace.figureCount)}`,
      hint: `caption ${formatCount(trace.captionCount)}`
    },
    {
      label: '轮询次数',
      value: formatCount(trace.pollCount),
      hint: trace.jobId ? `Job ${trace.jobId}` : 'native_text 无云端 job'
    },
    {
      label: '提交 / 轮询',
      value: `${formatDuration(trace.submitElapsedMs)} / ${formatDuration(trace.pollElapsedMs)}`,
      hint: 'Document Mind 阶段耗时'
    },
    {
      label: '拉取 / 标准化',
      value: `${formatDuration(trace.resultFetchElapsedMs)} / ${formatDuration(trace.standardizeElapsedMs)}`,
      hint: `总耗时 ${formatDuration(trace.elapsedMs)}`
    }
  ]
})
const parserTraceCoverage = computed(() => {
  const trace = parserTrace.value || {}
  return [
    {
      label: 'Block',
      value: Number(trace.bboxBlockCoverage || 0),
      tone: coverageBarClass(trace.bboxBlockCoverage),
      numerator: trace.bboxBlockCount,
      denominator: trace.blockCount,
      hint: `${formatCount(trace.bboxBlockCount)}/${formatCount(trace.blockCount)}`
    },
    {
      label: 'Cell',
      value: Number(trace.tableCellBboxCoverage || 0),
      tone: coverageBarClass(trace.tableCellBboxCoverage),
      numerator: trace.tableCellBboxCount,
      denominator: trace.tableCellCount,
      hint: `${formatCount(trace.tableCellBboxCount)}/${formatCount(trace.tableCellCount)}`
    }
  ]
})
const artifactGraph = computed(() => ragSnapshot.value?.artifactGraph || null)
const artifactGraphMetrics = computed(() => asArray(artifactGraph.value?.metrics))
const artifactGraphNodes = computed(() => asArray(artifactGraph.value?.nodes))
const artifactGraphEdges = computed(() => asArray(artifactGraph.value?.edges))
const artifactGraphNodeGroups = computed(() => {
  const groups = new Map()
  artifactGraphNodes.value.forEach((node) => {
    const type = normalizeGraphCode(node?.nodeType, 'UNKNOWN')
    if (!groups.has(type)) {
      groups.set(type, {
        type,
        label: artifactGraphNodeTypeLabel(type),
        tone: artifactGraphNodeTypeClass(type),
        items: []
      })
    }
    groups.get(type).items.push(node)
  })
  return Array.from(groups.values()).map((group) => ({
    ...group,
    items: group.items.slice(0, 24)
  }))
})
const selectedArtifactGraphNode = computed(() => {
  const selectedId = normalizeCode(selectedArtifactGraphNodeId.value)
  return artifactGraphNodes.value.find((node) => normalizeCode(node?.nodeId) === selectedId)
    || artifactGraphNodes.value[0]
    || null
})
const selectedArtifactGraphEdges = computed(() => {
  const selectedId = normalizeCode(selectedArtifactGraphNode.value?.nodeId)
  if (!selectedId) {
    return []
  }
  return artifactGraphEdges.value
    .filter((edge) => normalizeCode(edge?.sourceNodeId) === selectedId || normalizeCode(edge?.targetNodeId) === selectedId)
    .slice(0, 80)
})
const selectedArtifactGraphNeighbors = computed(() => {
  const nodeById = new Map(artifactGraphNodes.value.map((node) => [normalizeCode(node?.nodeId), node]))
  const selectedId = normalizeCode(selectedArtifactGraphNode.value?.nodeId)
  return selectedArtifactGraphEdges.value
    .map((edge) => {
      const sourceId = normalizeCode(edge?.sourceNodeId)
      const targetId = normalizeCode(edge?.targetNodeId)
      const neighborId = sourceId === selectedId ? targetId : sourceId
      return {
        edge,
        node: nodeById.get(neighborId),
        direction: sourceId === selectedId ? 'out' : 'in'
      }
    })
    .filter((item) => item.node)
})
const kgGraph = computed(() => ragSnapshot.value?.kgGraph || null)
const kgGraphMetrics = computed(() => asArray(kgGraph.value?.metrics))
const kgGraphNodes = computed(() => asArray(kgGraph.value?.nodes))
const kgGraphEdges = computed(() => asArray(kgGraph.value?.edges))
const kgGraphCommunities = computed(() => asArray(kgGraph.value?.communities))
const kgGraphEvidences = computed(() => asArray(kgGraph.value?.evidences))
const kgEntityTypeOptions = computed(() => uniqueOptions(kgGraphNodes.value.map((node) => node?.entityType), '全部类型'))
const kgQualityLevelOptions = computed(() => uniqueOptions(kgGraphNodes.value.map((node) => node?.qualityLevel), '全部质量'))
const kgCommunityOptions = computed(() => {
  const communityOptions = kgGraphCommunities.value.map((community) => ({
    value: normalizeCode(community?.communityId),
    label: community?.title || `社区 ${valueOrDash(community?.communityNo)}`
  })).filter((item) => item.value)
  return [{ value: 'ALL', label: '全部社区' }, { value: 'NONE', label: '未归属社区' }, ...communityOptions]
})
const filteredKgGraphNodes = computed(() => {
  return kgGraphNodes.value.filter((node) => {
    const typeMatched = selectedKgEntityType.value === 'ALL' || normalizeCode(node?.entityType) === selectedKgEntityType.value
    const qualityMatched = selectedKgQualityLevel.value === 'ALL' || normalizeCode(node?.qualityLevel) === selectedKgQualityLevel.value
    const communityId = normalizeCode(node?.communityId)
    const communityMatched = selectedKgCommunityFilter.value === 'ALL'
      || (selectedKgCommunityFilter.value === 'NONE' ? !communityId : communityId === selectedKgCommunityFilter.value)
    return typeMatched && qualityMatched && communityMatched
  })
})
const filteredKgGraphNodeIds = computed(() => new Set(filteredKgGraphNodes.value.map((node) => normalizeCode(node?.nodeId)).filter(Boolean)))
const filteredKgGraphEdges = computed(() => {
  const nodeIds = filteredKgGraphNodeIds.value
  return kgGraphEdges.value.filter((edge) => nodeIds.has(normalizeCode(edge?.sourceNodeId)) && nodeIds.has(normalizeCode(edge?.targetNodeId)))
})
const selectedKgGraphNode = computed(() => {
  const selectedId = normalizeCode(selectedKgGraphNodeId.value)
  return kgGraphNodes.value.find((node) => normalizeCode(node?.nodeId) === selectedId)
    || filteredKgGraphNodes.value[0]
    || kgGraphNodes.value[0]
    || null
})
const selectedKgGraphEdge = computed(() => {
  const selectedId = normalizeCode(selectedKgGraphEdgeId.value)
  return kgGraphEdges.value.find((edge) => normalizeCode(edge?.edgeId) === selectedId) || null
})
const selectedKgGraphCommunity = computed(() => {
  const selectedId = normalizeCode(selectedKgGraphCommunityId.value)
  return kgGraphCommunities.value.find((community) => normalizeCode(community?.communityId) === selectedId)
    || kgGraphCommunities.value[0]
    || null
})
const selectedKgGraphEvidences = computed(() => {
  if (selectedKgGraphEdge.value?.relationId != null) {
    const relationId = normalizeCode(selectedKgGraphEdge.value.relationId)
    return kgGraphEvidences.value.filter((evidence) => normalizeCode(evidence?.relationId) === relationId).slice(0, 20)
  }
  const entityId = normalizeCode(selectedKgGraphNode.value?.entityId)
  if (!entityId) {
    return kgGraphEvidences.value.slice(0, 20)
  }
  return kgGraphEvidences.value.filter((evidence) => normalizeCode(evidence?.entityId) === entityId).slice(0, 20)
})
const raptorTree = computed(() => ragSnapshot.value?.raptorTree || null)
const raptorTreeMetrics = computed(() => asArray(raptorTree.value?.metrics))
const raptorTreeNodes = computed(() => asArray(raptorTree.value?.nodes))
const raptorTreeEdges = computed(() => asArray(raptorTree.value?.edges))
const raptorQualityLevelOptions = computed(() => uniqueOptions(raptorTreeNodes.value.map((node) => node?.qualityLevel), '全部质量'))
const raptorSummaryStatusOptions = computed(() => uniqueOptions(raptorTreeNodes.value.map((node) => node?.llmSummaryStatus), '全部状态'))
const raptorSummaryStrategyOptions = computed(() => uniqueOptions(raptorTreeNodes.value.map((node) => node?.summaryStrategy), '全部策略'))
const filteredRaptorTreeNodes = computed(() => {
  return raptorTreeNodes.value.filter((node) => {
    const qualityMatched = selectedRaptorQualityLevel.value === 'ALL' || normalizeCode(node?.qualityLevel) === selectedRaptorQualityLevel.value
    const statusMatched = selectedRaptorSummaryStatus.value === 'ALL' || normalizeCode(node?.llmSummaryStatus) === selectedRaptorSummaryStatus.value
    const strategyMatched = selectedRaptorSummaryStrategy.value === 'ALL' || normalizeCode(node?.summaryStrategy) === selectedRaptorSummaryStrategy.value
    return qualityMatched && statusMatched && strategyMatched
  })
})
const filteredRaptorNodeIds = computed(() => new Set(filteredRaptorTreeNodes.value.map((node) => normalizeCode(node?.nodeId)).filter(Boolean)))
const raptorTreeLevelGroups = computed(() => {
  const groups = new Map()
  filteredRaptorTreeNodes.value.forEach((node) => {
    const levelKey = valueOrDash(node?.nodeLevel)
    if (!groups.has(levelKey)) {
      groups.set(levelKey, {
        level: node?.nodeLevel,
        label: `L${levelKey}`,
        items: []
      })
    }
    groups.get(levelKey).items.push(node)
  })
  return Array.from(groups.values())
    .sort((left, right) => Number(right.level ?? -1) - Number(left.level ?? -1))
    .map((group) => ({
      ...group,
      items: group.items
        .slice()
        .sort((left, right) => Number(left.nodeNo || 0) - Number(right.nodeNo || 0))
    }))
})
const selectedRaptorTreeNode = computed(() => {
  const selectedId = normalizeCode(selectedRaptorTreeNodeId.value)
  return raptorTreeNodes.value.find((node) => normalizeCode(node?.nodeId) === selectedId)
    || filteredRaptorTreeNodes.value[0]
    || raptorTreeNodes.value[0]
    || null
})
const selectedRaptorTreeChildren = computed(() => {
  const selectedId = normalizeCode(selectedRaptorTreeNode.value?.nodeId)
  if (!selectedId) {
    return []
  }
  const nodeIds = filteredRaptorNodeIds.value
  return raptorTreeEdges.value
    .filter((edge) => normalizeCode(edge?.sourceNodeId) === selectedId && nodeIds.has(normalizeCode(edge?.targetNodeId)))
    .map((edge) => raptorTreeNodes.value.find((node) => normalizeCode(node?.nodeId) === normalizeCode(edge?.targetNodeId)))
    .filter(Boolean)
})
const raptorQualityReport = computed(() => ragSnapshot.value?.raptorQuality || null)
const raptorQualityStats = computed(() => {
  const report = raptorQualityReport.value || {}
  return [
    {
      label: '当前阈值',
      value: formatPercent(report.configuredQualityFloor),
      hint: '低于该值不会入库'
    },
    {
      label: '建议阈值',
      value: formatPercent(report.recommendedQualityFloor),
      hint: '下一轮小步试探'
    },
    {
      label: '平均质量',
      value: formatPercent(report.averageQualityScore),
      hint: `中位数 ${formatPercent(report.medianQualityScore)}`
    },
    {
      label: 'LLM 摘要覆盖',
      value: `${formatCount(report.abstractiveNodeCount)}/${formatCount(report.nodeCount)}`,
      hint: formatPercent(report.abstractiveCoverage)
    },
    {
      label: '低分节点',
      value: `${formatCount(report.lowQualityNodeCount)} 个`,
      hint: `占比 ${formatPercent(report.lowQualityRatio)}`
    },
    {
      label: '阈值拦截',
      value: `${formatCount(report.floorBlockedNodeCount)} 个`,
      hint: `当前样本占比 ${formatPercent(report.floorBlockedRatio)}`
    },
    {
      label: '平均簇大小',
      value: formatDecimal(report.averageClusterSize),
      hint: `最大 ${formatCount(report.maxClusterSizeObserved)}`
    },
    {
      label: '树平衡分',
      value: formatPercent(report.averageTreeBalanceScore),
      hint: `单节点簇 ${formatCount(report.singletonClusterCount)}`
    },
    {
      label: '层级压缩',
      value: formatPercent(report.averageLevelCompressionRatio),
      hint: `簇内相似 ${formatPercent(report.averageIntraClusterSimilarity)}`
    }
  ]
})
const raptorQualityDistribution = computed(() => {
  const report = raptorQualityReport.value || {}
  return [
    { label: 'min', value: report.minQualityScore },
    { label: 'p10', value: report.p10QualityScore },
    { label: 'p50', value: report.medianQualityScore },
    { label: 'p90', value: report.p90QualityScore }
  ]
})
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
        overlayId: item.blockId ? `block-${item.blockId}` : '',
        pageNo: item.pageNo,
        canLocate: Boolean(item.blockId && item.bboxJson && hasPageOverlayRegion(`block-${item.blockId}`, item.pageNo)),
        title: `Block #${item.blockNo || '-'}`,
        subtitle: item.sectionPath || '未识别章节',
        chips: [item.blockType || 'block', item.pageRange || (item.pageNo != null ? `第 ${item.pageNo} 页` : '')],
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
        overlayId: item.tableId ? `table-${item.tableId}` : '',
        pageNo: item.pageNo,
        canLocate: Boolean(item.tableId && item.bboxJson && hasPageOverlayRegion(`table-${item.tableId}`, item.pageNo)),
        title: `表格 T#${item.tableNo || '-'}`,
        subtitle: item.title || item.sectionPath || '未命名表格',
        chips: [`${formatCount(item.rowCount)} 行`, `${formatCount(item.columnCount)} 列`, item.pageRange || ''],
        meta: compactList([item.tableId ? `ID ${item.tableId}` : '', item.pageNo != null ? `第 ${item.pageNo} 页` : '', item.bboxJson ? '有表格 bbox' : '']),
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
        nodeId: item.nodeId,
        nodeKey: item.nodeKey,
        parentNodeId: item.parentNodeId,
        nodeLevel: item.nodeLevel,
        nodeNo: item.nodeNo,
        treeDepth: item.treeDepth,
        treePath: item.treePath,
        qualityScore: item.qualityScore,
        qualityLevel: item.qualityLevel,
        qualityRisk: item.qualityRisk,
        summaryStrategy: item.summaryStrategy,
        clusterMethod: item.clusterMethod,
        treeBuilderMethod: item.treeBuilderMethod,
        avgClusterSize: item.avgClusterSize,
        maxClusterSizeObserved: item.maxClusterSizeObserved,
        singletonClusterCount: item.singletonClusterCount,
        levelCompressionRatio: item.levelCompressionRatio,
        avgIntraClusterSimilarity: item.avgIntraClusterSimilarity,
        treeBalanceScore: item.treeBalanceScore,
        abstractive: item.abstractive,
        llmSummaryStatus: item.llmSummaryStatus,
        sourceChunkCount: item.sourceChunkCount,
        sourceParentBlockCount: item.sourceParentBlockCount,
        childNodeCount: item.childNodeCount,
        sourceChunks: asArray(item.sourceChunks),
        sourceParentBlocks: asArray(item.sourceParentBlocks),
        title: item.title || `RAPTOR N#${item.nodeNo || '-'}`,
        subtitle: item.sectionPath || `Level ${valueOrDash(item.nodeLevel)}`,
        chips: compactList([
          `L${valueOrDash(item.nodeLevel)}`,
          item.summaryStrategy || '',
          item.clusterMethod || '',
          item.treeBuilderMethod || '',
          raptorNodeQualityLabel(item.qualityLevel),
          item.pageRange || '',
          item.keywords || ''
        ]),
        meta: compactList([
          item.sourceChunkIdsJson ? `源 chunk ${item.sourceChunkIdsJson}` : '',
          item.sourceParentBlockIdsJson ? `源 parent ${item.sourceParentBlockIdsJson}` : '',
          item.nodeId ? `ID ${item.nodeId}` : ''
        ]),
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
  if (hasBuildTaskSnapshot.value && buildTaskSnapshot.value?.taskId) {
    return buildTaskSnapshot.value.taskId
  }
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
const hasActiveParseRouteTask = computed(() => {
  return hasCode(documentDetail.value?.latestTaskType, 1)
    && ['1', '2'].includes(normalizeCode(documentDetail.value?.latestTaskStatus))
})
const shouldShowParseRouteProgressEntry = computed(() => {
  return hasActiveParseRouteTask.value
    || hasCode(documentDetail.value?.parseStatus, 1)
    || hasCode(documentDetail.value?.parseStatus, 2)
    || (!strategyPlan.value?.planReady && hasCode(documentDetail.value?.strategyStatus, 1))
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
    return '系统正在锁定当前确认方案并创建异步任务，稍后会自动进入构建阶段。'
  }
  return '构建阶段会实时刷新，当前步骤会显示转圈提示，完成后自动解除页面锁定。'
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

function normalizeGraphCode(value, fallback = '') {
  const normalized = normalizeCode(value).toUpperCase()
  return normalized || fallback
}

function uniqueOptions(values, allLabel) {
  const uniqueValues = Array.from(new Set(
    asArray(values)
      .map((value) => normalizeCode(value))
      .filter(Boolean)
  ))
  return [
    { value: 'ALL', label: allLabel },
    ...uniqueValues.map((value) => ({ value, label: value }))
  ]
}

function valueOrDash(value) {
  const text = String(value ?? '').trim()
  return text || '-'
}

function formatPercent(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) {
    return '-'
  }
  return `${Math.round(Math.max(0, Math.min(1, number)) * 100)}%`
}

function formatDecimal(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) {
    return '-'
  }
  return number.toFixed(number >= 10 ? 1 : 2).replace(/\.?0+$/, '')
}

function formatBytes(value) {
  const bytes = Number(value || 0)
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '-'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1).replace(/\.0$/, '')} KB`
  }
  return `${(bytes / 1024 / 1024).toFixed(1).replace(/\.0$/, '')} MB`
}

function countTextMatches(content, keyword) {
  const source = String(content || '')
  const needle = String(keyword || '').trim()
  if (!source || !needle) {
    return 0
  }
  const escaped = needle.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return source.match(new RegExp(escaped, 'gi'))?.length || 0
}

function parseJsonSafely(content) {
  try {
    return JSON.parse(content)
  } catch {
    return null
  }
}

function buildArtifactJsonPathRows(content, keyword) {
  const root = parseJsonSafely(content)
  if (root == null || typeof root !== 'object') {
    return []
  }
  const needle = String(keyword || '').trim().toLowerCase()
  const rows = []
  if (!needle) {
    Object.keys(root).slice(0, 80).forEach((key) => {
      const value = root[key]
      rows.push({
        path: `$.${key}`,
        preview: previewJsonPathValue(value)
      })
    })
    return rows
  }
  collectJsonPathRows(root, '$', needle, rows)
  return rows
}

function collectJsonPathRows(value, path, needle, rows) {
  if (rows.length >= 120) {
    return
  }
  const preview = previewJsonPathValue(value)
  if (path.toLowerCase().includes(needle) || preview.toLowerCase().includes(needle)) {
    rows.push({ path, preview })
  }
  if (Array.isArray(value)) {
    value.slice(0, 200).forEach((item, index) => collectJsonPathRows(item, `${path}[${index}]`, needle, rows))
    return
  }
  if (value && typeof value === 'object') {
    Object.entries(value).slice(0, 300).forEach(([key, item]) => {
      const safeKey = /^[A-Za-z_$][\w$]*$/.test(key) ? `.${key}` : `[${JSON.stringify(key)}]`
      collectJsonPathRows(item, `${path}${safeKey}`, needle, rows)
    })
  }
}

function previewJsonPathValue(value) {
  if (value == null) {
    return 'null'
  }
  if (typeof value === 'string') {
    return value
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  if (Array.isArray(value)) {
    return `Array(${value.length})`
  }
  if (typeof value === 'object') {
    return `Object(${Object.keys(value).length})`
  }
  return String(value)
}

function qualityBarWidth(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) {
    return '0%'
  }
  return `${Math.round(Math.max(0, Math.min(1, number)) * 100)}%`
}

function coverageBarClass(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) {
    return 'bg-destructive'
  }
  if (number >= 0.8) {
    return 'bg-[var(--color-success)]'
  }
  if (number >= 0.5) {
    return 'bg-amber-500'
  }
  return 'bg-destructive'
}

function raptorQualityLevelLabel(level) {
  if (level === 'STRONG') return '质量稳定'
  if (level === 'WATCH') return '需要观察'
  if (level === 'WEAK') return '质量偏弱'
  return '暂无评测'
}

function raptorQualityPanelClass(level) {
  if (level === 'STRONG') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (level === 'WATCH') return 'border-amber-500/20 bg-amber-500/[0.04]'
  if (level === 'WEAK') return 'border-destructive/20 bg-destructive/[0.04]'
  return 'border-border bg-secondary'
}

function raptorQualityBadgeClass(level) {
  if (level === 'STRONG') return 'bg-[var(--color-success)]/[0.10] text-[var(--color-success)]'
  if (level === 'WATCH') return 'bg-amber-500/[0.12] text-amber-700'
  if (level === 'WEAK') return 'bg-destructive/[0.10] text-destructive'
  return 'bg-secondary text-muted-foreground'
}

function raptorNodeQualityLabel(level) {
  if (level === 'HIGH') return '高质量'
  if (level === 'OK') return '可用'
  if (level === 'WATCH') return '观察'
  if (level === 'LOW') return '低分'
  if (level === 'BLOCKED') return '低于阈值'
  return ''
}

function raptorNodeQualityClass(level) {
  if (level === 'HIGH') return 'bg-[var(--color-success)]/[0.10] text-[var(--color-success)]'
  if (level === 'OK') return 'bg-primary/[0.08] text-primary'
  if (level === 'WATCH') return 'bg-amber-500/[0.12] text-amber-700'
  if (level === 'LOW' || level === 'BLOCKED') return 'bg-destructive/[0.10] text-destructive'
  return 'bg-secondary text-muted-foreground'
}

function raptorNodeQualityBarClass(level) {
  if (level === 'HIGH') return 'bg-[var(--color-success)]'
  if (level === 'WATCH') return 'bg-amber-500'
  if (level === 'LOW' || level === 'BLOCKED') return 'bg-destructive'
  return 'bg-primary'
}

function artifactGraphNodeTypeLabel(type) {
  const normalized = normalizeGraphCode(type)
  const labelMap = {
    DOCUMENT: 'Document',
    PARSE_BLOCK: 'ParseBlock',
    PARENT_BLOCK: 'ParentBlock',
    CHILD_CHUNK: 'ChildChunk',
    TABLE: 'Table',
    KG_EVIDENCE: 'KG evidence',
    RAPTOR_NODE: 'RAPTOR'
  }
  return labelMap[normalized] || normalized || '节点'
}

function artifactGraphNodeTypeClass(type) {
  const normalized = normalizeGraphCode(type)
  if (normalized === 'DOCUMENT') return 'bg-primary'
  if (normalized === 'PARSE_BLOCK') return 'bg-[#2563eb]'
  if (normalized === 'PARENT_BLOCK') return 'bg-[#0f766e]'
  if (normalized === 'CHILD_CHUNK') return 'bg-[#7c3aed]'
  if (normalized === 'TABLE') return 'bg-[#c2410c]'
  if (normalized === 'KG_EVIDENCE') return 'bg-[#be123c]'
  if (normalized === 'RAPTOR_NODE') return 'bg-[#0369a1]'
  return 'bg-muted-foreground'
}

function artifactGraphTypeCount(type) {
  const normalized = normalizeGraphCode(type)
  return artifactGraphNodes.value.filter((node) => normalizeGraphCode(node?.nodeType) === normalized).length
}

function selectArtifactGraphNode(node) {
  selectedArtifactGraphNodeId.value = node?.nodeId || ''
}

function selectKgGraphNode(node) {
  selectedKgGraphNodeId.value = node?.nodeId || ''
  selectedKgGraphEdgeId.value = ''
  if (node?.communityId != null) {
    selectedKgGraphCommunityId.value = String(node.communityId)
  }
}

function selectKgGraphEdge(edge) {
  selectedKgGraphEdgeId.value = edge?.edgeId || ''
  if (edge?.sourceNodeId) {
    selectedKgGraphNodeId.value = edge.sourceNodeId
  }
}

function selectKgGraphCommunity(community) {
  selectedKgGraphCommunityId.value = community?.communityId == null ? '' : String(community.communityId)
  selectedKgCommunityFilter.value = selectedKgGraphCommunityId.value || 'ALL'
}

function kgEntityName(entityId) {
  const targetId = normalizeCode(entityId)
  const node = kgGraphNodes.value.find((item) => normalizeCode(item?.entityId) === targetId)
  return node?.name || targetId || '-'
}

function kgQualityLabel(level) {
  const normalized = normalizeGraphCode(level)
  if (normalized === 'STRONG') return '强'
  if (normalized === 'WATCH') return '观察'
  if (normalized === 'WEAK') return '弱'
  if (normalized === 'ISOLATED') return '孤立'
  return normalized || '-'
}

function kgQualityClass(level) {
  const normalized = normalizeGraphCode(level)
  if (normalized === 'STRONG') return 'bg-[var(--color-success)]/[0.10] text-[var(--color-success)]'
  if (normalized === 'WATCH' || normalized === 'ISOLATED') return 'bg-amber-500/[0.12] text-amber-700'
  if (normalized === 'WEAK') return 'bg-destructive/[0.10] text-destructive'
  return 'bg-secondary text-muted-foreground'
}

function focusKgEvidenceOverlay(evidence) {
  if (!evidence?.bboxJson) {
    showNotice('当前 evidence 没有 bbox，不能跳到页面定位。', 'warning')
    return
  }
  showNotice('KG evidence 已有 bbox 元数据，但当前页面 overlay 只展示 block/table 真实区域；请通过 Chunk 查看原文来源。', 'info')
}

function selectRaptorTreeNode(node) {
  selectedRaptorTreeNodeId.value = node?.nodeId || ''
}

function raptorScopeLabel(scopeType) {
  const normalized = normalizeGraphCode(scopeType)
  if (normalized === 'DOCUMENT') return '文档级'
  if (normalized === 'DATASET') return '知识域级'
  if (!normalized) return '未标记 scope'
  return normalized
}

function raptorTreeNodeChips(node) {
  return compactList([
    raptorScopeLabel(node?.scopeType),
    node?.scopeKey || '',
    node?.summaryStrategy || '',
    node?.llmSummaryStatus || '',
    `source chunk ${formatCount(node?.sourceChunkCount)}`,
    `source parent ${formatCount(node?.sourceParentBlockCount)}`,
    `child ${formatCount(node?.childNodeCount)}`
  ]).slice(0, 8)
}

function raptorLevelClass(level) {
  const normalized = Number(level)
  if (normalized >= 3) return 'bg-primary/[0.10] text-primary'
  if (normalized === 2) return 'bg-[var(--color-success)]/[0.10] text-[var(--color-success)]'
  return 'bg-secondary text-foreground'
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

function displayPageOverlayNo(page) {
  const displayNo = String(page?.displayPageNo ?? '').trim()
  if (displayNo) {
    return displayNo
  }
  const pageNo = page?.pageNo
  return pageNo == null ? '-' : String(pageNo)
}

function hasPageOverlayRegion(overlayId, pageNo) {
  const targetId = normalizeCode(overlayId)
  if (!targetId) {
    return false
  }
  const targetPageNo = normalizeCode(pageNo)
  return pageOverlays.value.some((page) => {
    const pageMatched = !targetPageNo || normalizeCode(page?.pageNo) === targetPageNo
    return pageMatched && asArray(page?.overlays).some((region) => normalizeCode(region?.overlayId) === targetId)
  })
}

function pageOverlayTypeLabel(type) {
  const normalized = normalizeCode(type).toUpperCase()
  return PAGE_OVERLAY_TYPE_OPTIONS.find((item) => item.type === normalized)?.label || normalized || '区域'
}

function pageOverlayTypeCount(type) {
  const normalized = normalizeCode(type).toUpperCase()
  return asArray(selectedPageOverlay.value?.overlays)
    .filter((region) => normalizeCode(region?.type).toUpperCase() === normalized)
    .length
}

function pageOverlayTypeButtonClass(type) {
  const active = selectedPageOverlayTypeSet.value.has(normalizeCode(type).toUpperCase())
  return active
    ? 'border-primary bg-primary/[0.08] text-primary'
    : 'border-border bg-card text-muted-foreground hover:border-primary/30 hover:text-foreground'
}

function pageOverlayTypeDotClass(type) {
  const normalized = normalizeCode(type).toUpperCase()
  if (normalized === 'TITLE') return 'bg-[#2563eb]'
  if (normalized === 'TABLE') return 'bg-[#0f766e]'
  if (normalized === 'FIGURE') return 'bg-[#9333ea]'
  if (normalized === 'IMAGE') return 'bg-[#c2410c]'
  return 'bg-[#64748b]'
}

function pageOverlayTypeBadgeClass(type) {
  const normalized = normalizeCode(type).toUpperCase()
  if (normalized === 'TITLE') return 'bg-[#2563eb]/10 text-[#1d4ed8]'
  if (normalized === 'TABLE') return 'bg-[#0f766e]/10 text-[#0f766e]'
  if (normalized === 'FIGURE') return 'bg-[#9333ea]/10 text-[#7e22ce]'
  if (normalized === 'IMAGE') return 'bg-[#c2410c]/10 text-[#9a3412]'
  return 'bg-foreground/[0.06] text-muted-foreground'
}

function pageOverlayRegionClass(region) {
  const selected = normalizeCode(region?.overlayId) === normalizeCode(selectedOverlayId.value)
  const normalized = normalizeCode(region?.type).toUpperCase()
  const base = selected ? 'z-10 bg-white/10 ring-2 ring-primary/50' : 'bg-transparent hover:bg-white/10'
  if (normalized === 'TITLE') return `${base} border-[#2563eb]`
  if (normalized === 'TABLE') return `${base} border-[#0f766e]`
  if (normalized === 'FIGURE') return `${base} border-[#9333ea]`
  if (normalized === 'IMAGE') return `${base} border-[#c2410c]`
  return `${base} border-[#64748b]`
}

function pageOverlayRegionStyle(region) {
  const left = clampPercent(region?.leftRatio)
  const top = clampPercent(region?.topRatio)
  const width = clampPercent(region?.widthRatio)
  const height = clampPercent(region?.heightRatio)
  return {
    left: `${left}%`,
    top: `${top}%`,
    width: `${Math.max(0.25, Math.min(width, 100 - left))}%`,
    height: `${Math.max(0.25, Math.min(height, 100 - top))}%`
  }
}

function pageOverlayRegionTitle(region) {
  return compactList([
    pageOverlayTypeLabel(region?.type),
    region?.label,
    region?.sectionPath,
    region?.textPreview
  ]).join(' · ')
}

function clampPercent(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) {
    return 0
  }
  return Math.max(0, Math.min(100, number * 100))
}

function syncPageOverlaySelection() {
  if (!pageOverlays.value.length) {
    selectedPageOverlayNo.value = null
    selectedOverlayId.value = ''
    pageOverlayImageContent.value = null
    return
  }
  const hasSelectedPage = pageOverlays.value.some((page) => normalizeCode(page.pageNo) === normalizeCode(selectedPageOverlayNo.value))
  if (!hasSelectedPage) {
    selectedPageOverlayNo.value = pageOverlays.value[0]?.pageNo ?? null
  }
  const hasSelectedOverlay = selectedOverlayId.value
    && asArray(selectedPageOverlay.value?.overlays).some((region) => normalizeCode(region.overlayId) === normalizeCode(selectedOverlayId.value))
  if (!hasSelectedOverlay) {
    selectedOverlayId.value = ''
  }
}

function syncRagVisualSelections() {
  if (!artifactGraphNodes.value.some((node) => normalizeCode(node?.nodeId) === normalizeCode(selectedArtifactGraphNodeId.value))) {
    selectedArtifactGraphNodeId.value = artifactGraphNodes.value[0]?.nodeId || ''
  }
  if (!kgEntityTypeOptions.value.some((option) => option.value === selectedKgEntityType.value)) {
    selectedKgEntityType.value = 'ALL'
  }
  if (!kgQualityLevelOptions.value.some((option) => option.value === selectedKgQualityLevel.value)) {
    selectedKgQualityLevel.value = 'ALL'
  }
  if (!kgCommunityOptions.value.some((option) => option.value === selectedKgCommunityFilter.value)) {
    selectedKgCommunityFilter.value = 'ALL'
  }
  if (!kgGraphNodes.value.some((node) => normalizeCode(node?.nodeId) === normalizeCode(selectedKgGraphNodeId.value))) {
    selectedKgGraphNodeId.value = filteredKgGraphNodes.value[0]?.nodeId || kgGraphNodes.value[0]?.nodeId || ''
  }
  if (!kgGraphEdges.value.some((edge) => normalizeCode(edge?.edgeId) === normalizeCode(selectedKgGraphEdgeId.value))) {
    selectedKgGraphEdgeId.value = ''
  }
  if (!kgGraphCommunities.value.some((community) => normalizeCode(community?.communityId) === normalizeCode(selectedKgGraphCommunityId.value))) {
    selectedKgGraphCommunityId.value = kgGraphCommunities.value[0]?.communityId == null ? '' : String(kgGraphCommunities.value[0].communityId)
  }
  if (!raptorQualityLevelOptions.value.some((option) => option.value === selectedRaptorQualityLevel.value)) {
    selectedRaptorQualityLevel.value = 'ALL'
  }
  if (!raptorSummaryStatusOptions.value.some((option) => option.value === selectedRaptorSummaryStatus.value)) {
    selectedRaptorSummaryStatus.value = 'ALL'
  }
  if (!raptorSummaryStrategyOptions.value.some((option) => option.value === selectedRaptorSummaryStrategy.value)) {
    selectedRaptorSummaryStrategy.value = 'ALL'
  }
  if (!raptorTreeNodes.value.some((node) => normalizeCode(node?.nodeId) === normalizeCode(selectedRaptorTreeNodeId.value))) {
    selectedRaptorTreeNodeId.value = filteredRaptorTreeNodes.value[0]?.nodeId || raptorTreeNodes.value[0]?.nodeId || ''
  }
}

function selectPageOverlay(pageNo) {
  if (normalizeCode(selectedPageOverlayNo.value) === normalizeCode(pageNo)) {
    return
  }
  selectedPageOverlayNo.value = pageNo ?? null
  selectedOverlayId.value = ''
}

function selectOverlayRegion(region) {
  selectedOverlayId.value = region?.overlayId || ''
}

function togglePageOverlayType(type) {
  const normalized = normalizeCode(type).toUpperCase()
  if (!normalized) {
    return
  }
  const nextSet = new Set(selectedOverlayTypes.value.map((item) => normalizeCode(item).toUpperCase()).filter(Boolean))
  if (nextSet.has(normalized)) {
    nextSet.delete(normalized)
  } else {
    nextSet.add(normalized)
  }
  selectedOverlayTypes.value = Array.from(nextSet)
}

function findPageOverlayRegion(overlayId, pageNo) {
  const targetId = normalizeCode(overlayId)
  const targetPageNo = normalizeCode(pageNo)
  for (const page of pageOverlays.value) {
    const pageMatched = !targetPageNo || normalizeCode(page?.pageNo) === targetPageNo
    if (!pageMatched) {
      continue
    }
    const region = asArray(page?.overlays).find((item) => normalizeCode(item?.overlayId) === targetId)
    if (region) {
      return { page, region }
    }
  }
  return null
}

async function focusPageOverlay(overlayId, pageNo) {
  const target = findPageOverlayRegion(overlayId, pageNo)
  if (!target) {
    showNotice('当前快照里没有找到对应页面 overlay，请刷新快照或确认解析产物已生成。', 'warning')
    return
  }
  activeWorkbenchSection.value = 'rag'
  selectedPageOverlayNo.value = target.page.pageNo
  selectedOverlayId.value = target.region.overlayId || ''
  const targetType = normalizeCode(target.region.type).toUpperCase()
  if (targetType && !selectedPageOverlayTypeSet.value.has(targetType)) {
    selectedOverlayTypes.value = [...selectedOverlayTypes.value, targetType]
  }
  await nextTick()
  pageOverlaySectionRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function loadSelectedPageOverlayImage() {
  const requestSeq = pageOverlayImageRequestSeq.value + 1
  pageOverlayImageRequestSeq.value = requestSeq
  const page = selectedPageOverlay.value
  pageOverlayImageContent.value = null
  if (!page?.pageImageArtifactId || !documentId.value) {
    pageOverlayImageLoading.value = false
    return
  }
  const requestArtifactId = normalizeCode(page.pageImageArtifactId)
  pageOverlayImageLoading.value = true
  try {
    const data = await manageApi.queryParseArtifactContent({
      documentId: documentId.value,
      taskId: ragSnapshot.value?.parseTaskId || parseArtifactQuery.value?.taskId || undefined,
      artifactId: page.pageImageArtifactId
    })
    if (pageOverlayImageRequestSeq.value === requestSeq && normalizeCode(selectedPageOverlay.value?.pageImageArtifactId) === requestArtifactId) {
      pageOverlayImageContent.value = data
    }
  } catch (error) {
    console.error('读取页面定位底图失败', error)
    showNotice(normalizeError(error, '读取页面定位底图失败'), 'warning')
    if (pageOverlayImageRequestSeq.value === requestSeq && normalizeCode(selectedPageOverlay.value?.pageImageArtifactId) === requestArtifactId) {
      pageOverlayImageContent.value = null
    }
  } finally {
    if (pageOverlayImageRequestSeq.value === requestSeq && normalizeCode(selectedPageOverlay.value?.pageImageArtifactId) === requestArtifactId) {
      pageOverlayImageLoading.value = false
    }
  }
}

async function openPageOverlayArtifact(page) {
  const artifactId = page?.pageImageArtifactId
  if (!artifactId) {
    return
  }
  await openParseArtifact({
    artifactId,
    taskId: ragSnapshot.value?.parseTaskId || parseArtifactQuery.value?.taskId,
    artifactType: 'PAGE_IMAGE',
    artifactTypeName: '页面图片',
    fileName: page.pageImageObjectName ? page.pageImageObjectName.split('/').pop() : `page-${displayPageOverlayNo(page)}.png`,
    objectName: page.pageImageObjectName,
    viewable: true,
    contentType: 'image/png'
  })
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
  await loadBuildProgress({ resetLogs: true })
}

async function loadBuildProgress(options = {}) {
  const {
    resetLogs = false,
    taskId = activeBuildTaskId.value || buildTaskSnapshot.value?.taskId || ''
  } = options
  if (!documentId.value) {
    buildTaskSnapshot.value = null
    buildProgressLatestLogId.value = null
    return null
  }
  try {
    const data = await manageApi.queryIndexBuildProgress({
      documentId: documentId.value,
      taskId: taskId || undefined,
      sinceLogId: resetLogs ? undefined : buildProgressLatestLogId.value || undefined,
      logLimit: resetLogs ? 60 : 40
    })
    applyBuildProgress(data, resetLogs)
    return data
  } catch (error) {
    console.error('读取构建进度失败', error)
    if (resetLogs) {
      buildTaskSnapshot.value = null
      buildProgressLatestLogId.value = null
      buildProgressNoticeStage.value = null
    }
    throw error
  }
}

function applyBuildProgress(data, resetLogs = false) {
  if (!data) {
    return
  }
  const previousTaskStatus = normalizeCode(buildTaskSnapshot.value?.taskStatus)
  const previous = resetLogs ? [] : asArray(buildTaskSnapshot.value?.logs)
  const mergedLogs = mergeTaskLogs(previous, asArray(data.logs))
  buildTaskSnapshot.value = {
    ...buildTaskSnapshot.value,
    ...data,
    logs: mergedLogs
  }
  buildProgressLatestLogId.value = data.latestLogId || latestLogId(mergedLogs) || buildProgressLatestLogId.value || null
  if (hasCode(data.taskStatus, 4)) {
    buildProgressNoticeStage.value = null
    showNotice(data.errorMsg || '索引构建失败，请查看当前任务轨迹里的失败阶段。', 'danger')
  } else if (hasCode(data.taskStatus, 3) && previousTaskStatus !== '3') {
    buildProgressNoticeStage.value = null
    showNotice('索引构建完成，文档索引和 RAG 产物已更新。', 'success')
  } else if (data.building === true && data.currentStageName && normalizeCode(data.currentStage) !== buildProgressNoticeStage.value) {
    buildProgressNoticeStage.value = normalizeCode(data.currentStage)
    showNotice(`索引构建进行中：${data.currentStageName}`, 'info')
  }
  if (documentDetail.value) {
    documentDetail.value = {
      ...documentDetail.value,
      indexStatus: data.indexStatus ?? documentDetail.value.indexStatus,
      indexStatusName: data.indexStatusName || documentDetail.value.indexStatusName,
      latestTaskId: data.taskId || documentDetail.value.latestTaskId,
      latestTaskType: data.taskType || documentDetail.value.latestTaskType,
      latestTaskTypeName: data.taskTypeName || documentDetail.value.latestTaskTypeName,
      latestTaskStatus: data.taskStatus || documentDetail.value.latestTaskStatus,
      latestTaskStatusName: data.taskStatusName || documentDetail.value.latestTaskStatusName
    }
  }
}

function mergeTaskLogs(previousLogs, incomingLogs) {
  const byId = new Map()
  previousLogs.concat(incomingLogs).forEach((item) => {
    const id = normalizeCode(item?.id)
    if (id) {
      byId.set(id, item)
    }
  })
  return Array.from(byId.values())
    .sort((left, right) => {
      const leftTime = new Date(left?.createTime || 0).getTime()
      const rightTime = new Date(right?.createTime || 0).getTime()
      if (leftTime !== rightTime) {
        return leftTime - rightTime
      }
      return Number(left?.id || 0) - Number(right?.id || 0)
    })
}

function latestLogId(logs) {
  return asArray(logs)
    .map((item) => Number(item?.id || 0))
    .filter((id) => Number.isFinite(id) && id > 0)
    .reduce((max, id) => Math.max(max, id), 0) || null
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
    syncPageOverlaySelection()
    syncRagVisualSelections()
  } catch (error) {
    console.error('读取 RAG 学习快照失败', error)
    ragSnapshot.value = null
    syncPageOverlaySelection()
    syncRagVisualSelections()
  } finally {
    ragSnapshotLoading.value = false
  }
}

async function loadParseArtifacts() {
  if (!documentId.value) {
    parseArtifactQuery.value = null
    return
  }
  parseArtifactsLoading.value = true
  try {
    parseArtifactQuery.value = await manageApi.queryParseArtifacts({
      documentId: documentId.value
    })
  } catch (error) {
    console.error('读取解析产物失败', error)
    parseArtifactQuery.value = null
  } finally {
    parseArtifactsLoading.value = false
  }
}

async function openParseArtifact(item) {
  if (!item?.artifactId) {
    return
  }
  artifactPreviewDrawerOpen.value = true
  artifactContentLoading.value = true
  artifactContent.value = null
  artifactSearchKeyword.value = ''
  try {
    const data = await manageApi.queryParseArtifactContent({
      documentId: documentId.value,
      taskId: parseArtifactQuery.value?.taskId || item.taskId || undefined,
      artifactId: item.artifactId
    })
    artifactContent.value = data
    artifactPreviewCollapsed.value = !Boolean(data?.image) && Boolean(data?.json) && Number(data?.contentLength || 0) > ARTIFACT_PREVIEW_LIMIT
  } catch (error) {
    console.error('读取解析产物内容失败', error)
    showNotice(normalizeError(error, '读取解析产物内容失败'), 'danger')
    artifactContent.value = null
  } finally {
    artifactContentLoading.value = false
  }
}

async function downloadParseArtifact(item) {
  if (!item?.artifactId) {
    return
  }
  artifactDownloadLoading.value = true
  try {
    const result = await manageApi.downloadParseArtifact({
      documentId: documentId.value,
      taskId: parseArtifactQuery.value?.taskId || item.taskId || undefined,
      artifactId: item.artifactId
    })
    const url = window.URL.createObjectURL(result.blob)
    const link = document.createElement('a')
    link.href = url
    link.download = result.fileName || item.fileName || `${item.artifactType || 'artifact'}.bin`
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
  } catch (error) {
    console.error('下载解析产物失败', error)
    showNotice(normalizeError(error, '下载解析产物失败'), 'danger')
  } finally {
    artifactDownloadLoading.value = false
  }
}

async function copyText(text) {
  const value = String(text || '')
  if (!value) {
    return
  }
  try {
    await navigator.clipboard?.writeText(value)
    showNotice('已复制到剪贴板。', 'success')
  } catch (error) {
    console.error('复制失败', error)
    showNotice('复制失败，请手动选择文本复制。', 'warning')
  }
}

function closeArtifactPreviewDrawer() {
  artifactPreviewDrawerOpen.value = false
  artifactContent.value = null
  artifactSearchKeyword.value = ''
  artifactPreviewCollapsed.value = false
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

function openInitialParseRouteProgressIfNeeded() {
  const shouldOpenFromRoute = firstQueryValue(route.query?.showParseProgress) === '1'
  if (shouldOpenFromRoute || shouldShowParseRouteProgressEntry.value) {
    openParseRouteDialog(firstQueryValue(route.query?.parseTaskId) || '')
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
      loadDocumentRagSnapshot(),
      loadParseArtifacts()
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
    await loadDocumentDetail()
    await loadBuildProgress({ resetLogs: true, taskId: result.taskId })
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

function openParseRouteDialog(taskId = '') {
  parseRouteDialogTaskId.value = taskId
    || firstQueryValue(route.query?.parseTaskId)
    || (hasCode(documentDetail.value?.latestTaskType, 1) ? documentDetail.value?.latestTaskId : '')
    || ''
  parseRouteDialogOpen.value = true
}

async function handleParseRouteCompleted(progress) {
  try {
    await Promise.all([
      loadDocumentDetail(),
      loadStrategyPlan(),
      loadTaskLogs(),
      loadParseArtifacts(),
      loadDocumentRagSnapshot()
    ])
    if (progress?.planReady) {
      activeWorkbenchSection.value = 'strategy'
      showNotice('解析与策略推荐完成，已刷新推荐策略。', 'success')
    }
  } catch (error) {
    console.error('刷新解析完成后的文档详情失败', error)
  }
}

async function handleParseRouteFailed(progress) {
  await Promise.allSettled([
    loadDocumentDetail(),
    loadTaskLogs()
  ])
  showNotice(progress?.errorMsg || '解析与策略推荐失败，请查看任务日志。', 'danger')
}

async function handleOpenParseRouteStrategy(progress) {
  parseRouteDialogOpen.value = false
  await handleParseRouteCompleted(progress)
  activeWorkbenchSection.value = 'strategy'
  await nextTick()
  strategySectionRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
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
  let consecutiveErrorCount = 0
  buildPollTimer.value = window.setInterval(async () => {
    try {
      const progress = await loadBuildProgress()
      consecutiveErrorCount = 0
      const building = progress?.building === true
        || ['1', '2'].includes(normalizeCode(buildTaskSnapshot.value?.taskStatus))
        || hasCode(documentDetail.value?.indexStatus, 2)
      if (!building) {
        clearBuildPolling()
        await refreshBuildCompletionArtifacts()
      }
    } catch (error) {
      console.error('轮询索引构建状态失败', error)
      consecutiveErrorCount += 1
      if (consecutiveErrorCount >= 10) {
        showNotice('索引构建仍在后台执行，但进度轮询连续失败，请稍后手动刷新。', 'warning')
        clearBuildPolling()
      }
    }
  }, 3000)
}

async function refreshBuildCompletionArtifacts() {
  try {
    await loadDocumentDetail()
    await Promise.all([
      loadBuildProgress({ resetLogs: true }),
      loadDocumentChunks(chunkCurrentPage.value, { resetCollapse: false }),
      loadDocumentRagSnapshot()
    ])
  } catch (error) {
    console.error('刷新构建完成后的产物失败', error)
  }
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
  artifactContent.value = null
  parseArtifactQuery.value = null
  chunkDetailDrawerOpen.value = false
  artifactPreviewDrawerOpen.value = false
  chunkDetailFocusMode.value = 'chunk'
  artifactSearchKeyword.value = ''
  artifactPreviewCollapsed.value = false
  selectedPageOverlayNo.value = null
  selectedOverlayId.value = ''
  selectedOverlayTypes.value = [...DEFAULT_PAGE_OVERLAY_TYPES]
  selectedArtifactGraphNodeId.value = ''
  selectedKgGraphNodeId.value = ''
  selectedKgGraphEdgeId.value = ''
  selectedKgGraphCommunityId.value = ''
  selectedKgEntityType.value = 'ALL'
  selectedKgQualityLevel.value = 'ALL'
  selectedKgCommunityFilter.value = 'ALL'
  selectedRaptorTreeNodeId.value = ''
  selectedRaptorQualityLevel.value = 'ALL'
  selectedRaptorSummaryStatus.value = 'ALL'
  selectedRaptorSummaryStrategy.value = 'ALL'
  pageOverlayImageContent.value = null
  pageOverlayImageLoading.value = false
  parseRouteDialogOpen.value = false
  parseRouteDialogTaskId.value = ''
  await loadAll()
  applyRouteWorkbenchFocus()
  openInitialParseRouteProgressIfNeeded()
  await nextTick()
})

watch(() => route.query, async () => {
  applyRouteWorkbenchFocus()
  if (firstQueryValue(route.query?.showParseProgress) === '1') {
    openParseRouteDialog(firstQueryValue(route.query?.parseTaskId) || '')
  }
  if (documentId.value) {
    await loadDocumentRagSnapshot()
  }
}, { deep: true })

watch(() => selectedPageOverlay.value?.pageImageArtifactId, () => {
  loadSelectedPageOverlayImage()
})

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
  openInitialParseRouteProgressIfNeeded()
  await nextTick()
  if (!parseRouteDialogOpen.value && !strategyPlan.value?.planReady && normalizeCode(strategyPlan.value?.parseStatus) !== '4') {
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
  if (type === 'warning') return 'bg-amber-500/10 text-amber-700'
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
  if (state === 'done' || state === 'completed') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (state === 'ready') return 'border-primary/20 bg-primary/[0.04]'
  if (state === 'blocked') return 'border-amber-500/20 bg-amber-500/[0.04]'
  return 'border-border bg-secondary'
}
function strategyStatusStepClass(status) {
  if (status === 'done' || status === 'completed') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (status === 'current') return 'border-primary/20 bg-primary/[0.04]'
  if (status === 'failed') return 'border-destructive/20 bg-destructive/[0.04]'
  return 'border-border bg-secondary'
}
function buildStageClass(status) {
  if (status === 'done' || status === 'completed') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (status === 'current') return 'border-primary/20 bg-primary/[0.04]'
  if (status === 'error' || status === 'failed') return 'border-destructive/20 bg-destructive/[0.04]'
  return 'border-border bg-card'
}
function buildOverlayStageClass(status) {
  if (status === 'done' || status === 'completed') return 'border-[var(--color-success)]/20 bg-[var(--color-success)]/[0.04]'
  if (status === 'current') return 'border-primary/20 bg-primary/[0.04]'
  if (status === 'failed') return 'border-destructive/20 bg-destructive/[0.04]'
  return 'border-border bg-card/50'
}
function chunkChipClass(code) {
  if (code === '2') return 'bg-[var(--color-success)]/10 text-[var(--color-success)]'
  if (code === '3') return 'bg-destructive/10 text-destructive'
  return 'bg-foreground/[0.06] text-muted-foreground'
}
function chunkStatusIcon(code) {
  if (code === '3') return ExclamationCircleIcon
  return ClockIcon
}
function chunkStatusTextClass(code) {
  if (code === '3') return 'text-[#2f7d62]'
  return 'text-muted-foreground'
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
