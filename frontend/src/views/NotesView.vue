<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, body, dateTime, downloadBlob } from '../api'
import { acceptTypes, fileKind, fileUrl, formatSize, kindIcon, loadUploadLimits, uploadFile } from '../files'
import { insertText, prefixLines, renderMarkdown, wikilink, wrapText } from '../markdown'
import { useApp } from '../store'
import FilePreview from '../components/FilePreview.vue'
import Icon from '../components/Icon.vue'
import LinkPicker from '../components/LinkPicker.vue'
import Modal from '../components/Modal.vue'
import NoteGraph from '../components/NoteGraph.vue'
import TagManager from '../components/TagManager.vue'

const app = useApp(), route = useRoute(), router = useRouter()
const notes = ref([]), folders = ref([]), tags = ref([]), selected = ref(null), form = ref(null), search = ref(''), state = ref('active'), mode = ref('edit')
const graph = ref(null), history = ref([]), showHistory = ref(false), picker = ref(null), organizer = ref(false), preview = ref(null), busy = ref(false), uploading = ref(0), editor = ref(null)
const filterNames = { active: 'Ativas', archived: 'Arquivo', trash: 'Lixeira', templates: 'Modelos' }
const TOOLS = [
  { key: 'heading', label: 'Título', icon: 'heading' }, { key: 'bold', label: 'Negrito (Ctrl+B)', icon: 'bold' }, { key: 'italic', label: 'Itálico (Ctrl+I)', icon: 'italic' },
  { key: 'list', label: 'Lista', icon: 'list' }, { key: 'checklist', label: 'Checklist', icon: 'checklist' }, { key: 'table', label: 'Tabela', icon: 'table' }, { key: 'code', label: 'Código', icon: 'code' }
]
const overview = computed(() => route.query.view === 'overview')
const tagFilter = computed(() => typeof route.query.tag === 'string' ? route.query.tag : '')
const tagById = computed(() => new Map(tags.value.map(t => [t.id, t])))
const noteTitles = computed(() => new Map(notes.value.map(n => [n.id, n.title])))
const rendered = computed(() => renderMarkdown(form.value?.body || '', noteTitles.value))
const toForm = n => ({ title: n.title, body: n.body, folderId: n.folder_id, favorite: n.favorite, template: n.template, tagIds: n.tags?.map(t => t.id) || [], version: n.version })
const dirty = computed(() => !!(selected.value && form.value && app.canEdit && JSON.stringify(form.value) !== JSON.stringify(toForm(selected.value))))

async function loadList() {
  if (!app.projectId) return
  try {
    const q = new URLSearchParams({ state: state.value, q: search.value }); if (tagFilter.value) q.set('tag', tagFilter.value)
    ;[notes.value, folders.value, tags.value] = await Promise.all([api(`/projects/${app.projectId}/notes?${q}`), api(`/projects/${app.projectId}/folders`), api(`/projects/${app.projectId}/tags`)])
    dropDeletedOrganization()
  } catch (e) { app.fail(e) }
}
// Tags or folders deleted in "Organizar" (or by someone else) disappear from the open note without marking it as edited.
function dropDeletedOrganization() {
  const tagIds = new Set(tags.value.map(t => t.id)), folderIds = new Set(folders.value.map(f => f.id))
  if (selected.value) selected.value = { ...selected.value, tags: (selected.value.tags || []).filter(t => tagIds.has(t.id)), folder_id: folderIds.has(selected.value.folder_id) ? selected.value.folder_id : null }
  if (form.value) { form.value.tagIds = form.value.tagIds.filter(id => tagIds.has(id)); if (!folderIds.has(form.value.folderId)) form.value.folderId = null }
}
async function open(id) {
  if (!id) { selected.value = null; form.value = null; return }
  try { selected.value = await api(`/notes/${id}`); form.value = toForm(selected.value); mode.value = app.canEdit ? 'edit' : 'preview'; showHistory.value = false } catch (e) { app.fail(e); selected.value = null; form.value = null }
}
async function create(template = false) {
  if (!app.canEdit) return
  try {
    const n = await api(`/projects/${app.projectId}/notes`, { method: 'POST', body: body({ title: template ? 'Novo modelo' : 'Nota sem título', body: '', template, tagIds: tagFilter.value ? [tagFilter.value] : [] }) })
    await loadList(); await router.push({ path: `/notes/${n.id}`, query: tagFilter.value ? { tag: tagFilter.value } : {} })
  } catch (e) { app.fail(e) }
}
async function save() {
  if (!selected.value || !app.canEdit || busy.value) return
  busy.value = true
  try { const n = await api(`/notes/${selected.value.id}`, { method: 'PUT', body: body(form.value) }); selected.value = n; form.value = toForm(n); await loadList(); app.flash('Nota salva') }
  catch (e) { app.fail(e) } finally { busy.value = false }
}
async function changeState(next) {
  if (!selected.value) return
  try {
    await api(`/notes/${selected.value.id}/state`, { method: 'PATCH', body: body({ state: next }) })
    await loadList(); router.push('/notes')
    app.flash(next === 'trash' ? 'Nota movida para a lixeira' : next === 'restore' ? 'Nota restaurada' : 'Nota arquivada')
  } catch (e) { app.fail(e) }
}
async function duplicate() { try { const n = await api(`/notes/${selected.value.id}/duplicate`, { method: 'POST' }); await loadList(); router.push(`/notes/${n.id}`) } catch (e) { app.fail(e) } }
async function exportMd() { try { downloadBlob(await api(`/notes/${selected.value.id}/export`), `nota-${selected.value.id}.md`) } catch (e) { app.fail(e) } }
async function versions() { try { history.value = await api(`/notes/${selected.value.id}/versions`); showHistory.value = true } catch (e) { app.fail(e) } }
async function restore(v) {
  if (!confirm(`Restaurar a versão ${v.version}? Uma nova versão será criada.`)) return
  try { selected.value = await api(`/notes/${selected.value.id}/restore-version/${v.version}`, { method: 'POST' }); form.value = toForm(selected.value); showHistory.value = false; await loadList(); app.flash('Versão restaurada') } catch (e) { app.fail(e) }
}
function toggleTag(id) { form.value.tagIds = form.value.tagIds.includes(id) ? form.value.tagIds.filter(t => t !== id) : [...form.value.tagIds, id] }
function setTagFilter(id) { router.replace({ path: route.path, query: { ...route.query, tag: tagFilter.value === id || !id ? undefined : id } }) }

function applyEdit(result) {
  form.value.body = result.value
  nextTick(() => { const el = editor.value; if (el) { el.focus(); el.setSelectionRange(result.start, result.end) } })
}
function selection() { const el = editor.value; return el ? [el.selectionStart, el.selectionEnd] : [form.value.body.length, form.value.body.length] }
function format(key) {
  if (!app.canEdit) return
  const [start, end] = selection(), text = form.value.body
  const actions = {
    heading: () => prefixLines(text, start, end, '## '), list: () => prefixLines(text, start, end, '- '), checklist: () => prefixLines(text, start, end, '- [ ] '),
    bold: () => wrapText(text, start, end, '**'), italic: () => wrapText(text, start, end, '_'), code: () => wrapText(text, start, end, '\n```\n', '\n```\n', 'código'),
    table: () => insertText(text, start, end, `${start && text[start - 1] !== '\n' ? '\n' : ''}| Coluna | Valor |\n| --- | --- |\n| A | B |\n`)
  }
  applyEdit(actions[key]())
}
function openPicker(trigger = false) {
  const [start, end] = selection()
  picker.value = { start: trigger ? start - 2 : start, end, tab: 'note' }
}
function insertLink({ kind, id, label, uploaded }) {
  const { start, end } = picker.value, text = form.value.body
  picker.value = null
  if (uploaded) selectedRefresh()
  const snippet = kind === 'file' && uploaded && fileKind(uploaded.mime, uploaded.name) === 'image' ? `![${label}](${fileUrl(id)})` : wikilink(kind, id, label)
  applyEdit(insertText(text, start, end, snippet))
}
function onInput(event) {
  if (!event.inputType?.startsWith('insert') || !event.data?.endsWith('[')) return
  const pos = event.target.selectionStart
  if (event.target.value.slice(pos - 2, pos) === '[[' && event.target.value[pos - 3] !== '[') openPicker(true)
}
function onEditorKey(event) {
  if (!(event.ctrlKey || event.metaKey)) return
  const key = event.key.toLowerCase()
  if (key === 'b') { event.preventDefault(); format('bold') }
  else if (key === 'i') { event.preventDefault(); format('italic') }
  else if (key === 'k') { event.preventDefault(); openPicker() }
}
function onPageKey(event) { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && selected.value) { event.preventDefault(); save() } }
async function selectedRefresh() {
  const id = selected.value?.id
  try { const fresh = await api(`/notes/${id}`); if (selected.value?.id === id) selected.value = { ...selected.value, files: fresh.files } } catch (_) {}
}
async function attach(event) {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file || !selected.value) return
  const noteId = selected.value.id
  uploading.value = 0.01
  try {
    const f = await uploadFile(app.projectId, file, { noteId, onProgress: p => { uploading.value = Math.max(0.01, p) } })
    if (selected.value?.id !== noteId || !form.value) return app.flash(`${f.name} foi anexado à nota anterior.`)
    await selectedRefresh()
    const [start, end] = selection()
    applyEdit(insertText(form.value.body, start, end, fileKind(f.mime, f.name) === 'image' ? `![${f.name}](${fileUrl(f.id)})` : wikilink('file', f.id, f.name)))
    app.flash('Arquivo anexado. Salve a nota para manter o link no texto.')
  } catch (e) { app.fail(e) } finally { uploading.value = 0 }
}
async function openFile(id) { try { preview.value = { files: [await api(`/files/${id}`)], index: 0 } } catch (e) { app.fail(e) } }
function onPreviewClick(event) {
  const link = event.target.closest('a'); if (!link) return
  const href = link.getAttribute('href') || ''
  if (link.dataset.kind === 'file') { event.preventDefault(); openFile(link.dataset.id) }
  else if (link.dataset.kind || (href.startsWith('/') && !href.startsWith('/api/') && !link.target)) { event.preventDefault(); router.push(href) }
}
async function loadGraph() { if (app.projectId) try { graph.value = await api(`/projects/${app.projectId}/graph`) } catch (e) { app.fail(e) } }
function toggleOverview() { router.push(overview.value ? { path: '/notes' } : { path: '/notes', query: { view: 'overview' } }) }
function leaveGuard() { return !dirty.value || confirm('Esta nota tem alterações não salvas. Sair mesmo assim?') }
function beforeUnload(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }

let debounce
watch([search, state, tagFilter, () => app.projectId], () => { clearTimeout(debounce); debounce = setTimeout(loadList, 250) })
watch(() => app.projectId, () => { graph.value = null; if (overview.value) loadGraph() })
watch(() => route.params.id, id => open(id))
watch(overview, value => { if (value) loadGraph() })
onBeforeRouteUpdate((to, from) => to.params.id === from.params.id || leaveGuard())
onBeforeRouteLeave(leaveGuard)
onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload); window.addEventListener('keydown', onPageKey)
  loadUploadLimits(); await loadList()
  if (route.params.id) await open(route.params.id)
  if (overview.value) loadGraph()
})
onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('keydown', onPageKey) })
</script>

<template>
  <div class="page-head">
    <div><div class="eyebrow">CONHECIMENTO CONECTADO</div><h1>Notas</h1><p class="muted">Escreva, conecte e encontre suas ideias.</p></div>
    <div class="head-actions">
      <button class="btn btn-ghost" :class="{ selected: overview }" :aria-pressed="overview" @click="toggleOverview"><Icon name="overview" />Visão geral</button>
      <button v-if="app.canEdit && app.projectId" class="btn btn-ghost" @click="organizer = true"><Icon name="tags" />Organizar</button>
      <button v-if="app.canEdit && app.projectId" class="btn btn-primary" @click="create(false)"><Icon name="plus" />Nova nota</button>
    </div>
  </div>
  <div v-if="!app.projectId" class="empty-inline">Crie um projeto para começar.</div>

  <section v-else-if="overview" class="panel graph-panel">
    <div class="panel-head">
      <div><h2>Visão geral das notas</h2><p class="muted">Cada ponto é uma nota; a cor indica a categoria e as linhas mostram os links entre elas.</p></div>
      <button class="btn btn-ghost" @click="toggleOverview"><Icon name="arrow-left" />Voltar às notas</button>
    </div>
    <NoteGraph v-if="graph" :graph="graph" @open="id => router.push(`/notes/${id}`)" />
    <div v-else class="empty-inline"><Icon name="loading" class="spin" /> Montando a visão geral…</div>
  </section>

  <div v-else class="notes-layout" :class="{ 'has-selection': selected }">
    <aside class="notes-list panel" aria-label="Lista de notas">
      <div class="notes-list-tools">
        <label class="search-field"><Icon name="search" :size="16" /><span class="sr-only">Buscar notas</span><input v-model="search" type="search" placeholder="Buscar notas…" /></label>
        <select v-model="state" aria-label="Estado das notas"><option v-for="(label, key) in filterNames" :key="key" :value="key">{{ label }}</option></select>
      </div>
      <div v-if="tags.length" class="tag-filter" role="group" aria-label="Filtrar por etiqueta">
        <button v-for="t in tags" :key="t.id" type="button" class="legend-chip" :class="{ active: tagFilter === t.id, faded: tagFilter && tagFilter !== t.id }" :style="{ '--chip': t.color }" :aria-pressed="tagFilter === t.id" @click="setTagFilter(t.id)"><span class="legend-dot"></span>{{ t.name }}</button>
      </div>
      <div v-if="!notes.length" class="empty-inline">{{ tagFilter ? 'Nenhuma nota com esta etiqueta.' : 'Nenhuma nota encontrada.' }}</div>
      <router-link v-for="n in notes" :key="n.id" :to="{ path: `/notes/${n.id}`, query: tagFilter ? { tag: tagFilter } : {} }" class="note-list-item" :class="{ active: selected?.id === n.id }">
        <span class="note-title"><Icon v-if="n.favorite" name="star" :size="14" class="star-on" />{{ n.title }}</span>
        <span class="note-meta">
          <small>{{ dateTime(n.updated_at) }}</small>
          <span class="tag-dots"><span v-for="id in n.tag_ids.slice(0, 4)" :key="id" class="legend-dot" :style="{ '--chip': tagById.get(id)?.color }" :title="tagById.get(id)?.name"></span></span>
        </span>
      </router-link>
      <button v-if="app.canEdit" class="list-create" @click="create(true)"><Icon name="plus" :size="16" />Criar modelo de nota</button>
    </aside>

    <section v-if="selected && form" class="note-editor panel">
      <div class="note-toolbar">
        <router-link :to="{ path: '/notes', query: tagFilter ? { tag: tagFilter } : {} }" class="btn btn-ghost btn-sm mobile-only"><Icon name="arrow-left" :size="16" />Notas</router-link>
        <div class="segmented">
          <button v-if="app.canEdit" :class="{ active: mode === 'edit' }" @click="mode = 'edit'"><Icon name="edit" :size="15" />Editar</button>
          <button :class="{ active: mode === 'preview' }" @click="mode = 'preview'"><Icon name="eye" :size="15" />Visualizar</button>
        </div>
        <div class="head-actions">
          <span v-if="dirty" class="unsaved">Não salvo</span>
          <button class="btn btn-ghost btn-sm" title="Histórico de versões" @click="versions"><Icon name="timeline" :size="16" /><span class="hide-sm">Histórico</span></button>
          <button class="btn btn-ghost btn-sm" title="Exportar Markdown" @click="exportMd"><Icon name="download" :size="16" /><span class="hide-sm">Exportar</span></button>
          <button v-if="app.canEdit" class="btn btn-primary btn-sm" :disabled="busy || !dirty" title="Salvar (Ctrl+S)" @click="save">{{ busy ? 'Salvando…' : 'Salvar' }}</button>
        </div>
      </div>

      <div v-if="mode === 'preview'" class="note-preview">
        <h1>{{ form.title }}</h1>
        <div v-if="form.tagIds.length" class="tag-row"><span v-for="id in form.tagIds" :key="id" class="tag-pill" :style="{ '--chip': tagById.get(id)?.color }">{{ tagById.get(id)?.name }}</span></div>
        <div class="markdown" @click="onPreviewClick" v-html="rendered"></div>
        <p v-if="!form.body.trim()" class="muted">Esta nota está vazia.</p>
      </div>
      <template v-else>
        <input v-model="form.title" class="title-input" :disabled="!app.canEdit" maxlength="200" aria-label="Título da nota" />
        <div class="editor-actions" role="toolbar" aria-label="Formatação">
          <button v-for="tool in TOOLS" :key="tool.key" type="button" class="tool-btn" :title="tool.label" :aria-label="tool.label" :disabled="!app.canEdit" @click="format(tool.key)"><Icon :name="tool.icon" :size="16" /></button>
          <span class="tool-sep"></span>
          <button type="button" class="tool-btn tool-wide" title="Inserir link para nota, arquivo ou lembrete (Ctrl+K)" :disabled="!app.canEdit" @click="openPicker()"><Icon name="link" :size="16" />Link</button>
          <label class="tool-btn tool-wide file-label" :class="{ disabled: !app.canEdit }" title="Anexar arquivo e inserir no texto"><Icon name="attach" :size="16" />{{ uploading ? `${Math.round(uploading * 100)}%` : 'Anexo' }}<input type="file" :accept="acceptTypes()" :disabled="!app.canEdit || !!uploading" @change="attach" /></label>
        </div>
        <textarea ref="editor" v-model="form.body" class="markdown-editor" :disabled="!app.canEdit" maxlength="200000" spellcheck="true" aria-label="Conteúdo Markdown"
          placeholder="Comece a escrever em Markdown… Digite [[ para criar um link para outra nota, arquivo ou lembrete." @input="onInput" @keydown="onEditorKey"></textarea>
        <div class="note-options">
          <label>Pasta<select v-model="form.folderId" :disabled="!app.canEdit"><option :value="null">Sem pasta</option><option v-for="f in folders" :key="f.id" :value="f.id">{{ f.name }}</option></select></label>
          <label class="check-line"><input v-model="form.favorite" type="checkbox" :disabled="!app.canEdit" /><Icon name="star" :size="15" />Favorita</label>
          <label class="check-line"><input v-model="form.template" type="checkbox" :disabled="!app.canEdit" />Modelo</label>
        </div>
        <div class="tag-wrap">
          <strong>Etiquetas</strong>
          <button v-for="t in tags" :key="t.id" type="button" class="legend-chip" :class="{ active: form.tagIds.includes(t.id) }" :style="{ '--chip': t.color }" :aria-pressed="form.tagIds.includes(t.id)" :disabled="!app.canEdit" @click="toggleTag(t.id)">
            <span class="legend-dot"></span>{{ t.name }}<Icon v-if="form.tagIds.includes(t.id)" name="check" :size="13" />
          </button>
          <button v-if="app.canEdit" type="button" class="text-link" @click="organizer = true"><Icon name="tags" :size="15" />{{ tags.length ? 'Gerenciar' : 'Criar etiquetas' }}</button>
        </div>
      </template>

      <div class="note-relations">
        <div><strong>Referências de volta</strong><router-link v-for="n in selected.backlinks" :key="n.id" :to="`/notes/${n.id}`"><Icon name="notes" :size="14" />{{ n.title }}</router-link><small v-if="!selected.backlinks?.length">Nenhuma ainda</small></div>
        <div><strong>Links para</strong><router-link v-for="n in selected.links" :key="n.id" :to="`/notes/${n.id}`"><Icon name="link" :size="14" />{{ n.title }}</router-link><small v-if="!selected.links?.length">Nenhum ainda</small></div>
        <div><strong>Anexos</strong><button v-for="(f, i) in selected.files" :key="f.id" type="button" class="link-button" @click="preview = { files: selected.files, index: i }"><Icon :name="kindIcon[fileKind(f.mime, f.name)]" :size="14" />{{ f.name }}<small>{{ formatSize(f.size_bytes) }}</small></button><small v-if="!selected.files?.length">Nenhum ainda</small></div>
      </div>
      <p class="note-meta-line">Criada por {{ selected.created_by_name }} em {{ dateTime(selected.created_at) }} · atualizada por {{ selected.updated_by_name }} em {{ dateTime(selected.updated_at) }}</p>
      <div v-if="app.canEdit" class="note-danger">
        <button class="text-link" @click="duplicate"><Icon name="copy" :size="15" />Duplicar</button>
        <button v-if="!selected.archived_at && !selected.deleted_at" class="text-link" @click="changeState('archive')"><Icon name="archive" :size="15" />Arquivar</button>
        <button v-if="!selected.deleted_at" class="text-link danger-text" @click="changeState('trash')"><Icon name="trash" :size="15" />Mover para lixeira</button>
        <button v-if="selected.deleted_at || selected.archived_at" class="text-link" @click="changeState('restore')"><Icon name="restore" :size="15" />Restaurar</button>
      </div>
    </section>
    <div v-else class="panel note-placeholder"><div class="empty-icon"><Icon name="notes" :size="30" /></div><h2>Escolha uma nota</h2><p>Selecione uma nota na lista para ler ou editar.</p></div>
  </div>

  <Modal v-if="showHistory" title="Histórico de versões" size="md" @close="showHistory = false">
    <div v-for="v in history" :key="v.id" class="version-row">
      <span><strong>Versão {{ v.version }}</strong> · {{ v.editor_name }}<small>{{ dateTime(v.edited_at) }}</small></span>
      <button v-if="app.canEdit && v.version !== selected.version" class="btn btn-ghost btn-sm" @click="restore(v)"><Icon name="restore" :size="15" />Restaurar</button>
      <span v-else-if="v.version === selected.version" class="count-pill">atual</span>
    </div>
  </Modal>
  <LinkPicker v-if="picker" :project-id="app.projectId" :note-id="selected?.id" :initial-tab="picker.tab" @select="insertLink" @close="picker = null; editor?.focus()" />
  <TagManager v-if="organizer" :project-id="app.projectId" :tags="tags" :folders="folders" @changed="loadList" @close="organizer = false" />
  <FilePreview v-if="preview" :files="preview.files" :index="preview.index" @close="preview = null" />
</template>
