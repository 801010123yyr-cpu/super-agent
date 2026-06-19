<script setup>
import { cn } from '@/lib/utils'
const props = defineProps({ class: { type: null, default: '' } })
</script>

<template>
  <tr :class="cn('border-b border-border transition-colors hover:bg-primary/[0.04]', props.class)">
    <slot />
  </tr>
</template>
