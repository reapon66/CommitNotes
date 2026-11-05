<script setup>
import { computed, onMounted, ref } from 'vue'
import { api, body, dateTime } from '../api'
import { useApp } from '../store'
import Avatar from '../components/Avatar.vue'
import Icon from '../components/Icon.vue'
const app=useApp(),users=ref([]),audit=ref([]),name=ref(''),email=ref(''),password=ref(''),query=ref('')
const visible=computed(()=>users.value.filter(u=>`${u.name} ${u.email}`.toLowerCase().includes(query.value.toLowerCase())))
async function load(){try{[users.value,audit.value]=await Promise.all([api('/admin/users'),api('/admin/audit')])}catch(e){app.fail(e)}}
async function create(){try{users.value=await api('/admin/users',{method:'POST',body:body({name:name.value,email:email.value,password:password.value})});name.value='';email.value='';password.value='';await load();app.flash('Conta criada')}catch(e){app.fail(e)}}
async function update(user,field,value){if(field==='active'&&!value&&!confirm(`Suspender ${user.name}?`))return;try{users.value=await api(`/admin/users/${user.id}`,{method:'PATCH',body:body({[field]:value})});await load();app.flash('Conta atualizada')}catch(e){app.fail(e)}}
onMounted(load)
</script>
<template>
  <div class="page-head"><div><div class="eyebrow">ADMINISTRAÇÃO DA PLATAFORMA</div><h1>Painel DEV</h1><p class="muted">Gerencie contas globais e consulte ações administrativas. Notas privadas permanecem restritas aos membros dos projetos.</p></div></div>
  <div class="two-col">
    <form class="panel form-stack" @submit.prevent="create"><h2>Criar conta</h2><label>Nome<input v-model="name" maxlength="120" required/></label><label>E-mail<input v-model="email" type="email" required/></label><label>Senha inicial<input v-model="password" type="password" minlength="12" maxlength="128" required autocomplete="new-password"/></label><button class="btn btn-primary"><Icon name="user-plus" :size="16"/>Criar conta</button></form>
    <section class="panel"><h2>Registros administrativos</h2><div class="audit-list"><div v-for="a in audit" :key="a.id" class="audit-row"><strong>{{ a.admin_name }}</strong> · {{ a.action }}<small>{{ dateTime(a.created_at) }} · {{ a.target_user_id }}</small></div><div v-if="!audit.length" class="empty-inline">Nenhuma ação registrada.</div></div></section>
  </div>
  <section class="panel admin-users">
    <div class="panel-head"><h2>Contas</h2><label class="search-field"><Icon name="search" :size="16"/><span class="sr-only">Buscar contas</span><input v-model="query" type="search" placeholder="Buscar por nome ou e-mail"/></label></div>
    <div class="admin-table-wrap"><table class="responsive-table"><thead><tr><th>Pessoa</th><th>Status</th><th>Acesso global</th><th>Desde</th><th>Ações</th></tr></thead>
      <tbody><tr v-for="u in visible" :key="u.id">
        <td data-label="Pessoa"><span class="table-person"><Avatar :name="u.name" :id="u.id" size="sm"/><span><strong>{{ u.name }}</strong><small>{{ u.email }}</small></span></span></td>
        <td data-label="Status"><span class="status" :class="u.active?'good':'bad'">{{ u.active?'Ativa':'Suspensa' }}</span></td>
        <td data-label="Acesso global">{{ u.platform_admin?'Administrador':'Usuário' }}</td>
        <td data-label="Desde">{{ dateTime(u.created_at) }}</td>
        <td data-label="Ações"><div class="head-actions"><button class="btn btn-ghost btn-sm" @click="update(u,'active',!u.active)">{{ u.active?'Suspender':'Ativar' }}</button><button class="btn btn-ghost btn-sm" @click="update(u,'platformAdmin',!u.platform_admin)">{{ u.platform_admin?'Remover admin':'Tornar admin' }}</button></div></td>
      </tr></tbody></table></div>
  </section>
</template>
