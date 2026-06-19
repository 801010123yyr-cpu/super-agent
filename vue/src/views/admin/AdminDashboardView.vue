<template>
  <section class="flex flex-col gap-5">
    <header class="flex items-start justify-between gap-6 max-md:flex-col">
      <div class="max-w-3xl">
        <h3 class="m-0 text-[17px] font-semibold leading-normal text-foreground">把文档接入、切块策略和索引构建串成一条可观察的业务流水线</h3>
        <p class="mt-2 text-[13.5px] leading-relaxed text-muted-foreground">
          后台聚焦文档进入系统后的关键节点：上传、推荐策略、策略确认、索引构建和对话观测。
        </p>
      </div>
      <Button size="sm" class="whitespace-nowrap" type="button" @click="goDocuments">前往文档接入</Button>
    </header>

    <div class="flex flex-wrap rounded-lg border border-border bg-card divide-x divide-border">
      <div v-for="metric in metricCards" :key="metric.label" class="flex-1 min-w-[140px] px-5 py-4">
        <span class="text-[12px] text-muted-foreground">{{ metric.label }}</span>
        <strong class="mt-1.5 block text-[22px] font-semibold tracking-tight text-foreground">{{ formatCount(metric.value) }}</strong>
        <p class="mt-1 text-[11px] leading-relaxed text-muted-foreground">{{ metric.hint }}</p>
      </div>
    </div>

    <div class="grid grid-cols-[1.05fr_0.95fr] gap-3.5 max-[1080px]:grid-cols-2 max-md:grid-cols-1">
      <Card class="p-[20px_22px]">
        <div class="mb-[18px] flex items-center justify-between gap-3">
          <h4 class="m-0 text-[14.5px] font-semibold text-foreground">建议演示路径</h4>
        </div>

        <ol class="m-0 flex list-none flex-col gap-3.5 p-0">
          <li v-for="step in flowSteps" :key="step.index" class="flex gap-3">
            <span class="grid h-[22px] w-[22px] flex-none place-items-center rounded-full bg-primary/[0.09] text-xs font-semibold text-primary">{{ step.index }}</span>
            <div>
              <strong class="mb-1 block text-[13.5px] text-foreground">{{ step.title }}</strong>
              <span class="text-[12.5px] leading-relaxed text-muted-foreground">{{ step.desc }}</span>
            </div>
          </li>
        </ol>
      </Card>

      <Card class="p-[20px_22px]">
        <div class="mb-[18px] flex items-center justify-between gap-3 max-md:flex-col max-md:items-stretch">
          <h4 class="m-0 text-[14.5px] font-semibold text-foreground">最近接入文档</h4>
          <Button variant="outline" size="sm" type="button" @click="loadDashboard">刷新</Button>
        </div>

        <div v-if="loading" class="grid min-h-[200px] place-items-center rounded-md border border-dashed border-border-strong text-center text-[13px] text-muted-foreground">正在加载后台概览...</div>
        <div v-else-if="!documents.length" class="grid min-h-[200px] place-items-center rounded-md border border-dashed border-border-strong text-center text-[13px] text-muted-foreground">当前还没有文档，先去“文档接入”页面上传一份资料。</div>

        <div v-else class="flex flex-col gap-2">
          <article
            v-for="item in documents.slice(0, 6)"
            :key="item.documentId"
            class="flex items-start justify-between gap-4 rounded-md border border-border bg-secondary px-3.5 py-3 transition-colors hover:border-border-strong hover:bg-card max-md:flex-col max-md:items-stretch"
          >
            <div>
              <strong class="block text-[13.5px] text-foreground">{{ item.documentName }}</strong>
              <p class="mt-1.5 break-all text-xs text-muted-foreground">{{ item.originalFileName }}</p>
            </div>
            <div class="flex flex-wrap justify-end gap-2">
              <AdminStatusBadge :label="item.parseStatusName" :code="item.parseStatus" type="parse" />
              <AdminStatusBadge :label="item.indexStatusName" :code="item.indexStatus" type="index" />
            </div>
          </article>
        </div>
      </Card>
    </div>
  </section>
</template>

<!-- SCRIPT_PLACEHOLDER -->
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { manageApi } from '../../api/api'
import AdminStatusBadge from '../../components/admin/AdminStatusBadge.vue'
import { formatCount, hasCode } from '../../utils/manageFormat'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'

const router = useRouter()
const loading = ref(false)
const documents = ref([])
const summary = reactive({
  total: 0,
  parseSuccess: 0,
  strategyConfirmed: 0,
  indexSuccess: 0
})

const metricCards = computed(() => [
  { label: '文档总数', value: summary.total, hint: '已进入管理台的文档记录' },
  { label: '解析成功', value: summary.parseSuccess, hint: '可进入策略确认阶段的文档' },
  { label: '策略已确认', value: summary.strategyConfirmed, hint: '已经形成最终切块链路' },
  { label: '索引完成', value: summary.indexSuccess, hint: '可直接参与 RAG 检索问答' }
])

const flowSteps = [
  { index: 1, title: '上传文档', desc: '通过假登录后的管理台上传 PDF / Word / Markdown 文档。' },
  { index: 2, title: '查看系统推荐策略', desc: '根据文档结构与内容长度，观察结构切块、递归分块、语义分块和智能切块的组合。' },
  { index: 3, title: '确认并构建索引', desc: '在推荐结果基础上补充或移除策略，再触发异步构建索引。' },
  { index: 4, title: '做对话观测', desc: '查看真实会话在当前文档问答与开放式提问两种模式下的执行轨迹。' }
]

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

