<template>
  <div v-if="state.open" class="fixed inset-0 z-[100] flex items-center justify-center bg-[rgba(15,23,42,0.4)]" @click.self="onCancel">
    <div class="w-[min(420px,90vw)] rounded-xl border border-border bg-card p-6 shadow-xl">
      <h3 class="mb-3 text-base font-semibold text-foreground">{{ state.title }}</h3>
      <p class="mb-6 whitespace-pre-line text-[13px] leading-relaxed text-[var(--color-muted-strong)]">{{ state.message }}</p>
      <div class="flex justify-end gap-3">
        <button class="rounded-full border border-border bg-card px-5 py-2 text-sm font-semibold text-foreground hover:bg-secondary" type="button" @click="onCancel">取消</button>
        <button class="rounded-full bg-primary px-5 py-2 text-sm font-semibold text-white hover:opacity-90" type="button" @click="onConfirm">确定</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useConfirm } from '@/composables/useConfirm'
const { state, onConfirm, onCancel } = useConfirm()
</script>
