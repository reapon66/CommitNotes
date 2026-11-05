<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({ title: { type: String, default: '' }, size: { type: String, default: 'md' }, panelClass: { type: String, default: '' }, form: Boolean, closable: { type: Boolean, default: true } })
const emit = defineEmits(['close', 'submit'])
const panel = ref(null)
const focusable = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])'
let previous

function close() { if (props.closable) emit('close') }
function onKey(event) {
  if (event.key === 'Escape') { event.stopPropagation(); close() }
  if (event.key !== 'Tab') return
  const items = [...panel.value.querySelectorAll(focusable)].filter(el => el.offsetParent !== null)
  if (!items.length) return
  const first = items[0], last = items.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
onMounted(async () => {
  previous = document.activeElement
  document.body.dataset.modals = String(Number(document.body.dataset.modals || 0) + 1)
  await nextTick()
  ;(panel.value.querySelector('[autofocus]') || panel.value).focus({ preventScroll: true })
})
onBeforeUnmount(() => {
  const open = Number(document.body.dataset.modals || 1) - 1
  if (open > 0) document.body.dataset.modals = String(open); else delete document.body.dataset.modals
  if (previous?.isConnected) previous.focus({ preventScroll: true })
})
</script>

<template>
  <Teleport to="body">
    <div class="modal-backdrop" @mousedown.self="close" @keydown="onKey">
      <component :is="form ? 'form' : 'section'" ref="panel" class="modal" :class="[`modal-${size}`, panelClass]" role="dialog" aria-modal="true"
        :aria-label="title" tabindex="-1" @submit.prevent="emit('submit')">
        <header class="modal-head">
          <slot name="header"><h2>{{ title }}</h2></slot>
          <button v-if="closable" type="button" class="icon-btn" aria-label="Fechar" @click="close"><Icon name="close" /></button>
        </header>
        <div class="modal-body"><slot /></div>
        <footer v-if="$slots.footer" class="modal-actions"><slot name="footer" /></footer>
      </component>
    </div>
  </Teleport>
</template>
