<script setup>
import { computed, ref } from 'vue'
import { api, body } from '../api'
import { useApp } from '../store'
import ColorSwatches from './ColorSwatches.vue'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const props = defineProps({ boardId: { type: String, required: true }, column: { type: Object, default: null }, columns: { type: Array, default: () => [] }, taskCount: { type: Number, default: 0 } })
const emit = defineEmits(['close', 'saved'])
const app = useApp()
const SUGGESTIONS = ['A fazer', 'Em andamento', 'Em revisão', 'Bloqueado', 'Concluído']
const name = ref(props.column?.name || ''), color = ref(props.column?.color || '#3b82f6'), isDone = ref(!!props.column?.is_done)
const position = ref(props.columns.findIndex(c => c.id === props.column?.id)), moveTo = ref(''), busy = ref(false), confirmDelete = ref(false)
const targets = computed(() => props.columns.filter(c => c.id !== props.column?.id))
const suggestions = computed(() => SUGGESTIONS.filter(s => !props.columns.some(c => c.name.toLowerCase() === s.toLowerCase())))

async function save() {
  if (!name.value.trim()) return
  busy.value = true
  try {
    const payload = { name: name.value, color: color.value, isDone: isDone.value }
    if (props.column && position.value !== props.columns.findIndex(c => c.id === props.column.id)) payload.position = position.value
    const view = props.column
      ? await api(`/columns/${props.column.id}`, { method: 'PATCH', body: body(payload) })
      : await api(`/boards/${props.boardId}/columns`, { method: 'POST', body: body(payload) })
    emit('saved', view); app.flash(props.column ? 'Coluna atualizada' : 'Coluna criada'); emit('close')
  } catch (e) { app.fail(e) } finally { busy.value = false }
}
async function remove() {
  if (props.taskCount && !moveTo.value) return
  busy.value = true
  try {
    const view = await api(`/columns/${props.column.id}${props.taskCount ? `?moveTo=${moveTo.value}` : ''}`, { method: 'DELETE' })
    emit('saved', view); app.flash('Coluna excluída'); emit('close')
  } catch (e) { app.fail(e) } finally { busy.value = false }
}
</script>

<template>
  <Modal :title="column ? 'Editar coluna' : 'Nova coluna'" size="sm" form panel-class="column-editor" @close="emit('close')" @submit="save">
    <div class="column-preview" :style="{ '--chip': color }">
      <span class="column-bar"></span>
      <strong>{{ name || 'Nome da coluna' }}</strong>
      <span v-if="isDone" class="status good"><Icon name="done" :size="13" />Conclusão</span>
      <span class="count-pill">{{ taskCount }}</span>
    </div>
    <label>Nome<input v-model="name" maxlength="80" required autofocus placeholder="Ex.: Em revisão" /></label>
    <div v-if="!column && suggestions.length" class="suggestions">
      <button v-for="s in suggestions" :key="s" type="button" class="ref-chip" @click="name = s; isDone = s === 'Concluído'; color = s === 'Concluído' ? '#22c55e' : color">{{ s }}</button>
    </div>
    <div class="side-field"><span class="field-label">Cor</span><ColorSwatches v-model="color" label="Cor da coluna" /></div>
    <label class="switch-line">
      <input v-model="isDone" type="checkbox" role="switch" />
      <span class="switch" aria-hidden="true"></span>
      <span><strong>Coluna de conclusão</strong><small>Tarefas que entram aqui são marcadas como concluídas.</small></span>
    </label>
    <label v-if="column && columns.length > 1">Posição
      <select v-model.number="position"><option v-for="(c, i) in columns" :key="c.id" :value="i">{{ i + 1 }}ª posição{{ c.id === column.id ? ' (atual)' : ` · ${i < columns.findIndex(x => x.id === column.id) ? 'antes de' : 'depois de'} ${c.name}` }}</option></select>
    </label>
    <div v-if="column" class="danger-zone">
      <button v-if="!confirmDelete" type="button" class="text-link danger-text" @click="confirmDelete = true"><Icon name="trash" :size="15" />Excluir coluna</button>
      <template v-else>
        <p v-if="taskCount" class="hint">Esta coluna tem {{ taskCount }} tarefa(s). Escolha para onde movê-las:</p>
        <p v-else class="hint">A coluna está vazia e será excluída.</p>
        <div class="inline-add">
          <select v-if="taskCount" v-model="moveTo" aria-label="Coluna que receberá as tarefas"><option value="">Mover tarefas para…</option><option v-for="c in targets" :key="c.id" :value="c.id">{{ c.name }}</option></select>
          <button type="button" class="btn btn-ghost btn-sm danger-text" :disabled="busy || (taskCount && !moveTo)" @click="remove">Confirmar exclusão</button>
        </div>
      </template>
    </div>
    <template #footer>
      <button type="button" class="btn btn-ghost" @click="emit('close')">Cancelar</button>
      <button class="btn btn-primary" :disabled="busy || !name.trim()">{{ column ? 'Salvar coluna' : 'Criar coluna' }}</button>
    </template>
  </Modal>
</template>
