<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, body } from '../api'
import { useApp } from '../store'
import Icon from '../components/Icon.vue'
const route=useRoute(), router=useRouter(), app=useApp()
const mode=computed(() => route.path.slice(1)), name=ref(''), email=ref(''), password=ref(''), token=ref(route.query.token || ''), error=ref(''), message=ref(''), busy=ref(false)
async function submit() {
  error.value=''; message.value=''; busy.value=true
  try {
    if(mode.value==='forgot') { await api('/auth/forgot',{method:'POST',body:body({email:email.value})}); message.value='Se a conta existir, enviaremos um link de recuperação.' }
    else if(mode.value==='reset') { await api('/auth/reset',{method:'POST',body:body({token:token.value,password:password.value})}); message.value='Senha atualizada. Entre com a nova senha.'; setTimeout(() => router.push('/login'),1200) }
    else { app.user=await api(`/auth/${mode.value}`,{method:'POST',body:body({name:name.value,email:email.value,password:password.value})}); await app.loadProjects(); app.applyTheme(); router.push('/') }
  } catch(e) { error.value=e.message } finally { busy.value=false }
}
</script>
<template><div class="auth-card"><div class="auth-logo"><span class="brand-icon"><Icon name="check" :size="20" :stroke="3" /></span><span>Commit<strong>Notes</strong></span></div><h1>{{ mode==='register'?'Crie sua conta':mode==='forgot'?'Recupere seu acesso':mode==='reset'?'Defina uma nova senha':'Boas-vindas de volta' }}</h1><p class="muted">Um lugar para conectar ideias, arquivos e trabalho em equipe.</p>
  <form @submit.prevent="submit"><label v-if="mode==='register'">Nome<input v-model="name" required maxlength="120" autocomplete="name" placeholder="Seu nome" /></label><label v-if="mode==='login'||mode==='register'||mode==='forgot'">E-mail<input v-model="email" type="email" required autocomplete="email" placeholder="voce@exemplo.com" /></label><label v-if="mode==='reset'">Token de recuperação<input v-model="token" required /></label><label v-if="mode!=='forgot'">Senha<input v-model="password" type="password" required minlength="12" maxlength="128" :autocomplete="mode==='login'?'current-password':'new-password'" placeholder="Mínimo de 12 caracteres" /></label><p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="message" class="form-success" role="status">{{ message }}</p><button class="btn btn-primary btn-block" :disabled="busy">{{ busy?'Aguarde…':mode==='register'?'Criar conta':mode==='forgot'?'Enviar link':mode==='reset'?'Redefinir senha':'Entrar' }}</button></form>
  <div class="auth-links"><router-link v-if="mode==='login'" to="/forgot">Esqueceu a senha?</router-link><router-link :to="mode==='login'?'/register':'/login'">{{ mode==='login'?'Criar uma conta':'Voltar para login' }}</router-link></div></div></template>
