<script setup>
import { cn } from '@/lib/utils'

const props = defineProps({
  modelValue: { type: [String, Number], default: '' },
  class: { type: null, default: '' }
})
const emits = defineEmits(['update:modelValue'])
</script>

<template>
  <input
    :value="modelValue"
    :class="cn(
      'flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50',
      props.class
    )"
    @input="emits('update:modelValue', $event.target.value)"
  >
</template>
