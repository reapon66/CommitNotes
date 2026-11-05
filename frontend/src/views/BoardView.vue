<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, body, dateTime } from '../api'
import { useApp } from '../store'
import Avatar from '../components/Avatar.vue'
import ColumnEditor from '../components/ColumnEditor.vue'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'
import TaskModal from '../components/TaskModal.vue'

const app = useApp(), route = useRoute(), router = useRouter()
const boards = ref([]), boardId = ref(''), view = ref(null), members = ref([]), notes = ref([]), loading = ref(false)
const search = ref(''), person = ref(''), priority = ref(''), collapsed = ref(new Set()), mobileColumn = ref(''), isMobile = ref(false)
const quick = ref(null), quickInput = ref(null), dragging = ref(null), drop = ref(null), columnEditor = ref(null), boardModal = ref(null), boardName = ref(''), boardTemplate = ref('basic'), busy = ref(false)
const taskId = computed(() => typeof route.query.task === 'string' ? route.query.task : null)
const PRIORITY = { HIGH: 'Alta', MEDIUM: 'Média', LOW: 'Baixa' }
const TEMPLATES = {
  basic: { label: 'Básico', columns: [{ name: 'A fazer', color: '#64748b' }, { name: 'Em andamento', color: '#3b82f6' }, { name: 'Concluído', color: '#22c55e', isDone: true }] },
  dev: { label: 'Desenvolvimento', columns: [{ name: 'Backlog', color: '#64748b' }, { name: 'A fazer', color: '#6366f1' }, { name: 'Em andamento', color: '#3b82f6' }, { name: 'Em revisão', color: '#f59e0b' }, { name: 'Concluído', color: '#22c55e', isDone: true }] },
  empty: { label: 'Em branco', columns: [] }
}
const media = typeof matchMedia === 'function' ? matchMedia('(max-width: 760px)') : null
let poll, touchStart = null, pendingBoard = null

const filtered = computed(() => {
  const term = search.value.trim().toLowerCase(), who = person.value === 'me' ? app.user?.id : person.value
  return (view.value?.tasks || []).filter(t => (!term || t.title.toLowerCase().includes(term)) && (!who || t.assignees.some(a => a.id === who)) && (!priority.value || t.priority === priority.value))
})
const filtering = computed(() => !!(search.value.trim() || person.value || priority.value))
const tasksIn = id => filtered.value.filter(t => t.column_id === id)
const totalIn = id => (view.value?.tasks || []).filter(t => t.column_id === id).length
const shownColumns = computed(() => isMobile.value ? (view.value?.columns || []).filter(c => c.id === mobileColumn.value) : view.value?.columns || [])
const storageKey = kind => `cn_${kind}_${kind === 'board' ? app.projectId : boardId.value}`
function remember(kind, value) { try { localStorage.setItem(storageKey(kind), value) } catch (_) {} }
function recall(kind) { try { return localStorage.getItem(storageKey(kind)) } catch (_) { return null } }

async function loadBoards() {
  if (!app.projectId) return
  const project = app.projectId
  try {
    const [b, m, n] = await Promise.all([api(`/projects/${project}/boards`), api(`/projects/${project}/members`), api(`/projects/${project}/notes`)])
    if (project !== app.projectId) return
    boards.value = b; members.value = m; notes.value = n
    const saved = recall('board'), wanted = pendingBoard; pendingBoard = null
    boardId.value = [wanted, boardId.value, saved].find(id => id && b.some(x => x.id === id)) || b[0]?.id || ''
    if (!boardId.value) view.value = null
  } catch (e) { app.fail(e) }
}
async function loadView(quiet = false) {
  const board = boardId.value
  if (!board) return
  if (!quiet) loading.value = true
  try {
    const next = await api(`/boards/${board}/view`)
    if (board !== boardId.value) return
    view.value = next
    if (!next.columns.some(c => c.id === mobileColumn.value)) mobileColumn.value = next.columns.find(c => !c.is_done && totalIn(c.id))?.id || next.columns[0]?.id || ''
  } catch (e) { if (!quiet) app.fail(e) } finally { loading.value = false }
}
function setView(next) { view.value = next; if (!next.columns.some(c => c.id === mobileColumn.value)) mobileColumn.value = next.columns[0]?.id || '' }
function toggleCollapse(id) {
  const next = new Set(collapsed.value); next.has(id) ? next.delete(id) : next.add(id)
  collapsed.value = next; remember('collapsed', JSON.stringify([...next]))
}
function openTask(id) { router.replace({ query: { ...route.query, task: id } }) }
function closeTask() { const { task, ...rest } = route.query; router.replace({ query: rest }) }
// A task opened from a notification may belong to another project or board: switch to it before showing the task.
async function syncTaskBoard(id) {
  if (!id || view.value?.tasks.some(t => t.id === id)) return
  try {
    const t = await api(`/tasks/${id}`)
    if (t.project_id !== app.projectId) {
      if (!app.projects.some(p => p.id === t.project_id)) await app.loadProjects()
      pendingBoard = t.board_id; app.selectProject(t.project_id)
    } else if (t.board_id !== boardId.value) boardId.value = t.board_id
  } catch (e) { app.fail(e); closeTask() }
}

function focusQuick() { nextTick(() => [quickInput.value].flat()[0]?.focus()) }
function startQuick(columnId) { quick.value = { columnId, title: '' }; focusQuick() }
async function createTask() {
  const q = quick.value
  if (!q?.title.trim() || busy.value) return
  busy.value = true
  try {
    const who = person.value === 'me' ? app.user.id : person.value
    await api(`/boards/${boardId.value}/tasks`, { method: 'POST', body: body({ title: q.title.trim(), columnId: q.columnId, priority: priority.value || 'MEDIUM', assigneeIds: who ? [who] : [] }) })
    q.title = ''; await loadView(true); focusQuick()
  } catch (e) { app.fail(e) } finally { busy.value = false }
}
async function moveTask(task, columnId, beforeId = null) {
  if (!app.canEdit) return
  const all = view.value.tasks, siblings = all.filter(t => t.column_id === columnId && t.id !== task.id)
  const index = beforeId ? Math.max(0, siblings.findIndex(t => t.id === beforeId)) : siblings.length
  if (task.column_id === columnId && all.filter(t => t.column_id === columnId).findIndex(t => t.id === task.id) === index) return
  const rest = all.filter(t => t.id !== task.id), anchor = beforeId ? rest.findIndex(t => t.id === beforeId) : rest.findLastIndex(t => t.column_id === columnId)
  rest.splice(beforeId ? anchor : anchor + 1, 0, { ...task, column_id: columnId })
  view.value = { ...view.value, tasks: rest }
  try { await api(`/tasks/${task.id}`, { method: 'PATCH', body: body({ columnId, position: index }) }) } catch (e) { app.fail(e) }
  await loadView(true)
}
function onDragStart(event, task) {
  if (!app.canEdit) return event.preventDefault()
  dragging.value = task; event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', task.id)
}
function onDragOver(event, columnId) {
  if (!dragging.value) return
  event.preventDefault(); event.dataTransfer.dropEffect = 'move'
  const cards = [...event.currentTarget.querySelectorAll('.task-card:not(.is-dragging)')]
  const before = cards.find(card => { const box = card.getBoundingClientRect(); return event.clientY < box.top + box.height / 2 })
  drop.value = { columnId, beforeId: before?.dataset.task || null }
}
function onDrop(event, columnId) {
  event.preventDefault()
  const task = dragging.value, target = drop.value
  dragging.value = null; drop.value = null
  if (task) moveTask(task, columnId, target?.columnId === columnId ? target.beforeId : null)
}
function onDragEnd() { dragging.value = null; drop.value = null }
function onDragLeave(event) { if (!event.currentTarget.contains(event.relatedTarget)) drop.value = null }

function dueState(t) {
  if (!t.due_at) return ''
  if (t.completed_at) return 'done'
  const diff = new Date(t.due_at) - Date.now()
  return diff < 0 ? 'late' : diff < 86400000 ? 'soon' : ''
}
const dueLabel = t => new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(t.due_at))
const progress = t => Number(t.step_count) ? Math.round(100 * Number(t.done_count) / Number(t.step_count)) : 0

function openBoardModal(mode) { boardModal.value = mode; boardName.value = mode === 'rename' ? view.value?.board.name || '' : ''; boardTemplate.value = 'basic' }
async function submitBoard() {
  if (!boardName.value.trim()) return
  busy.value = true
  try {
    if (boardModal.value === 'rename') {
      const b = await api(`/boards/${boardId.value}`, { method: 'PATCH', body: body({ name: boardName.value }) })
      boards.value = boards.value.map(x => x.id === b.id ? b : x); view.value.board = b; app.flash('Quadro renomeado')
    } else {
      const b = await api(`/projects/${app.projectId}/boards`, { method: 'POST', body: body({ name: boardName.value, columns: TEMPLATES[boardTemplate.value].columns }) })
      boards.value = [...boards.value, b].sort((x, y) => x.name.localeCompare(y.name)); boardId.value = b.id; app.flash('Quadro criado')
    }
    boardModal.value = null
  } catch (e) { app.fail(e) } finally { busy.value = false }
}
async function deleteBoard() {
  if (!confirm(`Excluir o quadro ${view.value.board.name} e todas as suas tarefas? Esta ação não pode ser desfeita.`)) return
  try { await api(`/boards/${boardId.value}`, { method: 'DELETE' }); boardId.value = ''; await loadBoards(); app.flash('Quadro excluído') } catch (e) { app.fail(e) }
}
async function applyTemplate(key) {
  busy.value = true
  try { for (const column of TEMPLATES[key].columns) setView(await api(`/boards/${boardId.value}/columns`, { method: 'POST', body: body(column) })) } catch (e) { app.fail(e) } finally { busy.value = false }
}
function onTouchStart(event) { touchStart = event.touches.length === 1 ? { x: event.touches[0].clientX, y: event.touches[0].clientY } : null }
function onTouchEnd(event) {
  if (!touchStart || !isMobile.value || !view.value) return
  const dx = event.changedTouches[0].clientX - touchStart.x, dy = event.changedTouches[0].clientY - touchStart.y
  touchStart = null
  if (Math.abs(dx) < 70 || Math.abs(dy) > Math.abs(dx) * 0.6) return
  const columns = view.value.columns, i = columns.findIndex(c => c.id === mobileColumn.value), next = columns[i + (dx < 0 ? 1 : -1)]
  if (next) mobileColumn.value = next.id
}
function updateMedia() { isMobile.value = !!media?.matches }

watch(() => app.projectId, () => { view.value = null; boardId.value = ''; loadBoards() })
watch(boardId, id => {
  if (!id) return
  remember('board', id); document.querySelector('.kanban-board')?.scrollTo({ left: 0 })
  try { collapsed.value = new Set(JSON.parse(recall('collapsed') || '[]')) } catch (_) { collapsed.value = new Set() }
  loadView()
})
watch(taskId, syncTaskBoard)
watch(mobileColumn, id => nextTick(() => document.querySelector(`[data-column-tab="${id}"]`)?.scrollIntoView({ block: 'nearest', inline: 'center' })))
onMounted(async () => {
  updateMedia(); media?.addEventListener('change', updateMedia)
  await loadBoards(); syncTaskBoard(taskId.value)
  poll = setInterval(() => { if (!document.hidden && !dragging.value && !quick.value?.title) loadView(true) }, 30000)
})
onBeforeUnmount(() => { clearInterval(poll); media?.removeEventListener('change', updateMedia) })
</script>

<template>
  <div class="page-head">
    <div><div class="eyebrow">TRABALHO EM EQUIPE</div><h1>Kanban</h1><p class="muted">Acompanhe tarefas, responsáveis e etapas.</p></div>
    <div v-if="app.projectId" class="head-actions board-head">
      <label v-if="boards.length" class="board-switch"><Icon name="kanban" :size="17" /><span class="sr-only">Quadro</span>
        <select v-model="boardId" aria-label="Quadro"><option v-for="b in boards" :key="b.id" :value="b.id">{{ b.name }}</option></select>
      </label>
      <button v-if="app.canEdit && view" class="icon-btn" title="Renomear quadro" aria-label="Renomear quadro" @click="openBoardModal('rename')"><Icon name="edit" /></button>
      <button v-if="app.role === 'OWNER' && view" class="icon-btn danger-text" title="Excluir quadro" aria-label="Excluir quadro" @click="deleteBoard"><Icon name="trash" /></button>
      <button v-if="app.canEdit" class="btn btn-ghost" @click="openBoardModal('create')"><Icon name="plus" />Novo quadro</button>
    </div>
  </div>
  <div v-if="!app.projectId" class="empty-inline">Crie um projeto para começar.</div>
  <div v-else-if="!boards.length" class="empty-hero"><div class="empty-icon"><Icon name="kanban" :size="30" /></div><h2>Nenhum quadro ainda</h2><p>Crie um quadro para organizar as tarefas da equipe.</p><button v-if="app.canEdit" class="btn btn-primary" @click="openBoardModal('create')"><Icon name="plus" />Criar quadro</button></div>
  <template v-else-if="view">
    <div class="board-toolbar">
      <label class="search-field"><Icon name="search" :size="16" /><span class="sr-only">Buscar tarefas</span><input v-model="search" type="search" placeholder="Buscar tarefas…" /></label>
      <select v-model="person" aria-label="Filtrar por responsável"><option value="">Responsáveis</option><option value="me">Minhas tarefas</option><option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }}</option></select>
      <select v-model="priority" aria-label="Filtrar por prioridade"><option value="">Prioridades</option><option v-for="(label, key) in PRIORITY" :key="key" :value="key">Prioridade {{ label.toLowerCase() }}</option></select>
      <button v-if="filtering" class="text-link" @click="search = ''; person = ''; priority = ''"><Icon name="close" :size="15" />Limpar filtros</button>
      <button v-if="app.canEdit && view.columns.length" class="btn btn-ghost btn-sm toolbar-end" aria-label="Nova coluna" title="Nova coluna" @click="columnEditor = { column: null }"><Icon name="columns" :size="16" /><span class="hide-sm">Nova coluna</span></button>
    </div>

    <nav v-if="isMobile && view.columns.length" class="column-tabs" aria-label="Colunas do quadro">
      <button v-for="c in view.columns" :key="c.id" type="button" class="column-tab" :data-column-tab="c.id" :class="{ active: mobileColumn === c.id }" :style="{ '--chip': c.color }" :aria-pressed="mobileColumn === c.id" @click="mobileColumn = c.id">
        <span class="legend-dot"></span>{{ c.name }}<span class="count-pill">{{ tasksIn(c.id).length }}</span>
      </button>
    </nav>

    <div v-if="!view.columns.length" class="empty-hero board-empty">
      <div class="empty-icon"><Icon name="columns" :size="30" /></div>
      <h2>Este quadro ainda não tem colunas</h2>
      <p>Comece com um modelo pronto ou crie a primeira coluna do seu jeito.</p>
      <div v-if="app.canEdit" class="template-picker">
        <button v-for="key in ['basic', 'dev']" :key="key" type="button" class="template-card" :disabled="busy" @click="applyTemplate(key)">
          <strong>{{ TEMPLATES[key].label }}</strong>
          <span class="template-columns"><span v-for="c in TEMPLATES[key].columns" :key="c.name" :style="{ '--chip': c.color }">{{ c.name }}</span></span>
        </button>
        <button type="button" class="template-card template-blank" @click="columnEditor = { column: null }"><Icon name="plus" :size="22" /><strong>Criar coluna</strong></button>
      </div>
    </div>

    <div v-else class="kanban-board" :class="{ mobile: isMobile }" @touchstart.passive="onTouchStart" @touchend.passive="onTouchEnd">
      <section v-for="col in shownColumns" :key="col.id" class="kanban-column" :class="{ collapsed: !isMobile && collapsed.has(col.id), 'drop-active': drop?.columnId === col.id }" :style="{ '--column': col.color }"
        :aria-label="`Coluna ${col.name}`" @dragover="onDragOver($event, col.id)" @drop="onDrop($event, col.id)" @dragleave="onDragLeave">
        <template v-if="!isMobile && collapsed.has(col.id)">
          <button type="button" class="collapsed-strip" :title="`Expandir ${col.name}`" :aria-label="`Expandir coluna ${col.name}`" @click="toggleCollapse(col.id)">
            <Icon name="expand" :size="17" /><span class="count-pill">{{ tasksIn(col.id).length }}</span><span class="strip-name">{{ col.name }}</span>
            <Icon v-if="col.is_done" name="done" :size="15" class="strip-done" />
          </button>
        </template>
        <template v-else>
          <header class="column-head">
            <span class="column-dot"></span>
            <h2 :title="col.name">{{ col.name }}</h2>
            <Icon v-if="col.is_done" name="done" :size="15" class="done-mark" title="Coluna de conclusão" />
            <span class="count-pill" :title="filtering ? `${tasksIn(col.id).length} de ${totalIn(col.id)} tarefas` : `${totalIn(col.id)} tarefas`">{{ filtering ? `${tasksIn(col.id).length}/${totalIn(col.id)}` : totalIn(col.id) }}</span>
            <button v-if="app.canEdit" type="button" class="icon-btn" :aria-label="`Editar coluna ${col.name}`" title="Editar coluna" @click="columnEditor = { column: col }"><Icon name="more" /></button>
            <button v-if="!isMobile" type="button" class="icon-btn" :aria-label="`Recolher coluna ${col.name}`" title="Recolher coluna" @click="toggleCollapse(col.id)"><Icon name="collapse" :size="17" /></button>
          </header>
          <div class="column-cards">
            <template v-for="t in tasksIn(col.id)" :key="t.id">
              <div v-if="drop?.columnId === col.id && drop.beforeId === t.id" class="drop-placeholder"></div>
              <article class="task-card" :class="{ 'is-dragging': dragging?.id === t.id, completed: t.completed_at }" :data-task="t.id" :draggable="app.canEdit && !isMobile" :style="{ '--priority': t.priority === 'HIGH' ? '#e35f6f' : t.priority === 'LOW' ? '#4ba881' : '#d6a237' }"
                @dragstart="onDragStart($event, t)" @dragend="onDragEnd">
                <button type="button" class="task-open" @click="openTask(t.id)">
                  <span class="card-top">
                    <span class="priority-tag"><span class="priority-dot" :class="t.priority.toLowerCase()"></span>{{ PRIORITY[t.priority] }}</span>
                    <span v-if="t.due_at" class="due-badge" :class="dueState(t)" :title="`Prazo: ${dateTime(t.due_at)}`"><Icon name="due" :size="13" />{{ dueLabel(t) }}</span>
                  </span>
                  <strong>{{ t.title }}</strong>
                  <span v-if="Number(t.step_count)" class="progress-row"><span class="progress-track"><span :style="{ width: `${progress(t)}%` }"></span></span>{{ t.done_count }}/{{ t.step_count }}</span>
                  <span class="card-foot">
                    <span class="card-counts">
                      <span v-if="Number(t.comment_count)" :title="`${t.comment_count} comentário(s)`"><Icon name="comment" :size="13" />{{ t.comment_count }}</span>
                      <span v-if="Number(t.file_count)" :title="`${t.file_count} anexo(s)`"><Icon name="attach" :size="13" />{{ t.file_count }}</span>
                    </span>
                    <span class="avatar-stack" :title="t.assignees.map(a => a.name).join(', ') || 'Sem responsáveis'">
                      <Avatar v-for="a in t.assignees.slice(0, 3)" :key="a.id" :name="a.name" :id="a.id" size="xs" />
                      <span v-if="t.assignees.length > 3" class="person person-xs more">+{{ t.assignees.length - 3 }}</span>
                    </span>
                  </span>
                </button>
                <label v-if="app.canEdit && isMobile" class="card-move"><Icon name="move-right" :size="14" /><span class="sr-only">Mover {{ t.title }}</span>
                  <select :value="col.id" @change="moveTask(t, $event.target.value)"><option v-for="target in view.columns" :key="target.id" :value="target.id">{{ target.id === col.id ? `Coluna: ${target.name}` : `Mover para ${target.name}` }}</option></select>
                </label>
              </article>
            </template>
            <div v-if="drop?.columnId === col.id && !drop.beforeId" class="drop-placeholder"></div>
            <p v-if="!tasksIn(col.id).length && !(drop?.columnId === col.id)" class="column-empty">{{ filtering ? 'Nenhuma tarefa com estes filtros.' : 'Nenhuma tarefa.' }}</p>
          </div>
          <form v-if="quick?.columnId === col.id" class="quick-task" @submit.prevent="createTask" @keydown.esc="quick = null">
            <textarea ref="quickInput" v-model="quick.title" rows="2" maxlength="200" placeholder="Título da tarefa" aria-label="Título da nova tarefa" @keydown.enter.exact.prevent="createTask"></textarea>
            <div><button class="btn btn-primary btn-sm" :disabled="busy || !quick.title.trim()">Adicionar</button><button type="button" class="btn btn-ghost btn-sm" @click="quick = null">Cancelar</button></div>
          </form>
          <button v-else-if="app.canEdit" type="button" class="column-add" @click="startQuick(col.id)"><Icon name="plus" :size="16" />Adicionar tarefa</button>
        </template>
      </section>
      <button v-if="app.canEdit && !isMobile" type="button" class="add-column" @click="columnEditor = { column: null }"><Icon name="plus" :size="20" /><span>Nova coluna</span></button>
    </div>
  </template>
  <div v-else-if="loading" class="empty-inline"><Icon name="loading" class="spin" /> Carregando quadro…</div>

  <TaskModal v-if="taskId && view" :key="taskId" :task-id="taskId" :columns="view.columns" :members="members" :notes="notes" @changed="loadView(true)" @close="closeTask" />
  <ColumnEditor v-if="columnEditor" :board-id="boardId" :column="columnEditor.column" :columns="view?.columns || []" :task-count="columnEditor.column ? totalIn(columnEditor.column.id) : 0" @saved="setView" @close="columnEditor = null" />
  <Modal v-if="boardModal" :title="boardModal === 'rename' ? 'Renomear quadro' : 'Novo quadro'" size="sm" form @close="boardModal = null" @submit="submitBoard">
    <label>Nome<input v-model="boardName" maxlength="120" required autofocus placeholder="Ex.: Sprint de outubro" /></label>
    <fieldset v-if="boardModal === 'create'" class="template-options">
      <legend class="field-label">Colunas iniciais</legend>
      <label v-for="(template, key) in TEMPLATES" :key="key" class="template-option" :class="{ active: boardTemplate === key }">
        <input v-model="boardTemplate" type="radio" name="template" :value="key" />
        <strong>{{ template.label }}</strong>
        <span class="template-columns"><span v-for="c in template.columns" :key="c.name" :style="{ '--chip': c.color }">{{ c.name }}</span><small v-if="!template.columns.length">Sem colunas</small></span>
      </label>
    </fieldset>
    <template #footer>
      <button type="button" class="btn btn-ghost" @click="boardModal = null">Cancelar</button>
      <button class="btn btn-primary" :disabled="busy || !boardName.trim()">{{ boardModal === 'rename' ? 'Salvar' : 'Criar quadro' }}</button>
    </template>
  </Modal>
</template>
