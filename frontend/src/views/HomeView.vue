<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api, body, dateTime } from '../api'
import { activityDetail, activityLabel } from '../labels'
import { useApp } from '../store'
import Icon from '../components/Icon.vue'
import Modal from '../components/Modal.vue'

const app = useApp(), name = ref(''), description = ref(''), creating = ref(false), notes = ref([]), tags = ref([]), schedule = ref([]), activity = ref([]), summary = ref(null)
const upcoming = computed(() => schedule.value.filter(t => t.due_at && !t.completed_at && new Date(t.due_at) > Date.now() - 86400000).slice(0, 5))
const tagById = computed(() => new Map(tags.value.map(t => [t.id, t])))
const maxTag = computed(() => Math.max(1, ...(summary.value?.tags || []).map(t => Number(t.notes)), Number(summary.value?.untagged_notes || 0)))
const late = t => new Date(t.due_at) < Date.now()
async function load() {
  if (!app.projectId) return
  try {
    ;[notes.value, tags.value, schedule.value, activity.value, summary.value] = await Promise.all([api(`/projects/${app.projectId}/notes`), api(`/projects/${app.projectId}/tags`),
      api(`/projects/${app.projectId}/schedule`), api(`/projects/${app.projectId}/timeline`), api(`/projects/${app.projectId}/summary`)])
  } catch (e) { app.fail(e) }
}
async function create() {
  try {
    const p = await api('/projects', { method: 'POST', body: body({ name: name.value, description: description.value }) })
    await app.loadProjects(); app.selectProject(p.id); creating.value = false; name.value = ''; description.value = ''; await load(); app.flash('Projeto criado')
  } catch (e) { app.fail(e) }
}
watch(() => app.projectId, load); onMounted(load)
</script>

<template>
  <div class="page-head">
    <div><div class="eyebrow">SEU ESPAÇO DE TRABALHO</div><h1>{{ app.project ? app.project.name : 'Comece um novo projeto' }}</h1><p class="muted">{{ app.project?.description || 'Organize o conhecimento e mantenha sua equipe em movimento.' }}</p></div>
    <button class="btn btn-primary" @click="creating = true"><Icon name="plus" />Novo projeto</button>
  </div>
  <Modal v-if="creating" title="Novo projeto" size="sm" form @close="creating = false" @submit="create">
    <label>Nome<input v-model="name" required maxlength="120" autofocus /></label>
    <label>Descrição<textarea v-model="description" maxlength="2000" rows="3" /></label>
    <template #footer><button type="button" class="btn btn-ghost" @click="creating = false">Cancelar</button><button class="btn btn-primary">Criar projeto</button></template>
  </Modal>
  <div v-if="!app.project" class="empty-hero"><div class="empty-icon"><Icon name="dashboard" :size="30" /></div><h2>Seu próximo projeto começa aqui</h2><p>Crie um projeto para reunir notas, tarefas, arquivos e pessoas.</p><button class="btn btn-primary" @click="creating = true"><Icon name="plus" />Criar primeiro projeto</button></div>
  <template v-else>
    <div class="stat-grid">
      <router-link to="/notes" class="stat-card"><span><Icon name="notes" :size="16" />Notas</span><strong>{{ summary?.notes ?? notes.length }}</strong><small>Ideias documentadas<Icon name="arrow-right" :size="14" /></small></router-link>
      <router-link to="/board" class="stat-card"><span><Icon name="kanban" :size="16" />Tarefas abertas</span><strong>{{ summary?.open_tasks ?? '–' }}</strong><small>{{ summary?.my_open_tasks || 0 }} com você<Icon name="arrow-right" :size="14" /></small></router-link>
      <router-link to="/timeline" class="stat-card"><span><Icon name="done" :size="16" />Concluídas</span><strong>{{ summary?.done_tasks ?? '–' }}</strong><small>Histórico de entregas<Icon name="arrow-right" :size="14" /></small></router-link>
      <router-link to="/files" class="stat-card"><span><Icon name="files" :size="16" />Arquivos</span><strong>{{ summary?.files ?? '–' }}</strong><small>Biblioteca do projeto<Icon name="arrow-right" :size="14" /></small></router-link>
    </div>
    <div class="two-col">
      <section class="panel">
        <div class="panel-head"><h2>Notas por etiqueta</h2><router-link to="/notes?view=overview" class="text-link"><Icon name="overview" :size="15" />Visão geral das notas</router-link></div>
        <div v-if="!summary?.tags?.length" class="empty-inline">Crie etiquetas em Notas &gt; Organizar para categorizar o conhecimento do projeto.</div>
        <router-link v-for="t in summary?.tags || []" :key="t.id" :to="`/notes?tag=${t.id}`" class="tag-bar" :style="{ '--chip': t.color }">
          <span class="tag-bar-label"><span class="legend-dot"></span>{{ t.name }}</span>
          <span class="tag-bar-track"><span :style="{ width: `${Math.max(4, 100 * Number(t.notes) / maxTag)}%` }"></span></span>
          <strong>{{ t.notes }}</strong>
        </router-link>
        <div v-if="summary?.tags?.length && Number(summary.untagged_notes)" class="tag-bar untagged" :style="{ '--chip': '#94a3b8' }">
          <span class="tag-bar-label"><span class="legend-dot"></span>Sem etiqueta</span>
          <span class="tag-bar-track"><span :style="{ width: `${Math.max(4, 100 * Number(summary.untagged_notes) / maxTag)}%` }"></span></span>
          <strong>{{ summary.untagged_notes }}</strong>
        </div>
      </section>
      <section class="panel">
        <div class="panel-head"><h2>Próximos prazos</h2><router-link to="/timeline" class="text-link">Ver agenda<Icon name="arrow-right" :size="15" /></router-link></div>
        <div v-if="!upcoming.length" class="empty-inline">Nenhum prazo próximo.</div>
        <router-link v-for="t in upcoming" :key="t.id" :to="`/board?task=${t.id}`" class="list-row">
          <span class="row-icon" :class="{ late: late(t) }"><Icon name="due" :size="18" /></span>
          <span><strong>{{ t.title }}</strong><small>{{ dateTime(t.due_at) }} · {{ t.assignee_names || 'Sem responsáveis' }}</small></span>
          <Icon name="chevron-right" :size="16" class="row-arrow" />
        </router-link>
      </section>
    </div>
    <div class="two-col">
      <section class="panel">
        <div class="panel-head"><h2>Notas recentes</h2><router-link to="/notes" class="text-link">Ver todas<Icon name="arrow-right" :size="15" /></router-link></div>
        <div v-if="!notes.length" class="empty-inline">Ainda não há notas neste projeto.</div>
        <router-link v-for="n in notes.slice(0, 5)" :key="n.id" :to="`/notes/${n.id}`" class="list-row">
          <span class="row-icon"><Icon :name="n.favorite ? 'star' : 'notes'" :size="18" /></span>
          <span><strong>{{ n.title }}</strong><small>Atualizada {{ dateTime(n.updated_at) }}</small></span>
          <span class="tag-dots"><span v-for="id in n.tag_ids.slice(0, 4)" :key="id" class="legend-dot" :style="{ '--chip': tagById.get(id)?.color }" :title="tagById.get(id)?.name"></span></span>
        </router-link>
      </section>
      <section class="panel">
        <div class="panel-head"><h2>Atividade recente</h2><router-link to="/timeline" class="text-link">Linha do tempo<Icon name="arrow-right" :size="15" /></router-link></div>
        <div v-if="!activity.length" class="empty-inline">A atividade do projeto aparecerá aqui.</div>
        <div v-for="a in activity.slice(0, 6)" :key="a.id" class="activity-row"><span class="activity-dot"></span><span><strong>{{ a.actor_name }}</strong> · {{ activityLabel(a) }} <span v-if="activityDetail(a)" class="muted">· {{ activityDetail(a) }}</span></span><small>{{ dateTime(a.created_at) }}</small></div>
      </section>
    </div>
  </template>
</template>
