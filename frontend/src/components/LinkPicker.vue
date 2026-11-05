<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api, body, dateTime, isoInput } from '../api'
import { acceptTypes, fileKind, formatSize, kindIcon, kindLabel, loadUploadLimits, uploadFile } from '../files'
import { useApp } from '../store'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const props = defineProps({ projectId: { type: String, required: true }, noteId: { type: String, default: null }, initialTab: { type: String, default: 'note' } })
const emit = defineEmits(['select', 'close'])
const app = useApp()
const tab = ref(props.initialTab), query = ref(''), active = ref(0), loading = ref(true), list = ref(null), search = ref(null)
const notes = ref([]), files = ref([]), reminders = ref([]), uploading = ref(0), creating = ref(false), reminderTitle = ref(''), reminderDue = ref('')
const tabs = [{ key: 'note', label: 'Notas', icon: 'notes' }, { key: 'file', label: 'Arquivos', icon: 'files' }, { key: 'reminder', label: 'Lembretes', icon: 'alarm' }]
const normalize = value => (value || '').normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()

const items = computed(() => {
  const term = normalize(query.value)
  const source = tab.value === 'note'
    ? notes.value.filter(n => n.id !== props.noteId).map(n => ({ id: n.id, label: n.title, icon: n.favorite ? 'star' : 'notes', meta: `Atualizada ${dateTime(n.updated_at)}` }))
    : tab.value === 'file'
      ? files.value.map(f => ({ id: f.id, label: f.name, icon: kindIcon[fileKind(f.mime, f.name)], meta: `${kindLabel[fileKind(f.mime, f.name)]} · ${formatSize(f.size_bytes)} · ${dateTime(f.created_at)}` }))
      : reminders.value.map(r => ({ id: r.id, label: r.title, icon: r.completed_at ? 'done' : 'alarm', meta: `${dateTime(r.due_at)}${r.completed_at ? ' · concluído' : ''}${r.project_name ? ` · ${r.project_name}` : ' · pessoal'}` }))
  return term ? source.filter(item => normalize(item.label).includes(term)) : source
})
const counts = computed(() => ({ note: notes.value.filter(n => n.id !== props.noteId).length, file: files.value.length, reminder: reminders.value.length }))

async function load() {
  loading.value = true
  try {
    const [n, f, r] = await Promise.all([api(`/projects/${props.projectId}/notes?state=active`), api(`/projects/${props.projectId}/files`), api('/reminders')])
    notes.value = n; files.value = f
    reminders.value = r.filter(x => !x.project_id || x.project_id === props.projectId).sort((a, b) => Number(!!a.completed_at) - Number(!!b.completed_at) || new Date(a.due_at) - new Date(b.due_at))
  } catch (e) { app.fail(e) } finally { loading.value = false }
}
function choose(item) { if (item) emit('select', { kind: tab.value, id: item.id, label: item.label }) }
function onKey(event) {
  if (event.key === 'ArrowDown') { event.preventDefault(); active.value = Math.min(items.value.length - 1, active.value + 1); reveal() }
  else if (event.key === 'ArrowUp') { event.preventDefault(); active.value = Math.max(0, active.value - 1); reveal() }
  else if (event.key === 'Enter' && !event.isComposing) { event.preventDefault(); choose(items.value[active.value]) }
  else if (event.key === 'Tab' && event.ctrlKey) { event.preventDefault(); const i = tabs.findIndex(t => t.key === tab.value); tab.value = tabs[(i + (event.shiftKey ? 2 : 1)) % 3].key }
}
async function reveal() { await nextTick(); list.value?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' }) }
async function upload(event) {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file) return
  uploading.value = 0.01
  try {
    const saved = await uploadFile(props.projectId, file, { noteId: props.noteId, onProgress: p => { uploading.value = Math.max(0.01, p) } })
    emit('select', { kind: 'file', id: saved.id, label: saved.name, uploaded: saved })
  } catch (e) { app.fail(e) } finally { uploading.value = 0 }
}
async function createReminder() {
  if (!reminderTitle.value.trim() || !reminderDue.value) return
  creating.value = true
  try {
    const saved = await api('/reminders', { method: 'POST', body: body({ title: reminderTitle.value, dueAt: isoInput(reminderDue.value), projectId: props.projectId, noteId: props.noteId }) })
    emit('select', { kind: 'reminder', id: saved.id, label: saved.title })
  } catch (e) { app.fail(e) } finally { creating.value = false }
}
watch([tab, query], () => { active.value = 0 })
watch(tab, () => nextTick(() => search.value?.focus()))
onMounted(() => { load(); loadUploadLimits() })
</script>

<template>
  <Modal title="Inserir link" size="md" panel-class="link-picker" @close="emit('close')">
    <div class="segmented segmented-full" role="tablist" aria-label="Tipo de link">
      <button v-for="t in tabs" :key="t.key" type="button" role="tab" :aria-selected="tab === t.key" :class="{ active: tab === t.key }" @click="tab = t.key">
        <Icon :name="t.icon" :size="16" />{{ t.label }}<span class="count-pill">{{ counts[t.key] }}</span>
      </button>
    </div>
    <label class="search-field">
      <Icon name="search" :size="16" />
      <span class="sr-only">Buscar</span>
      <input ref="search" v-model="query" type="search" autofocus autocomplete="off" :placeholder="tab === 'note' ? 'Buscar nota…' : tab === 'file' ? 'Buscar arquivo…' : 'Buscar lembrete…'"
        role="combobox" aria-controls="link-options" :aria-activedescendant="items[active] ? `link-${items[active].id}` : undefined" @keydown="onKey" />
    </label>
    <ul id="link-options" ref="list" class="picker-list" role="listbox" aria-label="Resultados">
      <li v-if="loading" class="empty-inline"><Icon name="loading" class="spin" /> Carregando…</li>
      <li v-else-if="!items.length" class="empty-inline">{{ query ? 'Nada encontrado para essa busca.' : tab === 'note' ? 'Ainda não há outras notas.' : tab === 'file' ? 'Nenhum arquivo enviado.' : 'Nenhum lembrete neste projeto.' }}</li>
      <li v-for="(item, i) in items" :id="`link-${item.id}`" :key="item.id" role="option" :aria-selected="i === active" class="picker-option" :class="{ active: i === active }"
        @mousemove="active = i" @click="choose(item)">
        <span class="row-icon"><Icon :name="item.icon" :size="17" /></span>
        <span class="picker-text"><strong>{{ item.label }}</strong><small>{{ item.meta }}</small></span>
        <Icon name="link" :size="15" class="picker-go" />
      </li>
    </ul>
    <div v-if="tab === 'file' && app.canEdit" class="picker-extra">
      <label class="btn btn-ghost btn-sm file-label"><Icon name="upload" :size="16" />{{ uploading ? `Enviando ${Math.round(uploading * 100)}%` : 'Enviar novo arquivo' }}<input type="file" :accept="acceptTypes()" :disabled="!!uploading" @change="upload" /></label>
      <div v-if="uploading" class="progress-track"><div :style="{ width: `${Math.round(uploading * 100)}%` }"></div></div>
    </div>
    <form v-if="tab === 'reminder'" class="picker-extra picker-reminder" @submit.prevent="createReminder">
      <input v-model="reminderTitle" maxlength="200" required placeholder="Novo lembrete" aria-label="Título do novo lembrete" />
      <input v-model="reminderDue" type="datetime-local" required aria-label="Data e hora do lembrete" />
      <button class="btn btn-primary btn-sm" :disabled="creating"><Icon name="plus" :size="16" />Criar e inserir</button>
    </form>
    <p class="hint picker-hint">Use as setas e Enter para escolher. No editor, digite <kbd>[[</kbd> ou pressione <kbd>Ctrl</kbd>+<kbd>K</kbd> para abrir esta janela.</p>
  </Modal>
</template>
