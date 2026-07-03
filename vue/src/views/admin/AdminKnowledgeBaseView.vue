<template>
  <section class="flex flex-col gap-[18px]">
    <div class="flex items-start justify-between gap-4 rounded-lg border border-border bg-card p-5 shadow-sm max-[860px]:flex-col">
      <div>
        <h3 class="m-0 text-base font-semibold text-foreground">知识库管理</h3>
        <p class="mt-2 text-sm text-muted-foreground">知识库是聊天检索的硬边界，知识范围和主题只在知识库内部继续做软分类。</p>
      </div>
      <Button size="sm" type="button" :disabled="loading || actionLoading" @click="resetForm">新建知识库</Button>
    </div>

    <div
      v-if="notice.message"
      class="rounded-md border px-[18px] py-3.5 text-sm font-semibold"
      :class="notice.type === 'danger' ? 'border-destructive/20 bg-destructive/10 text-destructive' : 'border-primary/10 bg-primary/[0.08] text-primary'"
    >
      {{ notice.message }}
    </div>

    <div class="grid grid-cols-[minmax(0,1fr)_420px] gap-4 max-[1080px]:grid-cols-1">
      <Card class="p-5">
        <div class="flex items-center justify-between gap-3">
          <div>
            <h4 class="m-0 text-sm font-semibold text-foreground">知识库列表</h4>
            <p class="mt-1 text-sm text-muted-foreground">当前 {{ knowledgeBases.length }} 个知识库。</p>
          </div>
          <Button variant="ghost" size="sm" type="button" :disabled="loading" @click="loadKnowledgeBases">{{ loading ? '刷新中...' : '刷新' }}</Button>
        </div>

        <div class="mt-4 overflow-x-auto rounded-md border border-border">
          <table class="w-full min-w-[780px] border-collapse text-sm">
            <thead>
              <tr class="bg-secondary">
                <th v-for="head in tableHeads" :key="head" class="border-b border-border px-4 py-3 text-left text-xs font-semibold text-muted-foreground" :class="head === '操作' ? 'text-right' : ''">{{ head }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!loading && !knowledgeBases.length">
                <td colspan="6" class="px-4 py-10 text-center text-sm text-muted-foreground">还没有知识库，先创建一个再上传文档。</td>
              </tr>
              <tr v-for="item in knowledgeBases" :key="item.id" class="border-b border-border last:border-0">
                <td class="px-4 py-3">
                  <strong class="block text-foreground">{{ item.baseName }}</strong>
                  <span class="text-xs text-muted-foreground">{{ item.baseCode }}</span>
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ item.description || '-' }}</td>
                <td class="px-4 py-3 text-foreground">{{ item.documentCount || 0 }} / {{ item.retrievableDocumentCount || 0 }}</td>
                <td class="px-4 py-3">
                  <span class="inline-flex rounded-full px-2.5 py-1 text-xs font-semibold" :class="String(item.isDefault) === '1' ? 'bg-primary/[0.08] text-primary' : 'bg-secondary text-muted-foreground'">{{ String(item.isDefault) === '1' ? '默认' : '普通' }}</span>
                </td>
                <td class="px-4 py-3 text-muted-foreground">{{ item.sortOrder || 0 }}</td>
                <td class="px-4 py-3 text-right">
                  <div class="inline-flex gap-2">
                    <Button variant="ghost" size="sm" type="button" @click="editKnowledgeBase(item)">编辑</Button>
                    <Button variant="ghost" size="sm" type="button" :disabled="actionLoading" @click="deleteKnowledgeBase(item)">删除</Button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </Card>

      <Card class="p-5">
        <h4 class="m-0 text-sm font-semibold text-foreground">{{ form.id ? '编辑知识库' : '新建知识库' }}</h4>
        <div class="mt-4 grid gap-3">
          <div class="grid gap-2">
            <Label class="text-[13px] font-bold text-[var(--color-muted-strong)]">知识库编码</Label>
            <Input v-model="form.baseCode" class="h-9 text-sm" placeholder="例如 hr_policy" />
          </div>
          <div class="grid gap-2">
            <Label class="text-[13px] font-bold text-[var(--color-muted-strong)]">知识库名称</Label>
            <Input v-model="form.baseName" class="h-9 text-sm" placeholder="例如 人事制度库" />
          </div>
          <div class="grid gap-2">
            <Label class="text-[13px] font-bold text-[var(--color-muted-strong)]">描述</Label>
            <textarea v-model="form.description" class="form-textarea" placeholder="知识库用途、边界或使用说明"></textarea>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div class="grid gap-2">
              <Label class="text-[13px] font-bold text-[var(--color-muted-strong)]">排序</Label>
              <Input v-model="form.sortOrder" class="h-9 text-sm" placeholder="0" />
            </div>
            <label class="mt-7 inline-flex items-center gap-2 text-sm text-foreground">
              <input v-model="form.isDefault" type="checkbox" true-value="1" false-value="0" />
              <span>默认知识库</span>
            </label>
          </div>
          <div class="grid gap-2">
            <Label class="text-[13px] font-bold text-[var(--color-muted-strong)]">向量模型</Label>
            <Input v-model="form.embeddingModel" class="h-9 text-sm" placeholder="可空，默认沿用全局配置" />
          </div>
          <div v-for="field in jsonFields" :key="field.key" class="grid gap-2">
            <Label class="text-[13px] font-bold text-[var(--color-muted-strong)]">{{ field.label }}</Label>
            <textarea v-model="form[field.key]" class="form-textarea font-mono text-xs" :placeholder="field.placeholder"></textarea>
          </div>
          <div class="flex justify-end gap-2 border-t border-border pt-3">
            <Button variant="ghost" size="sm" type="button" @click="resetForm">清空</Button>
            <Button size="sm" type="button" :disabled="actionLoading" @click="saveKnowledgeBase">{{ actionLoading ? '保存中...' : '保存' }}</Button>
          </div>
        </div>
      </Card>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { manageApi } from '../../api/api'
import { Card } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const tableHeads = ['知识库', '描述', '文档 / 可检索', '默认', '排序', '操作']
const jsonFields = [
  { key: 'retrievalConfigJson', label: '检索配置 JSON', placeholder: '{"vectorTopK":8,"finalTopK":6,"hybrid":{"vectorWeight":1.0}}' },
  { key: 'graphRagConfigJson', label: 'GraphRAG 配置 JSON', placeholder: '{"graphRagTopK":6,"graphRagMaxHops":2,"graphRagChannelEnabled":true}' },
  { key: 'raptorConfigJson', label: 'RAPTOR 配置 JSON', placeholder: '{"raptorTopK":4,"raptorSourceChunkTopK":3,"raptorChannelEnabled":true}' },
  { key: 'metadataFilterJson', label: '元数据过滤 JSON', placeholder: '{"tags":["policy"]}' }
]

const knowledgeBases = ref([])
const loading = ref(false)
const actionLoading = ref(false)
const notice = reactive({ type: 'info', message: '' })
const form = reactive(emptyForm())

function emptyForm() {
  return {
    id: '',
    baseCode: '',
    baseName: '',
    description: '',
    embeddingModel: '',
    retrievalConfigJson: '',
    graphRagConfigJson: '',
    raptorConfigJson: '',
    metadataFilterJson: '',
    isDefault: '0',
    sortOrder: '0',
    operatorId: '10001'
  }
}

function showNotice(message, type = 'info') {
  notice.message = message
  notice.type = type
}

function resetForm() {
  Object.assign(form, emptyForm())
}

function editKnowledgeBase(item) {
  Object.assign(form, {
    ...emptyForm(),
    ...item,
    id: String(item.id || ''),
    isDefault: String(item.isDefault || '0'),
    sortOrder: String(item.sortOrder || '0')
  })
}

async function loadKnowledgeBases() {
  loading.value = true
  try {
    const data = await manageApi.listKnowledgeBases()
    knowledgeBases.value = Array.isArray(data) ? data : []
  } catch (error) {
    showNotice(error.message || '加载知识库列表失败', 'danger')
  } finally {
    loading.value = false
  }
}

async function saveKnowledgeBase() {
  if (!form.baseCode.trim() || !form.baseName.trim()) {
    showNotice('知识库编码和名称不能为空。', 'danger')
    return
  }
  actionLoading.value = true
  try {
    const result = await manageApi.saveKnowledgeBase(form)
    editKnowledgeBase(result)
    showNotice('知识库已保存。')
    await loadKnowledgeBases()
  } catch (error) {
    showNotice(error.message || '保存知识库失败', 'danger')
  } finally {
    actionLoading.value = false
  }
}

async function deleteKnowledgeBase(item) {
  if (!item?.id || !window.confirm(`确认删除知识库「${item.baseName}」吗？`)) return
  actionLoading.value = true
  try {
    await manageApi.deleteKnowledgeBase({ id: item.id, operatorId: '10001' })
    if (String(form.id) === String(item.id)) resetForm()
    showNotice('知识库已删除。')
    await loadKnowledgeBases()
  } catch (error) {
    showNotice(error.message || '删除知识库失败', 'danger')
  } finally {
    actionLoading.value = false
  }
}

onMounted(loadKnowledgeBases)
</script>

<style scoped>
.form-textarea {
  min-height: 78px;
  width: 100%;
  resize: vertical;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: #fff;
  padding: 10px 12px;
  color: var(--color-text-strong);
}

.form-textarea:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-soft);
}
</style>
