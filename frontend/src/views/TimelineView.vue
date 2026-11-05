<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, dateTime, isoInput } from '../api'
import { activityDetail, activityLabel } from '../labels'
import { useApp } from '../store'
import Icon from '../components/Icon.vue'

const app = useApp(), activity = ref([]), schedule = ref([]), members = ref([]), person = ref(''), from = ref(''), to = ref(''), tab = ref('activity')
const ICONS = { note: 'notes', task: 'kanban', file: 'files', member: 'users', folder: 'folder', tag: 'tag', board: 'kanban', column: 'columns', project: 'settings' }
const grouped = computed(() => {
  const groups = {}
  for (const item of tab.value === 'activity' ? activity.value : schedule.value) {
    const key = new Date(tab.value === 'activity' ? item.created_at : item.due_at).toLocaleDateString('pt-BR', { dateStyle: 'long' })
    ;(groups[key] ||= []).push(item)
  }
  return groups
})
async function load() {
  if (!app.projectId) return
  try {
    members.value = await api(`/projects/${app.projectId}/members`)
    const q = new URLSearchParams()
    if (person.value) q.set('person', person.value)
    if (from.value) q.set('from', isoInput(from.value))
    if (to.value) q.set('to', isoInput(to.value))
    ;[activity.value, schedule.value] = await Promise.all([api(`/projects/${app.projectId}/timeline?${q}`), api(`/projects/${app.projectId}/schedule?${q}`)])
  } catch (e) { app.fail(e) }
}
watch([() => app.projectId, person, from, to], load); onMounted(load)
</script>

<template>
  <div class="page-head"><div><div class="eyebrow">TUDO EM CONTEXTO</div><h1>Linha do tempo</h1><p class="muted">Veja o que mudou e acompanhe os próximos prazos.</p></div></div>
  <div v-if="!app.projectId" class="empty-inline">Crie um projeto para visualizar atividades.</div>
  <template v-else>
    <div class="timeline-filters panel">
      <div class="segmented"><button :class="{ active: tab === 'activity' }" @click="tab = 'activity'"><Icon name="timeline" :size="15" />Atividades</button><button :class="{ active: tab === 'schedule' }" @click="tab = 'schedule'"><Icon name="due" :size="15" />Prazos</button></div>
      <label>Pessoa<select v-model="person"><option value="">Todas as pessoas</option><option v-for="m in members" :key="m.id" :value="m.id">{{ m.name }}</option></select></label>
      <label>De<input v-model="from" type="datetime-local" /></label>
      <label>Até<input v-model="to" type="datetime-local" /></label>
    </div>
    <div v-if="!Object.keys(grouped).length" class="panel empty-inline">Nenhum registro no período selecionado.</div>
    <section v-for="(items, date) in grouped" :key="date" class="timeline-day">
      <h2>{{ date }}</h2>
      <template v-for="item in items" :key="item.id">
        <div v-if="tab === 'activity'" class="timeline-item panel">
          <span class="timeline-icon"><Icon :name="ICONS[item.entity_type] || 'timeline'" :size="18" /></span>
          <div><strong>{{ item.actor_name }}</strong> {{ activityLabel(item) }}<p v-if="activityDetail(item)">{{ activityDetail(item) }}</p><small>{{ dateTime(item.created_at) }}</small></div>
        </div>
        <router-link v-else :to="`/board?task=${item.id}`" class="timeline-item panel">
          <span class="timeline-icon" :class="{ late: !item.completed_at && new Date(item.due_at) < Date.now() }"><Icon :name="item.completed_at ? 'done' : 'due'" :size="18" /></span>
          <div><strong>{{ item.title }}</strong><p>{{ item.board_name }} · {{ item.column_name }} · {{ item.assignee_names || 'Sem responsáveis' }}</p><small>{{ dateTime(item.due_at) }} · {{ item.completed_at ? 'Concluída' : 'Em aberto' }}</small></div>
        </router-link>
      </template>
    </section>
  </template>
</template>
