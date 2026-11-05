<script setup>
import { ref } from 'vue'
import { api, body } from '../api'
import { useApp } from '../store'
import ColorSwatches from './ColorSwatches.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const props = defineProps({ projectId: { type: String, required: true }, tags: { type: Array, default: () => [] }, folders: { type: Array, default: () => [] }, initialTab: { type: String, default: 'tags' } })
const emit = defineEmits(['close', 'changed'])
const app = useApp()
const tab = ref(props.initialTab), newTag = ref(''), newTagColor = ref('#6366f1'), newFolder = ref(''), editingColor = ref(null), busy = ref(false)

async function call(request, message) {
  busy.value = true
  try { await request(); emit('changed'); if (message) app.flash(message); return true } catch (e) { app.fail(e); return false } finally { busy.value = false }
}
async function createTag() { if (newTag.value.trim() && await call(() => api(`/projects/${props.projectId}/tags`, { method: 'POST', body: body({ name: newTag.value, color: newTagColor.value }) }), 'Etiqueta criada')) newTag.value = '' }
const renameTag = (t, name) => name.trim() && name.trim() !== t.name && call(() => api(`/tags/${t.id}`, { method: 'PATCH', body: body({ name }) }), 'Etiqueta renomeada')
const recolorTag = (t, color) => { editingColor.value = null; return call(() => api(`/tags/${t.id}`, { method: 'PATCH', body: body({ color }) })) }
const removeTag = t => confirm(`Excluir a etiqueta ${t.name}? Ela será removida de todas as notas.`) && call(() => api(`/tags/${t.id}`, { method: 'DELETE' }), 'Etiqueta excluída')
async function createFolder() { if (newFolder.value.trim() && await call(() => api(`/projects/${props.projectId}/folders`, { method: 'POST', body: body({ name: newFolder.value }) }), 'Pasta criada')) newFolder.value = '' }
const renameFolder = (f, name) => name.trim() && name.trim() !== f.name && call(() => api(`/folders/${f.id}`, { method: 'PATCH', body: body({ name }) }), 'Pasta renomeada')
const removeFolder = f => confirm(`Excluir a pasta ${f.name}? As notas ficarão sem pasta.`) && call(() => api(`/folders/${f.id}`, { method: 'DELETE' }), 'Pasta excluída')
</script>

<template>
  <Modal title="Organizar notas" size="md" panel-class="tag-manager" @close="emit('close')">
    <div class="segmented segmented-full" role="tablist">
      <button type="button" role="tab" :aria-selected="tab === 'tags'" :class="{ active: tab === 'tags' }" @click="tab = 'tags'"><Icon name="tags" :size="16" />Etiquetas<span class="count-pill">{{ tags.length }}</span></button>
      <button type="button" role="tab" :aria-selected="tab === 'folders'" :class="{ active: tab === 'folders' }" @click="tab = 'folders'"><Icon name="folder" :size="16" />Pastas<span class="count-pill">{{ folders.length }}</span></button>
    </div>
    <template v-if="tab === 'tags'">
      <p class="hint">Etiquetas categorizam as notas; a cor de cada uma aparece na lista, no painel do projeto e na visão geral.</p>
      <form v-if="app.canEdit" class="manager-new" @submit.prevent="createTag">
        <div class="inline-add"><input v-model="newTag" maxlength="60" placeholder="Nova etiqueta (ex.: Decisão, Pesquisa)" aria-label="Nome da nova etiqueta" /><button class="btn btn-primary btn-sm" :disabled="busy || !newTag.trim()"><Icon name="plus" :size="16" />Criar</button></div>
        <ColorSwatches v-model="newTagColor" label="Cor da nova etiqueta" />
      </form>
      <ul class="manager-list">
        <li v-for="t in tags" :key="t.id">
          <div class="manager-row">
            <button type="button" class="swatch swatch-current" :style="{ '--chip': t.color }" :aria-label="`Alterar cor de ${t.name}`" :disabled="!app.canEdit" @click="editingColor = editingColor === t.id ? null : t.id"><Icon name="palette" :size="14" /></button>
            <input :value="t.name" maxlength="60" :disabled="!app.canEdit" :aria-label="`Nome da etiqueta ${t.name}`" @change="renameTag(t, $event.target.value)" @keydown.enter.prevent="$event.target.blur()" />
            <button v-if="app.canEdit" type="button" class="icon-btn danger-text" :aria-label="`Excluir etiqueta ${t.name}`" @click="removeTag(t)"><Icon name="trash" :size="16" /></button>
          </div>
          <ColorSwatches v-if="editingColor === t.id" :model-value="t.color" :label="`Cor de ${t.name}`" @update:model-value="recolorTag(t, $event)" />
        </li>
        <li v-if="!tags.length" class="empty-inline">Nenhuma etiqueta ainda.</li>
      </ul>
    </template>
    <template v-else>
      <p class="hint">Cada nota fica em uma pasta. Na visão geral você pode colorir as notas por pasta.</p>
      <form v-if="app.canEdit" class="inline-add manager-new" @submit.prevent="createFolder">
        <input v-model="newFolder" maxlength="100" placeholder="Nova pasta" aria-label="Nome da nova pasta" /><button class="btn btn-primary btn-sm" :disabled="busy || !newFolder.trim()"><Icon name="plus" :size="16" />Criar</button>
      </form>
      <ul class="manager-list">
        <li v-for="f in folders" :key="f.id" class="manager-row">
          <span class="row-icon small"><Icon name="folder" :size="15" /></span>
          <input :value="f.name" maxlength="100" :disabled="!app.canEdit" :aria-label="`Nome da pasta ${f.name}`" @change="renameFolder(f, $event.target.value)" @keydown.enter.prevent="$event.target.blur()" />
          <button v-if="app.canEdit" type="button" class="icon-btn danger-text" :aria-label="`Excluir pasta ${f.name}`" @click="removeFolder(f)"><Icon name="trash" :size="16" /></button>
        </li>
        <li v-if="!folders.length" class="empty-inline">Nenhuma pasta ainda.</li>
      </ul>
    </template>
  </Modal>
</template>
