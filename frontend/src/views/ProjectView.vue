<script setup>
import { onMounted, ref, watch } from 'vue'
import { api, body } from '../api'
import { promptInstall, pwa } from '../pwa'
import { useApp } from '../store'
import Avatar from '../components/Avatar.vue'
import Icon from '../components/Icon.vue'

const app = useApp(), members = ref([]), name = ref(''), description = ref(''), email = ref(''), role = ref('EDITOR'), theme = ref('light'), accent = ref('#5368d9')
async function load() {
  theme.value = app.user?.theme || 'light'; accent.value = app.user?.accent || '#5368d9'
  if (!app.projectId) return
  try { members.value = await api(`/projects/${app.projectId}/members`); name.value = app.project?.name || ''; description.value = app.project?.description || '' } catch (e) { app.fail(e) }
}
async function saveProject() { try { await api(`/projects/${app.projectId}`, { method: 'PATCH', body: body({ name: name.value, description: description.value }) }); await app.loadProjects(); app.flash('Projeto atualizado') } catch (e) { app.fail(e) } }
async function invite() { try { members.value = await api(`/projects/${app.projectId}/members`, { method: 'POST', body: body({ email: email.value, role: role.value }) }); email.value = ''; app.flash('Pessoa adicionada ao projeto') } catch (e) { app.fail(e) } }
async function changeRole(m) { try { members.value = await api(`/projects/${app.projectId}/members/${m.id}`, { method: 'PATCH', body: body({ role: m.role }) }); app.flash('Permissão atualizada') } catch (e) { app.fail(e); await load() } }
async function remove(m) { if (!confirm(`Remover ${m.name} do projeto? As tarefas deixarão de tê-lo como responsável.`)) return; try { members.value = await api(`/projects/${app.projectId}/members/${m.id}`, { method: 'DELETE' }); app.flash('Pessoa removida') } catch (e) { app.fail(e) } }
async function savePrefs() { try { await app.preferences(theme.value, accent.value); app.flash('Preferências salvas') } catch (e) { app.fail(e) } }
async function install() { if (await promptInstall() === 'accepted') app.flash('Aplicativo instalado') }
watch(() => app.projectId, load); onMounted(load)
</script>

<template>
  <div class="page-head"><div><div class="eyebrow">CONFIGURAÇÕES</div><h1>Projeto e preferências</h1><p class="muted">Controle quem participa e adapte a interface.</p></div></div>
  <div class="two-col">
    <section class="panel">
      <h2>Aparência</h2>
      <p class="muted">As preferências são salvas na sua conta.</p>
      <div class="settings-fields">
        <label>Tema<select v-model="theme"><option value="light">Claro</option><option value="dark">Escuro</option></select></label>
        <label>Cor principal<input v-model="accent" type="color" /></label>
      </div>
      <button class="btn btn-primary" @click="savePrefs">Salvar aparência</button>
    </section>
    <section v-if="app.project" class="panel">
      <h2>Sobre o projeto</h2>
      <div class="form-stack">
        <label>Nome<input v-model="name" :disabled="app.role !== 'OWNER'" maxlength="120" /></label>
        <label>Descrição<textarea v-model="description" :disabled="app.role !== 'OWNER'" maxlength="2000" rows="3"></textarea></label>
      </div>
      <button v-if="app.role === 'OWNER'" class="btn btn-primary" @click="saveProject">Salvar projeto</button>
    </section>
  </div>
  <section class="panel app-panel">
    <div class="app-panel-icon"><img src="/icons/icon-192.png" alt="" width="56" height="56" /></div>
    <div>
      <h2>Aplicativo CommitNotes</h2>
      <p v-if="pwa.installed" class="muted">O CommitNotes já está instalado neste dispositivo.</p>
      <p v-else-if="pwa.installable" class="muted">Instale para abrir em janela própria, pela tela inicial ou barra de tarefas, e receber notificações como um app.</p>
      <p v-else-if="pwa.ios" class="muted">No iPhone ou iPad: toque em <strong>Compartilhar</strong> e depois em <strong>Adicionar à Tela de Início</strong>. Depois, abra pelo ícone para ativar as notificações.</p>
      <p v-else class="muted">Use o menu do navegador (Instalar aplicativo / Adicionar à tela inicial) para instalar o CommitNotes. É necessário acessar pelo endereço HTTPS.</p>
    </div>
    <button v-if="pwa.installable" class="btn btn-primary" @click="install"><Icon name="install" />Instalar</button>
  </section>
  <section v-if="app.project" class="panel members-panel">
    <div class="panel-head"><div><h2>Pessoas e permissões</h2><p class="muted">Proprietários gerenciam o acesso. Editores alteram conteúdo; leitores consultam.</p></div></div>
    <form v-if="app.role === 'OWNER'" class="member-invite" @submit.prevent="invite">
      <input v-model="email" type="email" required placeholder="E-mail de uma conta existente" aria-label="E-mail da pessoa" />
      <select v-model="role" aria-label="Perfil"><option value="EDITOR">Editor</option><option value="READER">Leitor</option></select>
      <button class="btn btn-primary"><Icon name="user-plus" :size="16" />Adicionar</button>
    </form>
    <div v-for="m in members" :key="m.id" class="member-row">
      <Avatar :name="m.name" :id="m.id" />
      <span class="member-name"><strong>{{ m.name }}</strong><small>{{ m.email }}</small></span>
      <span v-if="m.role === 'OWNER'" class="role-pill">Proprietário</span>
      <template v-else-if="app.role === 'OWNER'">
        <select v-model="m.role" :aria-label="`Permissão de ${m.name}`" @change="changeRole(m)"><option value="EDITOR">Editor</option><option value="READER">Leitor</option></select>
        <button class="icon-btn danger-text" :aria-label="`Remover ${m.name}`" @click="remove(m)"><Icon name="trash" :size="16" /></button>
      </template>
      <span v-else class="role-pill">{{ m.role === 'EDITOR' ? 'Editor' : 'Leitor' }}</span>
    </div>
  </section>
</template>
