<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, body, relativeTime } from '../api'
import { useApp } from '../store'
import Avatar from './Avatar.vue'
import Icon from './Icon.vue'

const app = useApp(), router = useRouter()
const open = ref(false), items = ref([]), unread = ref(0), root = ref(null)
let timer

async function load() {
  if (!app.user || document.hidden) return
  try { const data = await api('/notifications'); items.value = data.items; unread.value = data.unread } catch (_) {}
}
async function markAll() {
  try { const data = await api('/notifications/read', { method: 'POST', body: body({ all: true }) }); items.value = data.items; unread.value = data.unread } catch (e) { app.fail(e) }
}
async function openItem(item) {
  open.value = false
  if (!item.read_at) {
    item.read_at = new Date().toISOString(); unread.value = Math.max(0, unread.value - 1)
    api('/notifications/read', { method: 'POST', body: body({ ids: [item.id] }) }).catch(() => {})
  }
  if (item.project_id && item.project_id !== app.projectId && app.projects.some(p => p.id === item.project_id)) app.selectProject(item.project_id)
  router.push(item.url)
}
function outside(event) { if (!root.value?.contains(event.target)) open.value = false }
watch(open, value => { if (value) { load(); document.addEventListener('pointerdown', outside) } else document.removeEventListener('pointerdown', outside) })
watch(() => app.user?.id, load)
onMounted(() => { load(); timer = setInterval(load, 20000); document.addEventListener('visibilitychange', load) })
onBeforeUnmount(() => { clearInterval(timer); document.removeEventListener('visibilitychange', load); document.removeEventListener('pointerdown', outside) })
</script>

<template>
  <div ref="root" class="notify" @keydown.esc="open = false">
    <button type="button" class="icon-btn" :aria-expanded="open" aria-haspopup="dialog" :aria-label="unread ? `Notificações, ${unread} não lidas` : 'Notificações'" title="Notificações" @click="open = !open">
      <Icon name="bell" :size="19" /><span v-if="unread" class="badge">{{ unread > 99 ? '99+' : unread }}</span>
    </button>
    <section v-if="open" class="notify-panel" role="dialog" aria-label="Notificações">
      <header>
        <h2>Notificações</h2>
        <button v-if="unread" type="button" class="text-link" @click="markAll"><Icon name="check-all" :size="15" />Marcar todas como lidas</button>
        <button type="button" class="icon-btn notify-close" aria-label="Fechar notificações" @click="open = false"><Icon name="close" /></button>
      </header>
      <ul>
        <li v-for="item in items" :key="item.id">
          <button type="button" class="notify-item" :class="{ unread: !item.read_at }" @click="openItem(item)">
            <Avatar :name="item.actor_name || 'CommitNotes'" size="sm" />
            <span class="notify-text">
              <strong>{{ item.title }}</strong>
              <span>{{ item.body }}</span>
              <small>{{ item.project_name ? `${item.project_name} · ` : '' }}{{ relativeTime(item.created_at) }}</small>
            </span>
            <span v-if="!item.read_at" class="unread-dot" aria-label="Não lida"></span>
          </button>
        </li>
      </ul>
      <div v-if="!items.length" class="empty-inline"><Icon name="bell" :size="26" /><p>Você será avisado aqui quando alguém atualizar tarefas em que você está envolvido.</p></div>
    </section>
  </div>
</template>
