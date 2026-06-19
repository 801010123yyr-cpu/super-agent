<template>
  <section class="dashboard-page">
    <header class="page-intro">
      <div class="page-intro-text">
        <h3>把文档接入、切块策略和索引构建串成一条可观察的业务流水线</h3>
        <p>
          后台聚焦文档进入系统后的关键节点：上传、推荐策略、策略确认、索引构建和对话观测。
        </p>
      </div>
      <button class="btn-primary" type="button" @click="goDocuments">前往文档接入</button>
    </header>

    <div class="metrics-grid">
      <article class="metric-card">
        <span class="metric-label">文档总数</span>
        <strong class="metric-value">{{ formatCount(summary.total) }}</strong>
        <p class="metric-hint">已进入管理台的文档记录</p>
      </article>
      <article class="metric-card">
        <span class="metric-label">解析成功</span>
        <strong class="metric-value">{{ formatCount(summary.parseSuccess) }}</strong>
        <p class="metric-hint">可进入策略确认阶段的文档</p>
      </article>
      <article class="metric-card">
        <span class="metric-label">策略已确认</span>
        <strong class="metric-value">{{ formatCount(summary.strategyConfirmed) }}</strong>
        <p class="metric-hint">已经形成最终切块链路</p>
      </article>
      <article class="metric-card">
        <span class="metric-label">索引完成</span>
        <strong class="metric-value">{{ formatCount(summary.indexSuccess) }}</strong>
        <p class="metric-hint">可直接参与 RAG 检索问答</p>
      </article>
    </div>

    <div class="dashboard-grid">
      <article class="panel-card">
        <div class="panel-header">
          <h4>建议演示路径</h4>
        </div>

        <ol class="flow-list">
          <li>
            <span class="flow-index">1</span>
            <div class="flow-body">
              <strong>上传文档</strong>
              <span>通过假登录后的管理台上传 PDF / Word / Markdown 文档。</span>
            </div>
          </li>
          <li>
            <span class="flow-index">2</span>
            <div class="flow-body">
              <strong>查看系统推荐策略</strong>
              <span>根据文档结构与内容长度，观察结构切块、递归分块、语义分块和智能切块的组合。</span>
            </div>
          </li>
          <li>
            <span class="flow-index">3</span>
            <div class="flow-body">
              <strong>确认并构建索引</strong>
              <span>在推荐结果基础上补充或移除策略，再触发异步构建索引。</span>
            </div>
          </li>
          <li>
            <span class="flow-index">4</span>
            <div class="flow-body">
              <strong>做对话观测</strong>
              <span>查看真实会话在当前文档问答与开放式提问两种模式下的执行轨迹。</span>
            </div>
          </li>
        </ol>
      </article>

      <article class="panel-card">
        <div class="panel-header">
          <h4>最近接入文档</h4>
          <button class="btn-ghost" type="button" @click="loadDashboard">刷新</button>
        </div>

        <div v-if="loading" class="empty-block">正在加载后台概览...</div>
        <div v-else-if="!documents.length" class="empty-block">当前还没有文档，先去“文档接入”页面上传一份资料。</div>

        <div v-else class="recent-list">
          <article v-for="item in documents.slice(0, 6)" :key="item.documentId" class="recent-item">
            <div class="recent-item-main">
              <strong>{{ item.documentName }}</strong>
              <p>{{ item.originalFileName }}</p>
            </div>
            <div class="recent-item-meta">
              <AdminStatusBadge :label="item.parseStatusName" :code="item.parseStatus" type="parse" />
              <AdminStatusBadge :label="item.indexStatusName" :code="item.indexStatus" type="index" />
            </div>
          </article>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { manageApi } from '../../api/api'
import AdminStatusBadge from '../../components/admin/AdminStatusBadge.vue'
import { formatCount, hasCode } from '../../utils/manageFormat'

const router = useRouter()
const loading = ref(false)
const documents = ref([])
const summary = reactive({
  total: 0,
  parseSuccess: 0,
  strategyConfirmed: 0,
  indexSuccess: 0
})

async function loadDashboard() {
  loading.value = true

  try {
    const data = await manageApi.queryDocumentPage({
      pageNo: 1,
      pageSize: 50,
      keyword: ''
    })
    documents.value = Array.isArray(data?.records) ? data.records : []

    summary.total = Number(data?.total || documents.value.length || 0)
    summary.parseSuccess = documents.value.filter((item) => hasCode(item.parseStatus, 3)).length
    summary.strategyConfirmed = documents.value.filter((item) => hasCode(item.strategyStatus, 3)).length
    summary.indexSuccess = documents.value.filter((item) => hasCode(item.indexStatus, 3)).length
  } catch (error) {
    console.error('加载后台概览失败', error)
    documents.value = []
    summary.total = 0
    summary.parseSuccess = 0
    summary.strategyConfirmed = 0
    summary.indexSuccess = 0
  } finally {
    loading.value = false
  }
}

function goDocuments() {
  router.push('/admin/documents')
}

onMounted(loadDashboard)
</script>

<style scoped>
.dashboard-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ── 页眉：直接落在灰底上，不套卡片 ── */
.page-intro {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.page-intro-text {
  max-width: 720px;
}

.page-intro h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--color-text-strong);
}

.page-intro p {
  margin: 8px 0 0;
  color: var(--color-muted);
  font-size: 13.5px;
  line-height: 1.7;
}

/* ── 按钮 ── */
.btn-primary,
.btn-ghost {
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.btn-primary {
  border: 1px solid var(--color-primary);
  color: #fff;
  background: var(--color-primary);
}

.btn-primary:hover {
  background: var(--color-primary-strong);
  border-color: var(--color-primary-strong);
}

.btn-ghost {
  color: var(--color-muted-strong);
  background: var(--color-surface);
  border: 1px solid var(--color-border-strong);
}

.btn-ghost:hover {
  color: var(--color-text-strong);
  border-color: var(--color-muted);
}

/* ── 指标卡 ── */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  padding: 18px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.metric-label {
  font-size: 12.5px;
  color: var(--color-muted);
  font-weight: 500;
}

.metric-value {
  display: block;
  margin-top: 10px;
  font-size: 26px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--color-text-strong);
}

.metric-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--color-muted);
  line-height: 1.6;
}

/* ── 双栏面板 ── */
.dashboard-grid {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 14px;
}

.panel-card {
  padding: 20px 22px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.panel-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 18px;
}

.panel-header h4 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--color-text-strong);
}

/* ── 流程步骤 ── */
.flow-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.flow-list li {
  display: flex;
  gap: 12px;
}

.flow-index {
  flex: none;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--color-primary-soft);
  color: var(--color-primary);
  font-size: 12px;
  font-weight: 600;
}

.flow-body strong {
  display: block;
  margin-bottom: 4px;
  font-size: 13.5px;
  color: var(--color-text-strong);
}

.flow-body span {
  font-size: 12.5px;
  color: var(--color-muted);
  line-height: 1.7;
}

/* ── 最近文档 ── */
.recent-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.recent-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-surface-soft);
  transition: border-color 0.15s ease, background 0.15s ease;
}

.recent-item:hover {
  border-color: var(--color-border-strong);
  background: var(--color-surface);
}

.recent-item-main strong {
  display: block;
  font-size: 13.5px;
  color: var(--color-text-strong);
}

.recent-item-main p {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--color-muted);
  word-break: break-all;
}

.recent-item-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: end;
}

.empty-block {
  min-height: 200px;
  display: grid;
  place-items: center;
  text-align: center;
  font-size: 13px;
  color: var(--color-muted);
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border-strong);
}

@media (max-width: 1080px) {
  .metrics-grid,
  .dashboard-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 768px) {
  .page-intro {
    flex-direction: column;
  }

  .panel-header,
  .recent-item {
    flex-direction: column;
    align-items: stretch;
  }

  .metrics-grid,
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}
</style>
