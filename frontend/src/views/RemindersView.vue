<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, body, dateTime, isoInput } from '../api'
import { useApp } from '../store'
import Icon from '../components/Icon.vue'

const app = useApp(), route = useRoute()
const reminders = ref([]), notes = ref([]), title = ref(''), description = ref(''), dueAt = ref(''), noteId = ref(''), projectId = ref(''), pushState = ref(''), busy = ref(false), loaded = ref(false)
const focus = computed(() => typeof route.query.focus === 'string' ? route.query.focus : '')
const pending = computed(() => reminders.value.filter(r => !r.completed_at).sort((a, b) => new Date(a.due_at) - new Date(b.due_at)))
const previous = computed(() => reminders.value.filter(r => r.completed_at))
const missing = computed(() => loaded.value && focus.value && !reminders.value.some(r => r.id === focus.value))
async function load() {
  try { reminders.value = await api('/reminders'); notes.value = app.projectId ? await api(`/projects/${app.projectId}/notes`) : []; loaded.value = true; reveal() } catch (e) { app.fail(e) }
}
function reveal() { if (focus.value) nextTick(() => document.getElementById(`reminder-${focus.value}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })) }
async function create() {
  busy.value = true
  try {
    await api('/reminders', { method: 'POST', body: body({ title: title.value, description: description.value, dueAt: isoInput(dueAt.value), projectId: projectId.value || null, noteId: noteId.value || null }) })
    title.value = ''; description.value = ''; dueAt.value = ''; noteId.value = ''; await load(); app.flash('Lembrete criado')
  } catch (e) { app.fail(e) } finally { busy.value = false }
}
async function act(id, action, minutes) { try { await api(`/reminders/${id}`, { method: 'PATCH', body: body({ action, minutes }) }); await load(); app.flash(action === 'complete' ? 'Lembrete concluído' : 'Lembrete adiado') } catch (e) { app.fail(e) } }
async function remove(id) { if (!confirm('Excluir este lembrete?')) return; try { await api(`/reminders/${id}`, { method: 'DELETE' }); await load() } catch (e) { app.fail(e) } }
function urlBase64ToUint8Array(base64) { const padding = '='.repeat((4 - base64.length % 4) % 4), raw = atob((base64 + padding).replace(/-/g, '+').replace(/_/g, '/')); return Uint8Array.from([...raw].map(c => c.charCodeAt(0))) }
async function enablePush() {
  try {
    if (!('serviceWorker' in navigator) || !('PushManager' in window) || !window.isSecureContext) throw new Error('Notificações push exigem HTTPS ou localhost e navegador compatível. No iPhone, instale o app na tela de início primeiro.')
    const { publicKey } = await api('/push/key')
    if (!publicKey) throw new Error('Push ainda não foi configurado no servidor.')
    const permission = await Notification.requestPermission()
    if (permission !== 'granted') throw new Error('Permissão de notificações não concedida.')
    await navigator.serviceWorker.register('/sw.js')
    const registration = await navigator.serviceWorker.ready
    const subscription = await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: urlBase64ToUint8Array(publicKey) })
    const json = subscription.toJSON()
    await api('/push/subscriptions', { method: 'POST', body: body({ endpoint: json.endpoint, p256dh: json.keys.p256dh, auth: json.keys.auth }) })
    pushState.value = 'Notificações ativadas neste navegador. Você receberá lembretes e atualizações das suas tarefas.'; app.flash('Notificações ativadas')
  } catch (e) { pushState.value = e.message; app.fail(e) }
}
watch(focus, reveal)
onMounted(load)
</script>

<template>
  <div class="page-head">
    <div><div class="eyebrow">NO SEU TEMPO</div><h1>Lembretes</h1><p class="muted">Avisos rápidos, separados das tarefas do Kanban.</p></div>
    <button class="btn btn-ghost" @click="enablePush"><Icon name="bell" />Ativar notificações</button>
  </div>
  <p v-if="pushState" class="hint">{{ pushState }}</p>
  <p v-if="missing" class="notice"><Icon name="info" :size="16" />Este lembrete pertence a outra pessoa ou foi excluído. Lembretes são pessoais.</p>
  <div class="two-col reminder-columns">
    <form class="panel reminder-form" @submit.prevent="create">
      <h2>Novo lembrete</h2>
      <label>Título<input v-model="title" required maxlength="200" placeholder="O que você precisa lembrar?" /></label>
      <label>Descrição (opcional)<textarea v-model="description" maxlength="1000" rows="3" placeholder="Um detalhe para ajudar…"></textarea></label>
      <label>Data e hora<input v-model="dueAt" type="datetime-local" required /></label>
      <label>Projeto (opcional)<select v-model="projectId" @change="noteId = ''"><option value="">Pessoal</option><option v-for="p in app.projects" :key="p.id" :value="p.id">{{ p.name }}</option></select></label>
      <label v-if="projectId === app.projectId && notes.length">Nota (opcional)<select v-model="noteId"><option value="">Nenhuma</option><option v-for="n in notes" :key="n.id" :value="n.id">{{ n.title }}</option></select></label>
      <button class="btn btn-primary" :disabled="busy"><Icon name="plus" />Criar lembrete</button>
    </form>
    <section class="panel">
      <h2>Em aberto <span class="count-pill">{{ pending.length }}</span></h2>
      <div v-if="!pending.length" class="empty-inline">Você não tem lembretes em aberto.</div>
      <article v-for="r in pending" :id="`reminder-${r.id}`" :key="r.id" class="reminder-row" :class="{ focused: focus === r.id, overdue: new Date(r.due_at) < Date.now() }">
        <span class="row-icon"><Icon name="alarm" :size="18" /></span>
        <div class="reminder-text">
          <strong>{{ r.title }}</strong>
          <p v-if="r.description">{{ r.description }}</p>
          <small>{{ dateTime(r.due_at) }}<span v-if="r.project_name"> · {{ r.project_name }}</span><router-link v-if="r.note_id" :to="`/notes/${r.note_id}`"> · <Icon name="notes" :size="12" />{{ r.note_title || 'Nota' }}</router-link></small>
        </div>
        <div class="head-actions">
          <button class="btn btn-ghost btn-sm" @click="act(r.id, 'snooze', 10)">Adiar 10 min</button>
          <button class="btn btn-primary btn-sm" @click="act(r.id, 'complete')"><Icon name="check" :size="15" />Concluir</button>
          <button class="icon-btn danger-text" :aria-label="`Excluir ${r.title}`" @click="remove(r.id)"><Icon name="trash" :size="16" /></button>
        </div>
      </article>
    </section>
  </div>
  <section class="panel history-section">
    <h2>Anteriores</h2>
    <div v-if="!previous.length" class="empty-inline">Os lembretes concluídos aparecerão aqui.</div>
    <div v-for="r in previous" :id="`reminder-${r.id}`" :key="r.id" class="past-row" :class="{ focused: focus === r.id }">
      <span class="past-text"><Icon name="done" :size="16" class="ok-icon" /><span><strong>{{ r.title }}</strong><small>{{ dateTime(r.due_at) }} · concluído {{ dateTime(r.completed_at) }}</small></span></span>
      <button class="icon-btn danger-text" :aria-label="`Excluir ${r.title}`" @click="remove(r.id)"><Icon name="trash" :size="16" /></button>
    </div>
  </section>
</template>
