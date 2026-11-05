<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, dateTime } from '../api'
import { acceptTypes, fileKind, fileUrl, formatSize, kindIcon, kindLabel, loadUploadLimits, uploadFile, uploadLimits, validateUpload } from '../files'
import { wikilink } from '../markdown'
import { useApp } from '../store'
import FilePreview from '../components/FilePreview.vue'
import Icon from '../components/Icon.vue'

const app = useApp(), route = useRoute(), router = useRouter()
const storedLayout = (() => { try { return localStorage.getItem('cn_files_layout') } catch (_) { return null } })()
const files = ref([]), notes = ref([]), tasks = ref([]), search = ref(''), kind = ref(''), layout = ref(storedLayout === 'list' ? 'list' : 'grid')
const linkType = ref('none'), linkId = ref(''), queue = ref([]), over = ref(false), preview = ref(null)
const KINDS = [['', 'Todos'], ['image', 'Imagens'], ['pdf', 'PDF'], ['docx', 'Word'], ['text', 'Texto']]
const visible = computed(() => kind.value ? files.value.filter(f => fileKind(f.mime, f.name) === kind.value) : files.value)
const sending = computed(() => queue.value.some(item => item.status === 'sending'))

async function load() {
  if (!app.projectId) return
  try {
    files.value = await api(`/projects/${app.projectId}/files?q=${encodeURIComponent(search.value)}`)
    if (app.canEdit && !notes.value.length) {
      notes.value = await api(`/projects/${app.projectId}/notes`)
      const boards = await api(`/projects/${app.projectId}/boards`)
      tasks.value = (await Promise.all(boards.map(b => api(`/boards/${b.id}/view`)))).flatMap(v => v.tasks)
    }
  } catch (e) { app.fail(e) }
}
function enqueue(list) {
  if (linkType.value !== 'none' && !linkId.value) return app.fail(new Error('Escolha a nota ou tarefa para vincular os arquivos.'))
  const items = [...list].map(file => ({ id: crypto.randomUUID?.() || `${Date.now()}${Math.random()}`, file, progress: 0, error: validateUpload(file), status: 'waiting' }))
  items.forEach(item => { if (item.error) item.status = 'error' })
  queue.value = [...queue.value.filter(item => item.status !== 'done'), ...items]
  send()
}
async function send() {
  const item = queue.value.find(i => i.status === 'waiting')
  if (!item || sending.value) return
  item.status = 'sending'
  try {
    await uploadFile(app.projectId, item.file, { noteId: linkType.value === 'note' ? linkId.value : null, taskId: linkType.value === 'task' ? linkId.value : null, onProgress: p => { item.progress = p } })
    item.status = 'done'; item.progress = 1; await load()
  } catch (e) { item.status = 'error'; item.error = e.message }
  send()
}
function onDrop(event) { over.value = false; if (app.canEdit && event.dataTransfer?.files?.length) enqueue(event.dataTransfer.files) }
function pick(event) { if (event.target.files?.length) enqueue(event.target.files); event.target.value = '' }
async function remove(f) {
  if (!confirm(`Excluir ${f.name}? Os links para ele nas notas deixarão de funcionar.`)) return
  try { await api(`/files/${f.id}`, { method: 'DELETE' }); await load(); app.flash('Arquivo excluído') } catch (e) { app.fail(e) }
}
async function copyLink(f) {
  try { await navigator.clipboard.writeText(wikilink('file', f.id, f.name)); app.flash('Referência copiada. Cole no texto de uma nota.') } catch (_) { app.fail(new Error('Não foi possível copiar')) }
}
function show(f) { preview.value = { files: visible.value, index: Math.max(0, visible.value.findIndex(x => x.id === f.id)) } }
async function openFromRoute(id) {
  if (!id) return
  const local = files.value.find(f => f.id === id)
  try { preview.value = { files: [local || await api(`/files/${id}`)], index: 0 } } catch (e) { app.fail(e) }
}
function closePreview() { preview.value = null; if (route.query.preview) router.replace({ query: {} }) }
let debounce
watch(search, () => { clearTimeout(debounce); debounce = setTimeout(load, 250) })
watch(() => app.projectId, () => { notes.value = []; tasks.value = []; load() })
watch(layout, value => { try { localStorage.setItem('cn_files_layout', value) } catch (_) {} })
watch(() => route.query.preview, id => openFromRoute(id))
onMounted(async () => { loadUploadLimits(); await load(); openFromRoute(route.query.preview) })
</script>

<template>
  <div class="page-head"><div><div class="eyebrow">BIBLIOTECA DO PROJETO</div><h1>Arquivos</h1><p class="muted">Busque pelo nome ou pelo texto de PDF, DOCX e TXT.</p></div></div>
  <div v-if="!app.projectId" class="empty-inline">Crie um projeto para enviar arquivos.</div>
  <template v-else>
    <section v-if="app.canEdit" class="panel upload-panel">
      <label class="dropzone" :class="{ over }" @dragover.prevent="over = true" @dragleave="over = false" @drop.prevent="onDrop">
        <input type="file" multiple :accept="acceptTypes()" @change="pick" />
        <span class="dropzone-icon"><Icon name="upload" :size="26" /></span>
        <strong>Arraste arquivos aqui ou <u>clique para escolher</u></strong>
        <small>PDF, DOCX, TXT, PNG, JPG ou WEBP · até {{ formatSize(uploadLimits.maxBytes) }} por arquivo</small>
      </label>
      <div class="upload-options">
        <label>Vincular a<select v-model="linkType" @change="linkId = ''"><option value="none">Nenhum vínculo</option><option value="note">Uma nota</option><option value="task">Uma tarefa</option></select></label>
        <label v-if="linkType !== 'none'">{{ linkType === 'note' ? 'Nota' : 'Tarefa' }}<select v-model="linkId"><option value="">Selecione…</option><option v-for="item in linkType === 'note' ? notes : tasks" :key="item.id" :value="item.id">{{ item.title }}</option></select></label>
      </div>
      <ul v-if="queue.length" class="upload-queue">
        <li v-for="item in queue" :key="item.id" :class="item.status">
          <Icon :name="item.status === 'done' ? 'done' : item.status === 'error' ? 'alert' : item.status === 'sending' ? 'loading' : 'file'" :size="16" :class="{ spin: item.status === 'sending' }" />
          <span class="queue-name">{{ item.file.name }}<small>{{ item.error || (item.status === 'done' ? 'Enviado' : item.status === 'sending' ? `${Math.round(item.progress * 100)}%` : `Aguardando · ${formatSize(item.file.size)}`) }}</small></span>
          <span v-if="item.status === 'sending'" class="progress-track"><span :style="{ width: `${Math.round(item.progress * 100)}%` }"></span></span>
        </li>
        <li v-if="!sending" class="queue-clear"><button type="button" class="text-link" @click="queue = []">Limpar lista</button></li>
      </ul>
    </section>

    <div class="files-toolbar">
      <label class="search-field"><Icon name="search" :size="16" /><span class="sr-only">Buscar arquivos</span><input v-model="search" type="search" placeholder="Buscar arquivos…" /></label>
      <div class="chip-row" role="group" aria-label="Filtrar por tipo">
        <button v-for="[key, label] in KINDS" :key="key" type="button" class="legend-chip" :class="{ active: kind === key }" :aria-pressed="kind === key" @click="kind = key">{{ label }}</button>
      </div>
      <span class="muted small">{{ visible.length }} arquivo(s)</span>
      <div class="segmented" role="group" aria-label="Modo de exibição">
        <button type="button" :class="{ active: layout === 'grid' }" aria-label="Grade" title="Grade" @click="layout = 'grid'"><Icon name="grid" :size="16" /></button>
        <button type="button" :class="{ active: layout === 'list' }" aria-label="Lista" title="Lista" @click="layout = 'list'"><Icon name="list" :size="16" /></button>
      </div>
    </div>

    <div v-if="!visible.length" class="panel empty-inline">{{ search || kind ? 'Nenhum arquivo encontrado.' : 'Nenhum arquivo enviado ainda.' }}</div>
    <div v-else-if="layout === 'grid'" class="file-grid">
      <article v-for="f in visible" :key="f.id" class="file-card">
        <button type="button" class="file-thumb" :class="`kind-${fileKind(f.mime, f.name)}`" :aria-label="`Visualizar ${f.name}`" @click="show(f)">
          <img v-if="fileKind(f.mime, f.name) === 'image'" :src="fileUrl(f.id)" :alt="''" loading="lazy" decoding="async" />
          <span v-else class="thumb-icon"><Icon :name="kindIcon[fileKind(f.mime, f.name)]" :size="34" /><small>{{ kindLabel[fileKind(f.mime, f.name)] }}</small></span>
        </button>
        <div class="file-card-body">
          <strong :title="f.name">{{ f.name }}</strong>
          <small>{{ formatSize(f.size_bytes) }} · {{ dateTime(f.created_at) }}</small>
          <small>{{ f.uploaded_by_name }}</small>
        </div>
        <div class="file-card-actions">
          <button type="button" class="icon-btn" title="Copiar referência para nota" aria-label="Copiar referência para nota" @click="copyLink(f)"><Icon name="link" :size="16" /></button>
          <a class="icon-btn" :href="fileUrl(f.id, true)" title="Baixar" :aria-label="`Baixar ${f.name}`"><Icon name="download" :size="16" /></a>
          <button v-if="app.canEdit" type="button" class="icon-btn danger-text" title="Excluir" :aria-label="`Excluir ${f.name}`" @click="remove(f)"><Icon name="trash" :size="16" /></button>
        </div>
      </article>
    </div>
    <div v-else class="panel file-list">
      <div v-for="f in visible" :key="f.id" class="file-row">
        <span class="file-icon" :class="`kind-${fileKind(f.mime, f.name)}`"><Icon :name="kindIcon[fileKind(f.mime, f.name)]" /></span>
        <button type="button" class="file-detail" @click="show(f)"><strong>{{ f.name }}</strong><small>{{ kindLabel[fileKind(f.mime, f.name)] }} · {{ formatSize(f.size_bytes) }} · {{ f.uploaded_by_name }} · {{ dateTime(f.created_at) }}</small></button>
        <div class="head-actions">
          <button type="button" class="btn btn-ghost btn-sm" @click="show(f)"><Icon name="eye" :size="15" />Prévia</button>
          <button type="button" class="icon-btn" title="Copiar referência para nota" aria-label="Copiar referência para nota" @click="copyLink(f)"><Icon name="link" :size="16" /></button>
          <a class="icon-btn" :href="fileUrl(f.id, true)" title="Baixar" :aria-label="`Baixar ${f.name}`"><Icon name="download" :size="16" /></a>
          <button v-if="app.canEdit" type="button" class="icon-btn danger-text" :aria-label="`Excluir ${f.name}`" @click="remove(f)"><Icon name="trash" :size="16" /></button>
        </div>
      </div>
    </div>
  </template>
  <FilePreview v-if="preview" :files="preview.files" :index="preview.index" @close="closePreview" />
</template>
