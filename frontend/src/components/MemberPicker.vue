<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Avatar from './Avatar.vue'
import Icon from './Icon.vue'

const props = defineProps({ members: { type: Array, default: () => [] }, modelValue: { type: Array, default: () => [] }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const open = ref(false), query = ref(''), root = ref(null), input = ref(null)
const selected = computed(() => props.modelValue.map(id => props.members.find(m => m.id === id)).filter(Boolean))
const filtered = computed(() => {
  const term = query.value.trim().toLowerCase()
  return term ? props.members.filter(m => `${m.name} ${m.email}`.toLowerCase().includes(term)) : props.members
})
function toggle(id) { emit('update:modelValue', props.modelValue.includes(id) ? props.modelValue.filter(x => x !== id) : [...props.modelValue, id]) }
function outside(event) { if (!root.value?.contains(event.target)) open.value = false }
watch(open, async value => {
  if (value) { query.value = ''; document.addEventListener('pointerdown', outside); await nextTick(); input.value?.focus() }
  else document.removeEventListener('pointerdown', outside)
})
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>

<template>
  <div ref="root" class="member-picker">
    <div class="person-chips">
      <span v-for="m in selected" :key="m.id" class="person-chip">
        <Avatar :name="m.name" :id="m.id" size="sm" />{{ m.name }}
        <button v-if="!disabled" type="button" class="chip-remove" :aria-label="`Remover ${m.name}`" @click="toggle(m.id)"><Icon name="close" :size="13" /></button>
      </span>
      <span v-if="!selected.length" class="muted small">Ninguém atribuído</span>
      <button v-if="!disabled" type="button" class="btn btn-ghost btn-sm" :aria-expanded="open" aria-haspopup="listbox" @click="open = !open"><Icon name="user-plus" :size="16" />Atribuir</button>
    </div>
    <div v-if="open" class="pop-panel" @keydown.esc.stop="open = false">
      <input ref="input" v-model="query" type="search" placeholder="Buscar pessoa…" aria-label="Buscar pessoa" />
      <div class="pop-list" role="listbox" aria-multiselectable="true" aria-label="Pessoas do projeto">
        <button v-for="m in filtered" :key="m.id" type="button" role="option" class="pop-option" :aria-selected="modelValue.includes(m.id)" @click="toggle(m.id)">
          <Avatar :name="m.name" :id="m.id" size="sm" />
          <span><strong>{{ m.name }}</strong><small>{{ m.email }}</small></span>
          <Icon v-if="modelValue.includes(m.id)" name="check" :size="16" class="pop-check" />
        </button>
        <p v-if="!filtered.length" class="empty-inline">Ninguém encontrado.</p>
      </div>
    </div>
  </div>
</template>
