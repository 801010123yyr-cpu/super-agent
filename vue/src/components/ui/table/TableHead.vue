<script setup>
import { cn } from '@/lib/utils'
const props = defineProps({ class: { type: null, default: '' } })
</script>

<template>
  <th :class="cn('h-11 px-4 text-left align-middle text-xs font-semibold text-muted-foreground whitespace-nowrap', props.class)">
    <slot />
  </th>
</template>
