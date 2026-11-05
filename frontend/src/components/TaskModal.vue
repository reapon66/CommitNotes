<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, body, dateInput, dateTime, isoInput } from '../api'
import { acceptTypes, fileKind, formatSize, kindIcon, loadUploadLimits, uploadFile } from '../files'
import { feedIcon, taskFeedText } from '../labels'
import { useApp } from '../store'
import Avatar from './Avatar.vue'
import FilePreview from './FilePreview.vue'
import Icon from './Icon.vue'
import MemberPicker from './MemberPicker.vue'
import Modal from './Modal.vue'

const props = defineProps({ taskId: { type: String, required: true }, columns: { type: Array, default: () => [] }, members: { type: Array, default: () => [] }, notes: { type: Array, default: () => [] } })
const emit = defineEmits(['close', 'changed'])
const app = useApp()
const task = ref(null), draft = ref(null), base = ref(null), busy = ref(false), stepLabel = ref(''), comment = ref(''), noteId = ref(''), uploading = ref(0), preview = ref(null), posting = ref(false)
const PRIORITIES = [['LOW', 'Baixa'], ['MEDIUM', 'Média'], ['HIGH', 'Alta']]
const toDraft = t => ({ title: t.title, description: t.description, assigneeIds: t.assignees.map(a => a.id), dueAt: dateInput(t.due_at), priority: t.priority, columnId: t.column_id })
// Only the fields this person edited are sent, so saving never reverts what a colleague changed meanwhile.
const changed = computed(() => draft.value && base.value ? Object.keys(draft.value).filter(key => JSON.stringify(draft.value[key]) !== JSON.stringify(base.value[key])) : [])
const dirty = computed(() => changed.value.length > 0)
const column = computed(() => props.columns.find(c => c.id === (draft.value?.columnId || task.value?.column_id)))
const progress = computed(() => { const steps = task.value?.steps || []; return steps.length ? Math.round(100 * steps.filter(s => s.done).length / steps.length) : 0 })
const overdue = computed(() => task.value?.due_at && !task.value.completed_at && new Date(task.value.due_at) < new Date())
const linkable = computed(() => props.notes.filter(n => !task.value?.notes.some(x => x.id === n.id)))

function apply(next, resetDraft = false) {
  task.value = next
  if (resetDraft || !dirty.value) { draft.value = toDraft(next); base.value = toDraft(next) }
}
async function load() { try { apply(await api(`/tasks/${props.taskId}`), true) } catch (e) { app.fail(e); emit('close') } }
async function run(request, message) {
  try { apply(await request()); emit('changed'); if (message) app.flash(message); return true } catch (e) { app.fail(e); return false }
}
async function save() {
  if (!dirty.value || !draft.value.title.trim()) return
  busy.value = true
  try {
    const fields = Object.fromEntries(changed.value.map(key => [key, key === 'dueAt' ? isoInput(draft.value.dueAt) : draft.value[key]]))
    apply(await api(`/tasks/${props.taskId}`, { method: 'PATCH', body: body(fields) }), true)
    emit('changed'); app.flash('Tarefa salva')
  } catch (e) { app.fail(e) } finally { busy.value = false }
}
async function addStep() { if (stepLabel.value.trim() && await run(() => api(`/tasks/${props.taskId}/steps`, { method: 'POST', body: body({ label: stepLabel.value }) }))) stepLabel.value = '' }
const toggleStep = s => run(() => api(`/steps/${s.id}`, { method: 'PATCH', body: body({ done: !s.done }) }))
const deleteStep = s => run(() => api(`/steps/${s.id}`, { method: 'DELETE' }))
async function linkNote() { if (noteId.value && await run(() => api(`/tasks/${props.taskId}/notes`, { method: 'POST', body: body({ noteId: noteId.value }) }), 'Nota vinculada')) noteId.value = '' }
const unlinkNote = n => run(() => api(`/tasks/${props.taskId}/notes/${n.id}`, { method: 'DELETE' }), 'Nota desvinculada')
async function sendComment() {
  if (!comment.value.trim() || posting.value) return
  posting.value = true
  if (await run(() => api(`/tasks/${props.taskId}/comments`, { method: 'POST', body: body({ body: comment.value }) }))) comment.value = ''
  posting.value = false
}
async function deleteComment(item) { if (confirm('Excluir este comentário?')) await run(() => api(`/comments/${item.id}`, { method: 'DELETE' })) }
async function upload(event) {
  const file = event.target.files?.[0]; event.target.value = ''
  if (!file) return
  uploading.value = 0.01
  try {
    await uploadFile(app.projectId, file, { taskId: props.taskId, onProgress: p => { uploading.value = Math.max(0.01, p) } })
    apply(await api(`/tasks/${props.taskId}`)); emit('changed'); app.flash('Arquivo anexado')
  } catch (e) { app.fail(e) } finally { uploading.value = 0 }
}
async function detach(f) {
  if (!confirm(`Remover o anexo ${f.name} desta tarefa? O arquivo continua na biblioteca.`)) return
  try { await api(`/files/${f.id}/link?taskId=${props.taskId}`, { method: 'DELETE' }); apply(await api(`/tasks/${props.taskId}`)); emit('changed') } catch (e) { app.fail(e) }
}
async function remove() {
  if (!confirm(`Excluir a tarefa ${task.value.title}? Esta ação não pode ser desfeita.`)) return
  try { await api(`/tasks/${props.taskId}`, { method: 'DELETE' }); emit('changed'); emit('close'); app.flash('Tarefa excluída') } catch (e) { app.fail(e) }
}
function close() { if (!dirty.value || confirm('Descartar as alterações não salvas desta tarefa?')) emit('close') }
function onKey(event) { if ((event.ctrlKey || event.metaKey) && event.key === 's') { event.preventDefault(); save() } }
onMounted(() => { load(); loadUploadLimits() })
</script>

<template>
  <Modal title="Detalhes da tarefa" size="lg" panel-class="task-modal" @close="close">
    <template #header>
      <div class="task-head">
        <span v-if="column" class="column-pill" :style="{ '--chip': column.color }"><span class="legend-dot"></span>{{ column.name }}</span>
        <span v-if="task?.completed_at" class="status good"><Icon name="done" :size="14" />Concluída</span>
        <small v-if="task" class="muted">Criada por {{ task.created_by_name }} · {{ dateTime(task.created_at) }}</small>
      </div>
    </template>
    <div v-if="!task" class="empty-inline"><Icon name="loading" class="spin" /> Carregando tarefa…</div>
    <div v-else class="task-layout" @keydown="onKey">
      <div class="task-main">
        <label class="sr-only" for="task-title">Título</label>
        <input id="task-title" v-model="draft.title" class="task-title-input" :disabled="!app.canEdit" required maxlength="200" placeholder="Título da tarefa" />
        <label class="field-label" for="task-description">Descrição</label>
        <textarea id="task-description" v-model="draft.description" :disabled="!app.canEdit" rows="4" maxlength="10000" placeholder="Contexto, critérios de aceite, links úteis…"></textarea>

        <section class="task-section">
          <div class="section-title"><h3><Icon name="checklist" :size="17" />Etapas</h3><span class="count-pill">{{ progress }}%</span></div>
          <div class="progress-track large"><div :style="{ width: `${progress}%` }"></div></div>
          <div v-for="s in task.steps" :key="s.id" class="step-row">
            <label><input type="checkbox" :checked="s.done" :disabled="!app.canEdit" @change="toggleStep(s)" /><span :class="{ done: s.done }">{{ s.label }}</span></label>
            <button v-if="app.canEdit" type="button" class="icon-btn" :aria-label="`Remover etapa ${s.label}`" @click="deleteStep(s)"><Icon name="close" :size="16" /></button>
          </div>
          <form v-if="app.canEdit" class="inline-add" @submit.prevent="addStep">
            <input v-model="stepLabel" maxlength="200" placeholder="Adicionar etapa" aria-label="Nova etapa" />
            <button class="btn btn-ghost btn-sm" :disabled="!stepLabel.trim()"><Icon name="plus" :size="16" />Adicionar</button>
          </form>
        </section>

        <section class="task-section">
          <div class="section-title"><h3><Icon name="comment" :size="17" />Atualizações</h3><span class="count-pill">{{ task.feed.length }}</span></div>
          <p class="hint">Responsáveis e quem criou a tarefa recebem um aviso a cada atualização.</p>
          <form v-if="app.canEdit" class="comment-box" @submit.prevent="sendComment">
            <Avatar :name="app.user.name" :id="app.user.id" size="sm" />
            <textarea v-model="comment" rows="2" maxlength="5000" placeholder="Escreva uma atualização para a equipe…" aria-label="Novo comentário"
              @keydown.enter.ctrl.prevent="sendComment" @keydown.enter.meta.prevent="sendComment"></textarea>
            <button class="btn btn-primary btn-sm" :disabled="!comment.trim() || posting"><Icon name="send" :size="15" />Enviar</button>
          </form>
          <ol class="feed">
            <li v-for="item in task.feed" :key="item.id" :class="item.kind === 'comment' ? 'feed-comment' : 'feed-event'">
              <template v-if="item.kind === 'comment'">
                <Avatar :name="item.actor_name" :id="item.actor_id" size="sm" />
                <div class="bubble">
                  <div class="bubble-head"><strong>{{ item.actor_name }}</strong><small>{{ dateTime(item.created_at) }}</small>
                    <button v-if="item.actor_id === app.user.id || app.role === 'OWNER'" type="button" class="icon-btn" aria-label="Excluir comentário" @click="deleteComment(item)"><Icon name="trash" :size="14" /></button>
                  </div>
                  <p>{{ item.detail }}</p>
                </div>
              </template>
              <template v-else>
                <span class="feed-icon"><Icon :name="feedIcon(item.action)" :size="14" /></span>
                <span><strong>{{ item.actor_name }}</strong> {{ taskFeedText(item) }}<small>{{ dateTime(item.created_at) }}</small></span>
              </template>
            </li>
          </ol>
        </section>
      </div>

      <aside class="task-side">
        <label>Status
          <select v-model="draft.columnId" :disabled="!app.canEdit"><option v-for="c in columns" :key="c.id" :value="c.id">{{ c.name }}{{ c.is_done ? ' (conclusão)' : '' }}</option></select>
        </label>
        <div class="side-field">
          <span class="field-label">Responsáveis</span>
          <MemberPicker v-model="draft.assigneeIds" :members="members" :disabled="!app.canEdit" />
        </div>
        <label>Prazo
          <input v-model="draft.dueAt" type="datetime-local" :disabled="!app.canEdit" />
          <small v-if="overdue" class="danger-text">Prazo vencido</small>
        </label>
        <div class="side-field">
          <span class="field-label">Prioridade</span>
          <div class="segmented segmented-full priority-picker">
            <button v-for="[value, label] in PRIORITIES" :key="value" type="button" :class="[{ active: draft.priority === value }, value.toLowerCase()]" :disabled="!app.canEdit" @click="draft.priority = value"><span class="priority-dot" :class="value.toLowerCase()"></span>{{ label }}</button>
          </div>
        </div>
        <div class="side-field">
          <span class="field-label">Notas vinculadas</span>
          <div v-for="n in task.notes" :key="n.id" class="side-link">
            <router-link :to="`/notes/${n.id}`" @click="emit('close')"><Icon name="notes" :size="15" />{{ n.title }}</router-link>
            <button v-if="app.canEdit" type="button" class="icon-btn" :aria-label="`Desvincular ${n.title}`" @click="unlinkNote(n)"><Icon name="unlink" :size="15" /></button>
          </div>
          <form v-if="app.canEdit && linkable.length" class="inline-add" @submit.prevent="linkNote">
            <select v-model="noteId" aria-label="Nota para vincular"><option value="">Vincular nota…</option><option v-for="n in linkable" :key="n.id" :value="n.id">{{ n.title }}</option></select>
            <button class="btn btn-ghost btn-sm" :disabled="!noteId">Vincular</button>
          </form>
        </div>
        <div class="side-field">
          <span class="field-label">Anexos</span>
          <div v-for="(f, i) in task.files" :key="f.id" class="side-link">
            <button type="button" class="link-button" @click="preview = i"><Icon :name="kindIcon[fileKind(f.mime, f.name)]" :size="15" />{{ f.name }}<small>{{ formatSize(f.size_bytes) }}</small></button>
            <button v-if="app.canEdit" type="button" class="icon-btn" :aria-label="`Remover anexo ${f.name}`" @click="detach(f)"><Icon name="unlink" :size="15" /></button>
          </div>
          <label v-if="app.canEdit" class="btn btn-ghost btn-sm file-label"><Icon name="attach" :size="16" />{{ uploading ? `Enviando ${Math.round(uploading * 100)}%` : 'Anexar arquivo' }}<input type="file" :accept="acceptTypes()" :disabled="!!uploading" @change="upload" /></label>
          <div v-if="uploading" class="progress-track"><div :style="{ width: `${Math.round(uploading * 100)}%` }"></div></div>
        </div>
      </aside>
    </div>
    <template #footer>
      <button v-if="app.canEdit && task" type="button" class="btn btn-ghost danger-text footer-start" @click="remove"><Icon name="trash" :size="16" />Excluir</button>
      <span v-if="dirty" class="unsaved">Alterações não salvas</span>
      <button type="button" class="btn btn-ghost" @click="close">Fechar</button>
      <button v-if="app.canEdit" type="button" class="btn btn-primary" :disabled="busy || !dirty" @click="save">{{ busy ? 'Salvando…' : 'Salvar' }}</button>
    </template>
  </Modal>
  <FilePreview v-if="preview !== null && task" :files="task.files" :index="preview" @close="preview = null" />
</template>
