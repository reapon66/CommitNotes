<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useApp } from './store'
import { api, body, dateTime } from './api'
import { promptInstall, pwa } from './pwa'
import Avatar from './components/Avatar.vue'
import Icon from './components/Icon.vue'
import NotificationBell from './components/NotificationBell.vue'

const app = useApp(), route = useRoute(), router = useRouter()
const nav = [['/', 'Visão geral', 'dashboard'], ['/notes', 'Notas', 'notes'], ['/board', 'Kanban', 'kanban'], ['/files', 'Arquivos', 'files'], ['/reminders', 'Lembretes', 'alarm'], ['/timeline', 'Linha do tempo', 'timeline'], ['/project', 'Projeto', 'settings']]
const title = computed(() => route.path === '/dev' ? 'Painel DEV' : nav.find(n => n[0] === (route.path.startsWith('/notes') ? '/notes' : route.path))?.[1] || 'CommitNotes')
const roleName = computed(() => ({ OWNER: 'Proprietário', EDITOR: 'Editor', READER: 'Leitor' })[app.role] || '')
const active = path => path === '/' ? route.path === '/' : route.path.startsWith(path)
const due = ref([]), hidden = ref(new Set()), menu = ref(false)
let timer
async function checkDue() {
  if (!app.user || document.hidden) return
  try { due.value = (await api('/reminders/due')).filter(r => !hidden.value.has(r.id)) } catch (_) {}
}
async function act(reminder, action, minutes) {
  try { await api(`/reminders/${reminder.id}`, { method: 'PATCH', body: body({ action, minutes }) }); due.value = due.value.filter(r => r.id !== reminder.id); app.flash(action === 'complete' ? 'Lembrete concluído' : 'Lembrete adiado') }
  catch (e) { app.fail(e) }
}
function dismiss(reminder) { hidden.value = new Set([...hidden.value, reminder.id]); due.value = due.value.filter(r => r.id !== reminder.id) }
async function logout() { try { await app.logout(); router.push('/login') } catch (e) { app.fail(e) } }
async function install() { if (await promptInstall() === 'accepted') app.flash('Aplicativo instalado') }
function onKey(event) { if (event.key === 'Escape' && menu.value) menu.value = false }
async function retry() { app.loading = true; await app.bootstrap(); if (!app.offline) router.replace(app.user ? route.fullPath : '/login') }
watch(() => app.user?.id, () => checkDue())
watch(() => route.fullPath, () => { menu.value = false })
onMounted(() => { checkDue(); timer = setInterval(checkDue, 15000); window.addEventListener('keydown', onKey) })
onUnmounted(() => { clearInterval(timer); window.removeEventListener('keydown', onKey) })
</script>

<template>
  <div v-if="!app.user && app.offline" class="auth-layout">
    <div class="auth-card offline-card"><span class="empty-icon"><Icon name="offline" :size="28" /></span><h1>Sem conexão</h1><p class="muted">Não foi possível falar com o servidor. Verifique a internet e tente novamente.</p><button class="btn btn-primary btn-block" :disabled="app.loading" @click="retry"><Icon name="refresh" :size="16" />Tentar novamente</button></div>
  </div>
  <div v-else-if="!app.user" class="auth-layout"><router-view /></div>
  <div v-else class="app-layout" :class="{ 'sidebar-collapsed': app.sidebarCollapsed }">
    <aside class="sidebar" :class="{ open: menu }" aria-label="Menu lateral">
      <div class="brand-row">
        <router-link to="/" class="brand" aria-label="CommitNotes, visão geral"><span class="brand-icon"><Icon name="check" :size="20" :stroke="3" /></span><span class="brand-text">Commit<span class="brand-light">Notes</span></span></router-link>
        <button type="button" class="icon-btn sidebar-toggle" :aria-label="app.sidebarCollapsed ? 'Expandir menu lateral' : 'Recolher menu lateral'" :title="app.sidebarCollapsed ? 'Expandir menu' : 'Recolher menu'" @click="app.toggleSidebar()">
          <Icon :name="app.sidebarCollapsed ? 'sidebar-open' : 'sidebar-close'" />
        </button>
        <button type="button" class="icon-btn menu-close" aria-label="Fechar menu" @click="menu = false"><Icon name="close" /></button>
      </div>
      <div class="side-section">ÁREA DE TRABALHO</div>
      <div class="project-switch-wrap">
        <button v-if="app.sidebarCollapsed" type="button" class="project-initial" :title="app.project?.name || 'Projeto'" :aria-label="`Projeto ${app.project?.name || ''}. Expandir menu para trocar`" @click="app.toggleSidebar()">{{ (app.project?.name || '?').charAt(0).toUpperCase() }}</button>
        <label class="sr-only" for="project-switch">Projeto ativo</label>
        <select id="project-switch" class="project-switch" :value="app.projectId || ''" @change="app.selectProject($event.target.value); router.push('/'); menu = false">
          <option v-if="!app.projects.length" value="">Sem projetos</option><option v-for="p in app.projects" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </div>
      <nav aria-label="Navegação principal" class="side-nav">
        <router-link v-for="[path, label, icon] in nav" :key="path" :to="path" :class="{ active: active(path) }" :title="app.sidebarCollapsed ? label : undefined" :aria-current="active(path) ? 'page' : undefined">
          <Icon :name="icon" :size="19" /><span class="nav-label">{{ label }}</span>
        </router-link>
      </nav>
      <div class="sidebar-bottom">
        <button v-if="pwa.installable" type="button" class="admin-link" title="Instalar aplicativo" @click="install"><Icon name="install" :size="19" /><span class="nav-label">Instalar aplicativo</span></button>
        <router-link v-if="app.user.platform_admin" to="/dev" class="admin-link" title="Painel DEV"><Icon name="admin" :size="19" /><span class="nav-label">Painel DEV</span></router-link>
        <div class="user-mini">
          <Avatar :name="app.user.name" :id="app.user.id" />
          <span class="user-text"><strong>{{ app.user.name }}</strong><small>{{ app.user.email }}</small></span>
          <button type="button" class="icon-btn" title="Sair" aria-label="Sair" @click="logout"><Icon name="logout" /></button>
        </div>
      </div>
    </aside>
    <div v-if="menu" class="menu-backdrop" @click="menu = false"></div>
    <div class="main-area">
      <div v-if="pwa.offline" class="offline-banner" role="status"><Icon name="offline" :size="16" />Sem conexão com a internet. Você pode navegar, mas as alterações só serão salvas quando a conexão voltar.</div>
      <header class="topbar">
        <button type="button" class="icon-btn menu-toggle" aria-label="Abrir menu" @click="menu = true"><Icon name="menu" /></button>
        <div class="breadcrumb"><span class="crumb-project">{{ app.project?.name || 'Área de trabalho' }}</span><Icon name="chevron-right" :size="14" /><strong>{{ title }}</strong></div>
        <div class="top-right">
          <span v-if="roleName" class="role-pill">{{ roleName }}</span>
          <router-link to="/reminders" class="icon-btn" :aria-label="due.length ? `Lembretes, ${due.length} no horário` : 'Lembretes'" title="Lembretes"><Icon name="alarm" :size="19" /><span v-if="due.length" class="badge">{{ due.length }}</span></router-link>
          <NotificationBell />
        </div>
      </header>
      <main class="content" :class="{ 'content-wide': route.path === '/board' || route.query.view === 'overview' }"><router-view /></main>
    </div>
    <div class="feedback" aria-live="polite"><div v-if="app.toast" class="toast success"><Icon name="done" :size="16" />{{ app.toast }}</div><div v-if="app.error" class="toast error" role="alert"><Icon name="alert" :size="16" />{{ app.error }}</div></div>
    <div v-if="due.length" class="reminder-popups" role="region" aria-label="Lembretes no horário">
      <article v-for="r in due.slice(0, 3)" :key="r.id" class="reminder-popup">
        <div class="popup-top"><span class="popup-icon"><Icon name="alarm" :size="18" /></span><strong>{{ r.title }}</strong><button type="button" class="icon-btn" :aria-label="`Fechar ${r.title}`" @click="dismiss(r)"><Icon name="close" :size="16" /></button></div>
        <p v-if="r.description">{{ r.description }}</p><small>{{ dateTime(r.due_at) }}</small>
        <div class="popup-actions"><button type="button" class="btn btn-ghost btn-sm" @click="act(r, 'snooze', 10)">Adiar 10 min</button><button type="button" class="btn btn-primary btn-sm" @click="act(r, 'complete')">Concluir</button></div>
      </article>
    </div>
  </div>
</template>
