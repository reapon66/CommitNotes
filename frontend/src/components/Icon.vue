<script setup>
import { icons } from '../icons'
defineProps({ name: { type: String, required: true }, size: { type: [Number, String], default: 18 }, stroke: { type: [Number, String], default: 2 } })
</script>
<template><component :is="icons[name]" :size="size" :stroke-width="stroke" class="icon" aria-hidden="true" focusable="false" /></template>
