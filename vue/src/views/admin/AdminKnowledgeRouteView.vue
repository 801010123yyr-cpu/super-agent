<template>
  <section class="flex flex-col gap-4">
    <header class="flex items-start justify-between gap-5 rounded-lg border border-border bg-card px-[26px] py-6 shadow-sm max-[900px]:flex-col">
      <div>
        <h3 class="m-0 mt-0.5 text-base font-semibold text-foreground">知识路由配置</h3>
        <p class="mt-1.5 text-sm text-muted-foreground">按 范围 → 主题 → 画像 → 关联 的顺序逐步配置，构建自动知识问答的候选预选体系。</p>
      </div>
      <div class="flex flex-wrap gap-2.5 max-[900px]:w-full">
        <button class="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-4 py-2.5 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60 disabled:cursor-not-allowed" type="button" :disabled="loading || actionLoading" @click="loadAll">刷新数据</button>
        <button class="inline-flex items-center gap-1.5 rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-60 disabled:cursor-not-allowed" type="button" :disabled="!documents.length || batchLoading" @click="regenerateAllProfiles">{{ batchLoading ? '批量重建中...' : '批量重建画像' }}</button>
      </div>
    </header>

    <div v-if="notice.message" class="rounded-md px-4 py-3 text-sm font-medium" :class="noticeClass(notice.type)">{{ notice.message }}</div>

    <div class="grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(170px,1fr))">
      <article v-for="item in summaryCards" :key="item.label" class="grid gap-2 rounded-lg border border-border bg-card p-4 shadow-sm">
        <span class="text-xs text-muted-foreground">{{ item.label }}</span>
        <strong class="text-[22px] text-foreground">{{ item.value }}</strong>
        <small class="text-xs text-muted-foreground">{{ item.description }}</small>
      </article>
    </div>

    <div class="rounded-lg border border-border bg-card p-[18px] shadow-sm">
      <div class="flex cursor-pointer select-none items-start justify-between gap-4" @click="coveragePanelCollapsed = !coveragePanelCollapsed">
        <div>
          <h4 class="m-0 mt-1.5 text-sm font-semibold text-foreground">范围覆盖率统计</h4>
        </div>
        <div class="flex items-center gap-2.5">
          <span class="inline-flex items-center rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">整体覆盖率 {{ overallCoverageRateText }}</span>
          <span class="text-xs text-muted-foreground transition-transform" :class="coveragePanelCollapsed ? '-rotate-90' : ''">&#9660;</span>
        </div>
      </div>
      <div v-show="!coveragePanelCollapsed" class="mt-4 grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(220px,1fr))">
        <article v-for="item in scopeCoverageRows" :key="item.scopeCode"
          class="grid gap-2.5 rounded-xl border border-border p-3.5"
          :class="item.pendingTopicCount > 0 ? 'bg-gradient-to-br from-amber-500/[0.07] to-white/90' : 'bg-card'">
          <div class="flex items-start justify-between gap-2">
            <div>
              <strong class="block text-sm text-foreground">{{ item.scopeName }}</strong>
              <span class="text-xs text-muted-foreground">{{ item.scopeCode }}</span>
            </div>
            <span class="text-sm font-bold text-foreground">{{ item.coverageRateText }}</span>
          </div>
          <div class="mt-1 h-2 overflow-hidden rounded-full bg-foreground/[0.08]">
            <span class="block h-full rounded-full bg-gradient-to-r from-primary to-[#0f766e]" :style="{ width: item.coverageRateText }"></span>
          </div>
          <div class="flex flex-wrap gap-2.5 text-xs text-muted-foreground">
            <span>主题 {{ item.topicCount }}</span>
            <span>已覆盖 {{ item.coveredTopicCount }}</span>
            <span>未关联 {{ item.pendingTopicCount }}</span>
            <span>文档 {{ item.documentCount }}</span>
          </div>
        </article>
      </div>
    </div>

    <nav class="flex gap-1 rounded-lg border border-border bg-card p-1.5 shadow-sm max-[1080px]:flex-wrap">
      <button v-for="tab in TAB_LIST" :key="tab.key"
        class="flex flex-1 items-center gap-2.5 rounded-lg px-4 py-3 transition-colors"
        :class="activeTab === tab.key ? 'bg-primary/[0.08]' : 'hover:bg-primary/[0.04]'"
        type="button" @click="activeTab = tab.key">
        <span class="grid h-[26px] w-[26px] shrink-0 place-items-center rounded-full text-xs font-bold"
          :class="activeTab === tab.key ? 'bg-primary text-white' : 'bg-foreground/[0.08] text-muted-foreground'">{{ tab.step }}</span>
        <span class="whitespace-nowrap font-semibold text-foreground">{{ tab.label }}</span>
        <span class="whitespace-nowrap text-xs text-muted-foreground max-[1080px]:hidden">{{ tab.hint }}</span>
      </button>
    </nav>

    <section v-show="activeTab === 'scope'" class="tab-content">
      <div class="rounded-lg border border-border bg-card p-[22px] shadow-sm">
        <div class="flex items-start justify-between gap-4 max-[900px]:flex-col">
          <div><h4 class="m-0 text-sm font-semibold text-foreground">知识范围</h4><p class="mt-1 text-sm text-muted-foreground">先把大范围定清楚，自动知识问答才能稳定地在正确文档池里预选。</p></div>
          <button class="inline-flex items-center rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90" type="button" @click="openCreateDrawer('scope')">新建范围</button>
        </div>
        <div class="mt-3.5"><input v-model.trim="scopeKeyword" class="w-full rounded-md border border-border bg-card px-3 py-2.5 text-sm text-foreground focus:border-ring focus:outline-none focus:ring-2 focus:ring-ring/20" placeholder="按范围编码、名称或描述筛选" /></div>
        <div class="mt-4 grid max-h-[520px] gap-3 overflow-y-auto" style="grid-template-columns:repeat(auto-fill,minmax(280px,1fr))">
          <article v-for="item in filteredScopes" :key="item.scopeCode"
            class="grid cursor-pointer gap-2 rounded-xl border p-3.5 transition-all"
            :class="item.scopeCode === activeScopeCode ? 'border-primary/30 bg-primary/[0.04]' : 'border-border bg-secondary hover:border-primary/20 hover:shadow-sm'"
            @click="openDrawer('scope', item, 'view')">
            <div class="flex items-center justify-between gap-2"><strong class="text-sm text-foreground">{{ item.scopeName }}</strong></div>
            <small class="line-clamp-2 text-xs text-muted-foreground">{{ item.description || '暂无描述' }}</small>
            <div class="flex gap-3 text-xs text-muted-foreground">
              <span>主题 {{ topics.filter(t => t.scopeCode === item.scopeCode).length }}</span>
              <span>文档 {{ documents.filter(d => d.knowledgeScopeCode === item.scopeCode).length }}</span>
            </div>
          </article>
          <div v-if="!filteredScopes.length" class="text-sm text-muted-foreground">没有匹配的知识范围。</div>
        </div>
      </div>
    </section>

    <section v-show="activeTab === 'topic'" class="tab-content">
      <div class="rounded-lg border border-border bg-card p-[22px] shadow-sm">
        <div class="flex items-start justify-between gap-4 max-[900px]:flex-col">
          <div><h4 class="m-0 text-sm font-semibold text-foreground">知识主题</h4><p class="mt-1 text-sm text-muted-foreground">主题是范围里的可回答单元，后续会通过主题文档关联把文档候选进一步收窄。</p></div>
          <button class="inline-flex items-center rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90" type="button" @click="openCreateDrawer('topic')">新建主题</button>
        </div>
        <div class="mt-3.5 grid items-center gap-2.5 max-[1080px]:grid-cols-1" style="grid-template-columns:180px minmax(0,1fr)">
          <select v-model="activeScopeCode" class="rounded-md border border-border bg-card px-3 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20">
            <option value="">全部范围</option>
            <option v-for="item in scopes" :key="item.scopeCode" :value="item.scopeCode">{{ item.scopeName }}</option>
          </select>
          <input v-model.trim="topicKeyword" class="w-full rounded-md border border-border bg-card px-3 py-2.5 text-sm text-foreground focus:border-ring focus:outline-none focus:ring-2 focus:ring-ring/20" placeholder="按主题编码、名称、别名或描述筛选" />
        </div>
        <div class="mt-4 grid max-h-[520px] gap-3 overflow-y-auto" style="grid-template-columns:repeat(auto-fill,minmax(280px,1fr))">
          <article v-for="item in filteredTopics" :key="item.topicCode"
            class="grid cursor-pointer gap-2 rounded-xl border p-3.5 transition-all"
            :class="item.topicCode === activeTopicCode ? 'border-primary/30 bg-primary/[0.04]' : 'border-border bg-secondary hover:border-primary/20 hover:shadow-sm'"
            @click="openDrawer('topic', item, 'view')">
            <strong class="text-sm text-foreground">{{ item.topicName }}</strong>
            <div class="flex flex-wrap gap-2">
              <span class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-foreground">{{ formatAnswerShapeLabel(item.answerShape) }}</span>
              <span class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-foreground">{{ formatExecutionPreferenceLabel(item.executionPreference) }}</span>
            </div>
            <small class="line-clamp-2 text-xs text-muted-foreground">{{ item.description || '暂无描述' }}</small>
          </article>
          <div v-if="!filteredTopics.length" class="text-sm text-muted-foreground">当前范围下还没有主题。</div>
        </div>
      </div>
    </section>

    <section v-show="activeTab === 'profile'" class="tab-content">
      <div class="rounded-lg border border-border bg-card p-[22px] shadow-sm">
        <div class="flex items-start justify-between gap-4 max-[900px]:flex-col">
          <div><h4 class="m-0 text-sm font-semibold text-foreground">文档画像</h4><p class="mt-1 text-sm text-muted-foreground">查看文档的类型、摘要、核心主题和图能力开关，判断自动路由是否有足够信息。</p></div>
        </div>
        <div class="mt-3.5"><input v-model.trim="documentKeyword" class="w-full rounded-md border border-border bg-card px-3 py-2.5 text-sm text-foreground focus:border-ring focus:outline-none focus:ring-2 focus:ring-ring/20" placeholder="按文档名、范围、业务分类或标签筛选文档" /></div>

        <div v-if="profileAnomalyRows.length" class="mt-4 rounded-lg border border-border bg-card p-[18px]">
          <div class="flex cursor-pointer select-none items-start justify-between gap-4" @click="anomalyCollapsed = !anomalyCollapsed">
            <div>
              <h4 class="m-0 mt-1.5 text-sm font-semibold text-foreground">画像异常清单 ({{ profileAnomalyRows.length }})</h4>
            </div>
            <div class="flex items-center gap-2.5">
              <button class="inline-flex items-center rounded-full border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60 disabled:cursor-not-allowed" type="button" :disabled="!selectedProfileRepairIds.length || batchLoading" @click.stop="batchRepairProfiles">{{ batchLoading ? '修复中...' : `批量重建 ${selectedProfileRepairIds.length} 份` }}</button>
              <span class="text-xs text-muted-foreground transition-transform" :class="anomalyCollapsed ? '-rotate-90' : ''">&#9660;</span>
            </div>
          </div>
          <div v-show="!anomalyCollapsed">
            <div class="mt-3 flex items-center gap-2.5">
              <label class="inline-flex cursor-pointer items-center gap-2 rounded-full bg-secondary px-3 py-2 text-[13px] text-foreground">
                <input type="checkbox" :checked="allVisibleAnomaliesSelected" @change="toggleAllVisibleAnomalies" />
                <span>全选异常</span>
              </label>
            </div>
            <div class="mt-3 grid gap-3" style="grid-template-columns:repeat(auto-fit,minmax(220px,1fr))">
              <article v-for="item in profileAnomalyRows" :key="`anomaly-${item.documentId}`"
                class="grid grid-cols-[auto_1fr_auto] items-start gap-3 rounded-xl border p-3.5"
                :class="item.tone === 'danger' ? 'bg-gradient-to-br from-red-500/[0.06] to-white/92' : 'bg-gradient-to-br from-amber-500/[0.07] to-white/90'">
                <label class="inline-flex cursor-pointer items-center">
                  <input type="checkbox" class="accent-primary" :checked="selectedProfileRepairIds.includes(item.documentId)" @change="toggleProfileRepair(item.documentId)" />
                </label>
                <div class="grid gap-2">
                  <strong class="text-sm text-foreground">{{ item.documentName }}</strong>
                  <span class="text-xs text-muted-foreground">{{ item.scopeText }}</span>
                  <div class="flex flex-wrap gap-1.5">
                    <span v-for="problem in item.problems" :key="`${item.documentId}-${problem}`" class="inline-flex rounded-full bg-amber-500/[0.14] px-2.5 py-1 text-xs text-amber-700">{{ problem }}</span>
                  </div>
                </div>
                <button class="inline-flex items-center rounded-full border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click.stop="selectAnomalyDocument(item); openDrawer('profile', selectedProfileDocument, 'view')">查看</button>
              </article>
            </div>
          </div>
        </div>

        <div class="mt-4 grid max-h-[520px] gap-3 overflow-y-auto" style="grid-template-columns:repeat(auto-fill,minmax(280px,1fr))">
          <article v-for="item in filteredDocuments" :key="item.documentId"
            class="grid cursor-pointer gap-2 rounded-xl border p-3.5 transition-all"
            :class="item.documentId === profileDocumentId ? 'border-primary/30 bg-primary/[0.04]' : 'border-border bg-secondary hover:border-primary/20 hover:shadow-sm'"
            @click="selectDocument(item); openDrawer('profile', item, 'view')">
            <strong class="text-sm text-foreground">{{ item.documentName }}</strong>
            <small class="text-xs text-muted-foreground">{{ documentMetaLine(item) }}</small>
          </article>
          <div v-if="!filteredDocuments.length" class="text-sm text-muted-foreground">没有匹配的文档。</div>
        </div>
      </div>
    </section>

    <section v-show="activeTab === 'relation'" class="tab-content">
      <div class="rounded-lg border border-border bg-card p-[22px] shadow-sm">
        <div class="flex items-start justify-between gap-4 max-[900px]:flex-col">
          <div><h4 class="m-0 text-sm font-semibold text-foreground">主题文档关联</h4><p class="mt-1 text-sm text-muted-foreground">把"哪个主题该优先看哪份文档"显式维护下来，低置信自动路由时会直接受益。</p></div>
          <button class="inline-flex items-center rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90" type="button" @click="openCreateDrawer('relation')">新建关联</button>
        </div>
        <div class="mt-3.5 grid items-center gap-2.5 max-[1080px]:grid-cols-1" style="grid-template-columns:180px minmax(0,1fr) auto">
          <select v-model="activeScopeCode" class="rounded-md border border-border bg-card px-3 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring/20">
            <option value="">全部范围</option>
            <option v-for="item in scopes" :key="item.scopeCode" :value="item.scopeCode">{{ item.scopeName }}</option>
          </select>
          <input v-model.trim="relationKeyword" class="w-full rounded-md border border-border bg-card px-3 py-2.5 text-sm text-foreground focus:border-ring focus:outline-none focus:ring-2 focus:ring-ring/20" placeholder="按主题、文档、原因筛选关联结果" />
          <button class="inline-flex items-center rounded-full border border-border bg-card px-3 py-2.5 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60" type="button" :disabled="actionLoading" @click="loadRelations">刷新</button>
        </div>
        <div class="mt-3.5 flex items-center gap-2.5">
          <span class="inline-flex items-center rounded-full bg-secondary px-3 py-1.5 text-xs text-foreground">{{ relations.length }} 条可见关联</span>
        </div>
        <div class="mt-3.5 grid max-h-[520px] gap-2 overflow-y-auto">
          <article v-for="item in relations" :key="`${item.topicCode}-${item.documentId}`"
            class="flex cursor-pointer items-center justify-between gap-3 rounded-xl border border-border bg-secondary p-3 transition-colors hover:border-primary/20 max-[900px]:flex-col max-[900px]:items-start"
            @click="openDrawer('relation', item, 'view')">
            <div class="grid gap-1 min-w-0">
              <strong class="text-sm text-foreground">{{ item.documentName }}</strong>
              <span class="text-xs text-muted-foreground">{{ item.topicCode }} · 分数 {{ item.relationScore }} · {{ item.knowledgeScopeName || item.knowledgeScopeCode || '未分范围' }}</span>
              <small class="text-xs text-muted-foreground">{{ item.reason || documentMetaLine(item) }}</small>
            </div>
            <button class="inline-flex shrink-0 items-center rounded-full border border-destructive/[0.14] bg-destructive/[0.06] px-3 py-1.5 text-sm font-semibold text-destructive hover:bg-destructive/10 disabled:opacity-60 disabled:cursor-not-allowed" type="button" :disabled="actionLoading" @click.stop="removeRelation(item)">移除</button>
          </article>
          <div v-if="!relations.length" class="text-sm text-muted-foreground">当前筛选下还没有保存的文档关联。</div>
        </div>
      </div>
    </section>

    <transition name="drawer-fade">
      <div v-if="drawerVisible" class="fixed inset-0 z-50 bg-[rgba(15,23,42,0.3)]" @click="closeDrawer"></div>
    </transition>
    <transition name="drawer-slide">
      <aside v-if="drawerVisible" class="fixed bottom-0 right-0 top-0 z-[51] flex w-[480px] max-w-[90vw] flex-col bg-card shadow-[-4px_0_24px_rgba(15,23,42,0.12)] max-[900px]:w-screen max-[900px]:max-w-screen">
        <div class="flex items-center justify-between border-b border-border px-6 py-5">
          <h4 class="m-0 text-sm font-semibold text-foreground">
            <template v-if="drawerType === 'scope'">{{ drawerMode === 'edit' && !drawerTarget ? '新建知识范围' : '知识范围详情' }}</template>
            <template v-else-if="drawerType === 'topic'">{{ drawerMode === 'edit' && !drawerTarget ? '新建知识主题' : '知识主题详情' }}</template>
            <template v-else-if="drawerType === 'profile'">文档画像详情</template>
            <template v-else-if="drawerType === 'relation'">{{ drawerMode === 'edit' && !drawerTarget ? '新建主题文档关联' : '关联详情' }}</template>
          </h4>
          <button class="inline-flex items-center rounded-full border border-border bg-card px-3 py-1.5 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="closeDrawer">关闭</button>
        </div>
        <div class="flex-1 overflow-y-auto px-6 py-5">

          <template v-if="drawerType === 'scope'">
            <template v-if="drawerMode === 'view' && drawerTarget">
              <div class="grid gap-3.5">
                <div v-for="row in scopeViewRows" :key="row.label" class="grid gap-1"><span class="text-xs text-muted-foreground">{{ row.label }}</span><strong class="text-sm text-foreground">{{ row.value }}</strong></div>
                <div v-if="drawerTarget.aliases" class="grid gap-2"><p class="m-0 text-xs text-muted-foreground">别名</p><div class="flex flex-wrap gap-2"><span v-for="a in parseTextList(drawerTarget.aliases)" :key="a" class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-foreground">{{ a }}</span></div></div>
                <div v-if="drawerTarget.examples" class="grid gap-2"><p class="m-0 text-xs text-muted-foreground">典型问题</p><div class="flex flex-wrap gap-2"><span v-for="e in parseJsonArray(drawerTarget.examples)" :key="e" class="inline-flex rounded-full bg-primary/[0.08] px-2.5 py-1 text-xs text-[var(--color-primary-strong)]">{{ e }}</span></div></div>
              </div>
            </template>
            <template v-if="drawerMode === 'edit'">
              <div class="mt-4 grid gap-2.5">
                <input v-model="scopeForm.scopeCode" class="drawer-input" placeholder="范围编码，例如 operation_rule" />
                <input v-model="scopeForm.scopeName" class="drawer-input" placeholder="范围名称，例如 运营规则" />
                <input v-model="scopeForm.parentScopeCode" class="drawer-input" placeholder="父级编码，可空" />
                <input v-model="scopeForm.aliases" class="drawer-input" placeholder="别名，英文逗号分隔" />
                <input v-model="scopeForm.sortOrder" class="drawer-input" placeholder="排序值" />
                <textarea v-model="scopeForm.description" class="drawer-input min-h-[74px] resize-y" placeholder="范围描述"></textarea>
                <textarea v-model="scopeForm.examples" class="drawer-input min-h-[74px] resize-y" placeholder='典型问题 JSON，例如 ["上线观察多久"]'></textarea>
              </div>
            </template>
          </template>

          <template v-if="drawerType === 'topic'">
            <template v-if="drawerMode === 'view' && drawerTarget">
              <div class="grid gap-3.5">
                <div v-for="row in topicViewRows" :key="row.label" class="grid gap-1"><span class="text-xs text-muted-foreground">{{ row.label }}</span><strong class="text-sm text-foreground">{{ row.value }}</strong></div>
                <div v-if="drawerTarget.aliases" class="grid gap-2"><p class="m-0 text-xs text-muted-foreground">别名</p><div class="flex flex-wrap gap-2"><span v-for="a in parseTextList(drawerTarget.aliases)" :key="a" class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-foreground">{{ a }}</span></div></div>
                <div v-if="drawerTarget.examples" class="grid gap-2"><p class="m-0 text-xs text-muted-foreground">典型问题</p><div class="flex flex-wrap gap-2"><span v-for="e in parseJsonArray(drawerTarget.examples)" :key="e" class="inline-flex rounded-full bg-primary/[0.08] px-2.5 py-1 text-xs text-[var(--color-primary-strong)]">{{ e }}</span></div></div>
              </div>
            </template>
            <template v-if="drawerMode === 'edit'">
              <div class="mt-4 grid gap-2.5">
                <input v-model="topicForm.topicCode" class="drawer-input" placeholder="主题编码" />
                <input v-model="topicForm.topicName" class="drawer-input" placeholder="主题名称" />
                <select v-model="topicForm.scopeCode" class="drawer-input"><option value="">选择所属范围</option><option v-for="item in scopes" :key="item.scopeCode" :value="item.scopeCode">{{ item.scopeName }}</option></select>
                <input v-model="topicForm.aliases" class="drawer-input" placeholder="别名，英文逗号分隔" />
                <select v-model="topicForm.answerShape" class="drawer-input"><option value="">选择回答形态</option><option v-for="item in ANSWER_SHAPE_OPTIONS" :key="item.value" :value="item.value">{{ item.label }}</option></select>
                <select v-model="topicForm.executionPreference" class="drawer-input"><option value="">选择执行偏好</option><option v-for="item in EXECUTION_PREFERENCE_OPTIONS" :key="item.value" :value="item.value">{{ item.label }}</option></select>
                <input v-model="topicForm.sortOrder" class="drawer-input" placeholder="排序值" />
                <textarea v-model="topicForm.description" class="drawer-input min-h-[74px] resize-y" placeholder="主题描述"></textarea>
                <textarea v-model="topicForm.examples" class="drawer-input min-h-[74px] resize-y" placeholder="典型问题 JSON"></textarea>
              </div>
            </template>
          </template>

          <template v-if="drawerType === 'profile'">
            <div class="grid gap-3.5">
              <div class="grid gap-1"><span class="text-xs text-muted-foreground">文档名称</span><strong class="text-sm text-foreground">{{ selectedProfileDocument?.documentName || '-' }}</strong></div>
              <div class="grid gap-1"><span class="text-xs text-muted-foreground">元数据</span><small class="text-xs text-muted-foreground">{{ selectedProfileDocumentMeta }}</small></div>
            </div>
            <div class="mt-3 flex flex-wrap gap-2.5">
              <button class="inline-flex items-center rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-60 disabled:cursor-not-allowed" type="button" :disabled="!profileDocumentId || actionLoading" @click="loadProfile">查看画像</button>
              <button class="inline-flex items-center rounded-full border border-border bg-card px-4 py-2.5 text-sm font-semibold text-foreground hover:bg-secondary disabled:opacity-60 disabled:cursor-not-allowed" type="button" :disabled="!profileDocumentId || actionLoading" @click="regenerateProfile">重新生成</button>
            </div>
            <div v-if="profile" class="mt-4 grid gap-4 rounded-xl border border-border bg-secondary p-4">
              <div class="flex items-start justify-between gap-3 max-[900px]:flex-col">
                <strong class="text-sm text-foreground">{{ selectedProfileDocument?.documentName || `文档 ${profileDocumentId}` }}</strong>
                <span class="inline-flex whitespace-nowrap rounded-full px-2.5 py-1.5 text-xs font-bold" :class="profileStatusClass(profile.profileStatus)">{{ profileStatusText(profile.profileStatus) }}</span>
              </div>
              <p class="m-0 leading-[1.75] text-sm text-muted-foreground">{{ profile.documentSummary || '当前画像还没有生成摘要。' }}</p>
              <div class="grid grid-cols-2 gap-2.5">
                <article v-for="card in profileMiniCards" :key="card.label" class="grid gap-1.5 rounded-lg border border-border bg-card p-3">
                  <span class="text-xs text-muted-foreground">{{ card.label }}</span><strong class="text-sm text-foreground">{{ card.value }}</strong>
                </article>
              </div>
              <div class="grid gap-2"><p class="m-0 text-xs text-muted-foreground">核心主题</p><div class="flex flex-wrap gap-1.5"><span v-for="item in parseJsonArray(profile.coreTopics)" :key="`dt-${item}`" class="inline-flex rounded-full bg-primary/[0.08] px-2.5 py-1 text-xs text-[var(--color-primary-strong)]">{{ item }}</span><span v-if="!parseJsonArray(profile.coreTopics).length" class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-muted-foreground">暂无</span></div></div>
              <div class="grid gap-2"><p class="m-0 text-xs text-muted-foreground">示例问题</p><div class="flex flex-wrap gap-1.5"><span v-for="item in parseJsonArray(profile.exampleQuestions)" :key="`dq-${item}`" class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-foreground">{{ item }}</span><span v-if="!parseJsonArray(profile.exampleQuestions).length" class="inline-flex rounded-full bg-foreground/[0.06] px-2.5 py-1 text-xs text-muted-foreground">暂无</span></div></div>
            </div>
          </template>

          <template v-if="drawerType === 'relation'">
            <template v-if="drawerMode === 'view' && drawerTarget">
              <div class="grid gap-3.5">
                <div class="grid gap-1"><span class="text-xs text-muted-foreground">主题编码</span><strong class="text-sm text-foreground">{{ drawerTarget.topicCode }}</strong></div>
                <div class="grid gap-1"><span class="text-xs text-muted-foreground">文档名称</span><strong class="text-sm text-foreground">{{ drawerTarget.documentName }}</strong></div>
                <div class="grid gap-1"><span class="text-xs text-muted-foreground">关联分数</span><strong class="text-sm text-foreground">{{ drawerTarget.relationScore }}</strong></div>
                <div class="grid gap-1"><span class="text-xs text-muted-foreground">关联来源</span><strong class="text-sm text-foreground">{{ drawerTarget.relationSource || '-' }}</strong></div>
                <div class="grid gap-1"><span class="text-xs text-muted-foreground">原因</span><p class="m-0 text-sm leading-relaxed text-foreground">{{ drawerTarget.reason || '未填写' }}</p></div>
              </div>
            </template>
            <template v-if="drawerMode === 'edit'">
              <div class="mt-4 grid gap-2.5">
                <select v-model="relationForm.topicCode" class="drawer-input"><option value="">选择主题</option><option v-for="item in topics" :key="item.topicCode" :value="item.topicCode">{{ item.topicName }}</option></select>
                <select v-model="relationForm.documentId" class="drawer-input"><option value="">选择文档</option><option v-for="item in documents" :key="item.documentId" :value="item.documentId">{{ item.documentName }}</option></select>
                <input v-model="relationForm.relationScore" class="drawer-input" placeholder="关联分数，例如 0.9200" />
                <input v-model="relationForm.reason" class="drawer-input" placeholder="关联原因" />
              </div>
            </template>
          </template>
        </div>

        <div class="flex gap-2.5 border-t border-border px-6 py-4">
          <template v-if="drawerType === 'scope'">
            <template v-if="drawerMode === 'view'"><button class="rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90" type="button" @click="switchDrawerToEdit">编辑</button><button class="rounded-full border border-destructive/[0.14] bg-destructive/[0.06] px-4 py-2.5 text-sm font-semibold text-destructive hover:bg-destructive/10 disabled:opacity-60" type="button" :disabled="actionLoading" @click="deleteScope">删除</button></template>
            <template v-else><button class="rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-60" type="button" :disabled="actionLoading" @click="saveScope">保存</button><button class="rounded-full border border-border bg-card px-4 py-2.5 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="closeDrawer">取消</button></template>
          </template>
          <template v-if="drawerType === 'topic'">
            <template v-if="drawerMode === 'view'"><button class="rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90" type="button" @click="switchDrawerToEdit">编辑</button><button class="rounded-full border border-destructive/[0.14] bg-destructive/[0.06] px-4 py-2.5 text-sm font-semibold text-destructive hover:bg-destructive/10 disabled:opacity-60" type="button" :disabled="actionLoading" @click="deleteTopic">删除</button></template>
            <template v-else><button class="rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-60" type="button" :disabled="actionLoading" @click="saveTopic">保存</button><button class="rounded-full border border-border bg-card px-4 py-2.5 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="closeDrawer">取消</button></template>
          </template>
          <template v-if="drawerType === 'profile'"><button class="rounded-full border border-border bg-card px-4 py-2.5 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="closeDrawer">关闭</button></template>
          <template v-if="drawerType === 'relation'">
            <template v-if="drawerMode === 'view'"><button class="rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90" type="button" @click="switchDrawerToEdit">编辑</button><button class="rounded-full border border-destructive/[0.14] bg-destructive/[0.06] px-4 py-2.5 text-sm font-semibold text-destructive hover:bg-destructive/10 disabled:opacity-60" type="button" :disabled="actionLoading" @click="removeRelation(drawerTarget); closeDrawer()">移除</button></template>
            <template v-else><button class="rounded-full bg-primary px-4 py-2.5 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-60" type="button" :disabled="actionLoading" @click="saveRelation">保存</button><button class="rounded-full border border-border bg-card px-4 py-2.5 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="closeDrawer">取消</button></template>
          </template>
        </div>
      </aside>
    </transition>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { manageApi } from '../../api/api'
import { useConfirm } from '@/composables/useConfirm'
const { confirm } = useConfirm()

const OPERATOR_ID = '10001'
const ANSWER_SHAPE_OPTIONS = Object.freeze([
  { value: 'list', label: '列表型回答' },
  { value: 'explain', label: '解释说明型回答' },
  { value: 'steps', label: '步骤型回答' }
])
const EXECUTION_PREFERENCE_OPTIONS = Object.freeze([
  { value: 'retrieval', label: '普通检索优先' },
  { value: 'graph_assist', label: '图辅助优先' }
])
const DOCUMENT_TYPE_OPTIONS = Object.freeze([
  { value: 'intro', label: '介绍型文档' }, { value: 'manual', label: '操作手册' },
  { value: 'rule', label: '规则文档' }, { value: 'faq', label: '常见问题' },
  { value: 'troubleshooting', label: '故障排查' }, { value: 'spec', label: '规格说明' }
])
const PROFILE_SOURCE_OPTIONS = Object.freeze([
  { value: 'auto', label: '自动生成' }, { value: 'manual', label: '手动维护' }, { value: 'mixed', label: '自动 + 手动' }
])
const ANSWER_SHAPE_LABEL_MAP = Object.freeze(ANSWER_SHAPE_OPTIONS.reduce((r, i) => { r[i.value] = i.label; return r }, {}))
const EXECUTION_PREFERENCE_LABEL_MAP = Object.freeze(EXECUTION_PREFERENCE_OPTIONS.reduce((r, i) => { r[i.value] = i.label; return r }, {}))
const DOCUMENT_TYPE_LABEL_MAP = Object.freeze(DOCUMENT_TYPE_OPTIONS.reduce((r, i) => { r[i.value] = i.label; return r }, {}))
const PROFILE_SOURCE_LABEL_MAP = Object.freeze(PROFILE_SOURCE_OPTIONS.reduce((r, i) => { r[i.value] = i.label; return r }, {}))

const loading = ref(false)
const actionLoading = ref(false)
const batchLoading = ref(false)
const scopes = ref([])
const topics = ref([])
const documents = ref([])
const allRelations = ref([])
const profileDocumentId = ref('')
const profile = ref(null)
const activeScopeCode = ref('')
const activeTopicCode = ref('')
const scopeKeyword = ref('')
const topicKeyword = ref('')
const documentKeyword = ref('')
const relationKeyword = ref('')
const selectedProfileRepairIds = ref([])
const scopeSectionRef = ref(null)
const profileSectionRef = ref(null)
const relationSectionRef = ref(null)
const notice = reactive({ type: 'info', message: '' })
const activeTab = ref('scope')
const TAB_LIST = Object.freeze([
  { key: 'scope', label: '知识范围', step: 1, hint: '定义知识领域边界' },
  { key: 'topic', label: '知识主题', step: 2, hint: '范围下的可回答单元' },
  { key: 'profile', label: '文档画像', step: 3, hint: '文档类型与能力分析' },
  { key: 'relation', label: '主题文档关联', step: 4, hint: '主题与文档的绑定关系' }
])
const drawerVisible = ref(false)
const drawerMode = ref('view')
const drawerTarget = ref(null)
const drawerType = ref('')
const coveragePanelCollapsed = ref(false)
const anomalyCollapsed = ref(true)
const scopeForm = reactive({ scopeCode: '', scopeName: '', parentScopeCode: '', description: '', aliases: '', examples: '', sortOrder: '0', operatorId: OPERATOR_ID })
const topicForm = reactive({ topicCode: '', topicName: '', scopeCode: '', description: '', aliases: '', examples: '', answerShape: '', executionPreference: '', sortOrder: '0', operatorId: OPERATOR_ID })
const relationForm = reactive({ topicCode: '', documentId: '', relationScore: '0.9000', relationSource: 'manual', reason: '', operatorId: OPERATOR_ID })

const activeScope = computed(() => scopes.value.find((item) => item.scopeCode === activeScopeCode.value) || null)
const activeTopic = computed(() => topics.value.find((item) => item.topicCode === activeTopicCode.value) || null)
const filteredScopes = computed(() => {
  const keyword = scopeKeyword.value.trim().toLowerCase()
  if (!keyword) return scopes.value
  return scopes.value.filter((item) => [item.scopeCode, item.scopeName, item.description, item.aliases].filter(Boolean).join(' ').toLowerCase().includes(keyword))
})
const filteredTopics = computed(() => {
  const keyword = topicKeyword.value.trim().toLowerCase()
  return topics.value.filter((item) => {
    if (activeScopeCode.value && item.scopeCode !== activeScopeCode.value) return false
    if (!keyword) return true
    return [item.topicCode, item.topicName, item.description, item.aliases].filter(Boolean).join(' ').toLowerCase().includes(keyword)
  })
})
const filteredDocuments = computed(() => {
  const keyword = documentKeyword.value.trim().toLowerCase()
  return documents.value.filter((item) => {
    if (activeScopeCode.value && item.knowledgeScopeCode !== activeScopeCode.value) return false
    if (!keyword) return true
    return [item.documentName, item.originalFileName, item.knowledgeScopeCode, item.knowledgeScopeName, item.businessCategory, item.documentTags].filter(Boolean).join(' ').toLowerCase().includes(keyword)
  })
})
const selectedProfileDocument = computed(() => documents.value.find((item) => item.documentId === profileDocumentId.value) || null)
const selectedProfileDocumentMeta = computed(() => selectedProfileDocument.value ? documentMetaLine(selectedProfileDocument.value) : '未选择文档')
const selectedScopeAliases = computed(() => parseTextList(activeScope.value?.aliases))
const selectedScopeExamples = computed(() => parseJsonArray(activeScope.value?.examples))
const selectedScopeStats = computed(() => {
  if (!activeScope.value) return null
  const scopeTopics = topics.value.filter((item) => item.scopeCode === activeScope.value.scopeCode)
  const topicCodes = new Set(scopeTopics.map((item) => item.topicCode))
  return { topicCount: scopeTopics.length, relationCount: allRelations.value.filter((item) => topicCodes.has(item.topicCode)).length, documentCount: documents.value.filter((item) => item.knowledgeScopeCode === activeScope.value.scopeCode).length }
})
const selectedTopicRelations = computed(() => activeTopic.value ? allRelations.value.filter((item) => item.topicCode === activeTopic.value.topicCode) : [])
const selectedTopicStats = computed(() => {
  if (!activeTopic.value) return null
  const scores = selectedTopicRelations.value.map((item) => Number(item.relationScore)).filter((item) => Number.isFinite(item))
  return { relationCount: selectedTopicRelations.value.length, linkedDocumentCount: new Set(selectedTopicRelations.value.map((item) => item.documentId)).size, averageScoreText: scores.length ? (scores.reduce((s, i) => s + i, 0) / scores.length).toFixed(4) : '-' }
})
const relations = computed(() => {
  const keyword = relationKeyword.value.trim().toLowerCase()
  return allRelations.value.filter((item) => {
    const topic = topics.value.find((t) => t.topicCode === item.topicCode)
    if (activeScopeCode.value && topic?.scopeCode !== activeScopeCode.value) return false
    if (activeTopicCode.value && item.topicCode !== activeTopicCode.value) return false
    if (!keyword) return true
    return [item.topicCode, item.documentName, item.reason, item.knowledgeScopeName, item.businessCategory, item.documentTags].filter(Boolean).join(' ').toLowerCase().includes(keyword)
  })
})
const selectedProfileMetadataMissing = computed(() => {
  if (!selectedProfileDocument.value) return []
  const missing = []
  if (!selectedProfileDocument.value.knowledgeScopeCode && !selectedProfileDocument.value.knowledgeScopeName) missing.push('知识范围')
  if (!selectedProfileDocument.value.businessCategory) missing.push('业务分类')
  if (!selectedProfileDocument.value.documentTags) missing.push('文档标签')
  return missing
})
const selectedProfileRelatedTopics = computed(() => {
  if (!selectedProfileDocument.value) return []
  const topicCodes = new Set(allRelations.value.filter((item) => item.documentId === selectedProfileDocument.value.documentId).map((item) => item.topicCode))
  return topics.value.filter((item) => topicCodes.has(item.topicCode))
})
const selectedProfileDocumentTags = computed(() => parseTextList(selectedProfileDocument.value?.documentTags))
const scopeCoverageRows = computed(() => scopes.value.map((scope) => {
  const scopeTopics = topics.value.filter((t) => t.scopeCode === scope.scopeCode)
  const topicCodes = new Set(scopeTopics.map((t) => t.topicCode))
  const scopeRelations = allRelations.value.filter((r) => topicCodes.has(r.topicCode))
  const coveredTopicCodes = new Set(scopeRelations.map((r) => r.topicCode))
  const coverageRate = scopeTopics.length ? (coveredTopicCodes.size / scopeTopics.length) * 100 : 0
  return { scopeCode: scope.scopeCode, scopeName: scope.scopeName, topicCount: scopeTopics.length, coveredTopicCount: coveredTopicCodes.size, pendingTopicCount: Math.max(0, scopeTopics.length - coveredTopicCodes.size), documentCount: documents.value.filter((d) => d.knowledgeScopeCode === scope.scopeCode).length, relationCount: scopeRelations.length, coverageRate, coverageRateText: `${coverageRate.toFixed(0)}%` }
}))
const overallCoverageRateText = computed(() => {
  if (!topics.value.length) return '0%'
  const coveredTopicCodes = new Set(allRelations.value.map((r) => r.topicCode))
  return `${((coveredTopicCodes.size / topics.value.length) * 100).toFixed(0)}%`
})
const profileAnomalyRows = computed(() => {
  const scopeCodes = new Set(scopes.value.map((s) => s.scopeCode))
  const linkedDocumentIds = new Set(allRelations.value.map((r) => String(r.documentId)))
  return documents.value.map((document) => {
    const problems = []
    if (!document.knowledgeScopeCode && !document.knowledgeScopeName) problems.push('缺少知识范围')
    if (document.knowledgeScopeCode && !scopeCodes.has(document.knowledgeScopeCode)) problems.push('范围未建节点')
    if (!document.businessCategory) problems.push('缺少业务分类')
    if (!document.documentTags) problems.push('缺少标签')
    if (!linkedDocumentIds.has(String(document.documentId))) problems.push('未绑定主题')
    return { documentId: String(document.documentId), documentName: document.documentName, scopeText: document.knowledgeScopeName || document.knowledgeScopeCode || '未分配范围', problems, tone: problems.length >= 3 ? 'danger' : 'warning', suggestion: buildAnomalySuggestion(problems) }
  }).filter((item) => item.problems.length > 0)
})
const allVisibleAnomaliesSelected = computed(() => profileAnomalyRows.value.length && profileAnomalyRows.value.every((item) => selectedProfileRepairIds.value.includes(item.documentId)))
const summaryCards = computed(() => {
  const documentWithMetaCount = documents.value.filter((item) => Boolean(item.knowledgeScopeCode || item.knowledgeScopeName || item.businessCategory || item.documentTags)).length
  const pendingTopicCount = topics.value.filter((item) => !allRelations.value.some((r) => r.topicCode === item.topicCode)).length
  return [
    { label: '知识范围', value: String(scopes.value.length), description: '知识范围是自动路由的第一层收敛边界' },
    { label: '知识主题', value: String(topics.value.length), description: '主题是范围里的可回答单元' },
    { label: '文档数', value: String(documents.value.length), description: '当前可维护画像和路由元数据的文档数量' },
    { label: '已补元数据文档', value: String(documentWithMetaCount), description: '至少填了范围、业务类目或标签的文档数' },
    { label: '已保存关联', value: String(allRelations.value.length), description: '当前所有主题已保存的文档关联数' },
    { label: '未关联主题', value: String(pendingTopicCount), description: '还没有绑定任何文档关系的主题数' }
  ]
})
const scopeViewRows = computed(() => !drawerTarget.value ? [] : [
  { label: '范围编码', value: drawerTarget.value.scopeCode },
  { label: '范围名称', value: drawerTarget.value.scopeName },
  { label: '父级编码', value: drawerTarget.value.parentScopeCode || '-' },
  { label: '排序值', value: drawerTarget.value.sortOrder },
  { label: '描述', value: drawerTarget.value.description || '暂无描述' }
])
const topicViewRows = computed(() => !drawerTarget.value ? [] : [
  { label: '主题编码', value: drawerTarget.value.topicCode },
  { label: '主题名称', value: drawerTarget.value.topicName },
  { label: '所属范围', value: drawerTarget.value.scopeCode },
  { label: '回答形态', value: formatAnswerShapeLabel(drawerTarget.value.answerShape) },
  { label: '执行偏好', value: formatExecutionPreferenceLabel(drawerTarget.value.executionPreference) },
  { label: '排序值', value: drawerTarget.value.sortOrder },
  { label: '描述', value: drawerTarget.value.description || '暂无描述' }
])
const profileMiniCards = computed(() => !profile.value ? [] : [
  { label: '文档类型', value: formatDocumentTypeLabel(profile.value.documentType) },
  { label: '画像来源', value: formatProfileSourceLabel(profile.value.profileSource) },
  { label: '图能力', value: graphCapabilityText(profile.value) },
  { label: '核心主题数', value: parseJsonArray(profile.value.coreTopics).length }
])

watch(() => relationForm.topicCode, (value) => {
  activeTopicCode.value = value || ''
  const currentTopic = topics.value.find((item) => item.topicCode === value)
  if (currentTopic?.scopeCode) activeScopeCode.value = currentTopic.scopeCode
})

function noticeClass(type) {
  if (type === 'success') return 'bg-[#ecfdf3] text-[#027a48]'
  if (type === 'danger') return 'bg-[#fef3f2] text-[#b42318]'
  return 'bg-primary/[0.08] text-primary'
}
function openDrawer(type, item, mode = 'view') { drawerType.value = type; drawerTarget.value = item ? { ...item } : null; drawerMode.value = mode; drawerVisible.value = true }
function closeDrawer() { drawerVisible.value = false; drawerTarget.value = null; drawerMode.value = 'view'; drawerType.value = '' }
function switchDrawerToEdit() {
  drawerMode.value = 'edit'
  if (drawerType.value === 'scope' && drawerTarget.value) { editScope(drawerTarget.value) }
  else if (drawerType.value === 'topic' && drawerTarget.value) { editTopic(drawerTarget.value) }
  else if (drawerType.value === 'relation' && drawerTarget.value) { Object.assign(relationForm, { topicCode: drawerTarget.value.topicCode || '', documentId: drawerTarget.value.documentId || '', relationScore: drawerTarget.value.relationScore || '0.9000', relationSource: 'manual', reason: drawerTarget.value.reason || '', operatorId: OPERATOR_ID }) }
}
function openCreateDrawer(type) {
  if (type === 'scope') resetScopeForm()
  else if (type === 'topic') resetTopicForm()
  else if (type === 'relation') Object.assign(relationForm, { topicCode: activeTopicCode.value || '', documentId: '', relationScore: '0.9000', relationSource: 'manual', reason: '', operatorId: OPERATOR_ID })
  openDrawer(type, null, 'edit')
}
function scrollToSection(section) {
  const element = { scope: scopeSectionRef.value, profile: profileSectionRef.value, relation: relationSectionRef.value }[section]
  element?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
function focusCoverageScope(item) { activeScopeCode.value = item.scopeCode; const scope = scopes.value.find((s) => s.scopeCode === item.scopeCode); if (scope) editScope(scope); scrollToSection('scope') }
function showNotice(message, type = 'info') { notice.message = message; notice.type = type }
function resetScopeForm() { Object.assign(scopeForm, { scopeCode: '', scopeName: '', parentScopeCode: '', description: '', aliases: '', examples: '', sortOrder: '0', operatorId: OPERATOR_ID }); activeScopeCode.value = '' }
function resetTopicForm() { Object.assign(topicForm, { topicCode: '', topicName: '', scopeCode: activeScopeCode.value || '', description: '', aliases: '', examples: '', answerShape: '', executionPreference: '', sortOrder: '0', operatorId: OPERATOR_ID }); activeTopicCode.value = '' }
function editScope(item) { activeScopeCode.value = item.scopeCode; if (activeTopic.value && activeTopic.value.scopeCode !== item.scopeCode) { activeTopicCode.value = ''; relationForm.topicCode = '' }; Object.assign(scopeForm, { ...item, operatorId: OPERATOR_ID }); topicForm.scopeCode = item.scopeCode }
function editTopic(item) { activeScopeCode.value = item.scopeCode; activeTopicCode.value = item.topicCode; relationForm.topicCode = item.topicCode; Object.assign(topicForm, { ...item, operatorId: OPERATOR_ID }) }
function selectDocument(item) { profileDocumentId.value = item.documentId; profile.value = null }
async function withAction(task, successMessage = '') {
  actionLoading.value = true
  try { const result = await task(); if (successMessage) showNotice(successMessage, 'success'); return result }
  catch (error) { showNotice(error.message || '执行失败', 'danger'); return null }
  finally { actionLoading.value = false }
}
async function loadAll() {
  loading.value = true
  try {
    const [scopeList, topicList, docPage] = await Promise.all([manageApi.listKnowledgeScopes(), manageApi.listKnowledgeTopics(), manageApi.queryDocumentPage({ pageNo: '1', pageSize: '200', keyword: '' })])
    scopes.value = Array.isArray(scopeList) ? scopeList : []
    topics.value = Array.isArray(topicList) ? topicList : []
    documents.value = Array.isArray(docPage?.records) ? docPage.records : []
    if (activeScopeCode.value && !scopes.value.some((item) => item.scopeCode === activeScopeCode.value)) activeScopeCode.value = ''
    if (activeTopicCode.value && !topics.value.some((item) => item.topicCode === activeTopicCode.value)) { activeTopicCode.value = ''; relationForm.topicCode = '' }
    await loadRelations()
  } catch (error) { showNotice(error.message || '加载知识路由数据失败', 'danger') }
  finally { loading.value = false }
}
async function saveScope() { await withAction(async () => { const data = await manageApi.saveKnowledgeScope(scopeForm); activeScopeCode.value = data?.scopeCode || scopeForm.scopeCode; await loadAll(); closeDrawer() }, '知识范围已保存') }
async function deleteScope() {
  if (!activeScope.value || !await confirm(`确认删除范围「${activeScope.value.scopeName}」吗？`, '确认删除')) return
  await withAction(async () => { await manageApi.deleteKnowledgeScope({ scopeCode: activeScope.value.scopeCode, operatorId: OPERATOR_ID }); resetScopeForm(); closeDrawer(); await loadAll() }, '知识范围已删除')
}
async function saveTopic() { await withAction(async () => { const data = await manageApi.saveKnowledgeTopic(topicForm); activeTopicCode.value = data?.topicCode || topicForm.topicCode; relationForm.topicCode = activeTopicCode.value; await loadAll(); closeDrawer() }, '知识主题已保存') }
async function deleteTopic() {
  if (!activeTopic.value || !await confirm(`确认删除主题「${activeTopic.value.topicName}」吗？`, '确认删除')) return
  await withAction(async () => { await manageApi.deleteKnowledgeTopic({ topicCode: activeTopic.value.topicCode, operatorId: OPERATOR_ID }); resetTopicForm(); relationForm.topicCode = ''; closeDrawer(); await loadAll() }, '知识主题已删除')
}
async function loadProfile() { if (!profileDocumentId.value) return; await withAction(async () => { profile.value = await manageApi.queryDocumentProfile({ documentId: profileDocumentId.value }) }) }
async function regenerateProfile() { if (!profileDocumentId.value) return; await withAction(async () => { profile.value = await manageApi.regenerateDocumentProfile({ documentId: profileDocumentId.value, operatorId: OPERATOR_ID }) }, '文档画像已重新生成') }
async function regenerateAllProfiles() {
  if (!documents.value.length || !await confirm(`确认批量重建 ${documents.value.length} 份文档画像吗？`, '批量重建画像')) return
  batchLoading.value = true
  try { await manageApi.batchRegenerateDocumentProfiles({ documentIds: documents.value.map((item) => item.documentId), operatorId: OPERATOR_ID }); showNotice(`已触发 ${documents.value.length} 份文档的画像重建`, 'success'); if (profileDocumentId.value) await loadProfile() }
  catch (error) { showNotice(error.message || '批量重建文档画像失败', 'danger') }
  finally { batchLoading.value = false }
}
async function batchRepairProfiles() {
  const documentIds = [...selectedProfileRepairIds.value]
  if (!documentIds.length) { showNotice('请先选择要批量修复的文档。', 'danger'); return }
  batchLoading.value = true
  try { await manageApi.batchRegenerateDocumentProfiles({ documentIds, operatorId: OPERATOR_ID }); selectedProfileRepairIds.value = []; showNotice(`已批量重建 ${documentIds.length} 份文档画像。`, 'success'); if (profileDocumentId.value) await loadProfile(); await loadAll() }
  catch (error) { showNotice(error.message || '批量重建文档画像失败', 'danger') }
  finally { batchLoading.value = false }
}
async function loadRelations() {
  try { allRelations.value = await manageApi.listTopicDocuments({ topicCode: '' }) }
  catch (error) { showNotice(error.message || '加载主题文档关联失败', 'danger') }
}
async function saveRelation() { await withAction(async () => { await manageApi.saveTopicDocumentRelation(relationForm); await loadRelations(); closeDrawer() }, '主题文档关联已保存') }
async function removeRelation(item) { await withAction(async () => { await manageApi.removeTopicDocumentRelation({ topicCode: item.topicCode, documentId: item.documentId, operatorId: OPERATOR_ID }); await loadRelations() }, '主题文档关联已移除') }
function documentMetaLine(item = {}) { return [item.knowledgeScopeName || item.knowledgeScopeCode, item.businessCategory, item.documentTags].filter(Boolean).join(' · ') || '还没有范围 / 类目 / 标签元数据' }
function parseTextList(value) { const n = String(value || '').trim(); if (!n) return []; return n.split(',').map((item) => item.trim()).filter(Boolean) }
function formatAnswerShapeLabel(value) { return formatMappedLabel(value, ANSWER_SHAPE_LABEL_MAP) }
function formatExecutionPreferenceLabel(value) { return formatMappedLabel(value, EXECUTION_PREFERENCE_LABEL_MAP) }
function formatDocumentTypeLabel(value) { return formatMappedLabel(value, DOCUMENT_TYPE_LABEL_MAP) }
function formatProfileSourceLabel(value) { return formatMappedLabel(value, PROFILE_SOURCE_LABEL_MAP) }
function formatMappedLabel(value, labelMap) { const n = String(value || '').trim(); if (!n) return '未设置'; return labelMap[n] || n }
function buildAnomalySuggestion(problems) {
  if (problems.includes('范围未建节点')) return '建议先在知识范围区补齐对应 scopeCode，再重建画像并复测自动路由。'
  if (problems.includes('缺少知识范围') || problems.includes('缺少标签')) return '建议重新上传时补齐知识范围和文档标签；当前可先重建画像观察自动补全效果。'
  if (problems.includes('未绑定主题')) return '建议在主题文档关联区为该文档至少绑定 1 个核心主题。'
  return '建议重建画像后查看核心主题、示例问题和图能力是否恢复正常。'
}
function toggleProfileRepair(documentId) { const n = String(documentId); if (selectedProfileRepairIds.value.includes(n)) { selectedProfileRepairIds.value = selectedProfileRepairIds.value.filter((item) => item !== n); return }; selectedProfileRepairIds.value = [...selectedProfileRepairIds.value, n] }
function toggleAllVisibleAnomalies() {
  if (allVisibleAnomaliesSelected.value) { const visibleIds = new Set(profileAnomalyRows.value.map((item) => item.documentId)); selectedProfileRepairIds.value = selectedProfileRepairIds.value.filter((item) => !visibleIds.has(item)); return }
  const merged = new Set(selectedProfileRepairIds.value); profileAnomalyRows.value.forEach((item) => merged.add(item.documentId)); selectedProfileRepairIds.value = [...merged]
}
function selectAnomalyDocument(item) { profileDocumentId.value = item.documentId; profile.value = null; loadProfile() }
function parseJsonArray(value) { if (!value) return []; try { const parsed = JSON.parse(value); return Array.isArray(parsed) ? parsed.filter(Boolean) : [] } catch { return [] } }
function graphCapabilityText(profileValue = {}) { const enabled = []; if (String(profileValue.supportsGraphOutline) === '1') enabled.push('大纲导航'); if (String(profileValue.supportsItemLookup) === '1') enabled.push('条目定位'); if (String(profileValue.supportsGraphAssist) === '1') enabled.push('图辅助检索'); return enabled.length ? enabled.join(' / ') : '未开启' }
function profileStatusText(status) { if (String(status) === '2') return '已生成'; if (String(status) === '3') return '生成失败'; return '待生成' }
function profileStatusClass(status) { if (String(status) === '2') return 'bg-green-500/[0.12] text-green-700'; if (String(status) === '3') return 'bg-red-500/[0.12] text-red-700'; return 'bg-amber-500/[0.14] text-amber-700' }

onMounted(loadAll)
</script>

<style scoped>
/* 保留：Vue <transition> 钩子类名无法用 Tailwind 替换 */
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity 0.25s ease; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-slide-enter-active, .drawer-slide-leave-active { transition: transform 0.25s ease; }
.drawer-slide-enter-from, .drawer-slide-leave-to { transform: translateX(100%); }
/* tab 切换淡入动画 */
.tab-content { animation: tabFadeIn 0.2s ease; }
@keyframes tabFadeIn { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
/* drawer 表单输入公共样式（多处复用，统一定义避免重复） */
.drawer-input { width: 100%; border: 1px solid var(--color-border); border-radius: var(--radius-sm); padding: 10px 12px; background: #fff; color: var(--color-text-strong); }
.drawer-input:focus { outline: none; border-color: var(--color-primary); box-shadow: 0 0 0 3px var(--color-primary-soft); }
</style>
