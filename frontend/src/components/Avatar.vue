<script setup>
import { avatarColor, initials } from '../people'
defineProps({ name: { type: String, default: '' }, id: { type: String, default: '' }, size: { type: String, default: 'md' } })
</script>
<template><span class="person" :class="`person-${size}`" :style="{ '--person': avatarColor(id || name) }" :title="name" aria-hidden="true">{{ initials(name) }}</span></template>
