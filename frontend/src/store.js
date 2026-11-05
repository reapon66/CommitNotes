import { defineStore } from 'pinia'
import { api, ApiError, body } from './api'

const stored = key => { try { return localStorage.getItem(key) } catch (_) { return null } }
const store = (key, value) => { try { localStorage.setItem(key, value) } catch (_) {} }

export const useApp = defineStore('app', {
  state: () => ({ user: null, projects: [], projectId: null, loading: true, offline: false, toast: '', error: '', sidebarCollapsed: stored('cn_sidebar') === 'collapsed' }),
  getters: { project: s => s.projects.find(p => p.id === s.projectId), role: s => s.projects.find(p => p.id === s.projectId)?.role, canEdit() { return this.role === 'OWNER' || this.role === 'EDITOR' } },
  actions: {
    async bootstrap() {
      // A network failure (offline PWA start) is not the same as being signed out.
      try { this.user = await api('/auth/me'); await this.loadProjects(); this.offline = false } catch (e) { this.user = null; this.offline = !(e instanceof ApiError) }
      this.loading = false; this.applyTheme()
    },
    async loadProjects() {
      this.projects = await api('/projects')
      const saved = stored('cn_project')
      if (!this.projects.some(p => p.id === this.projectId)) this.projectId = this.projects.find(p => p.id === saved)?.id || this.projects[0]?.id || null
    },
    selectProject(id) { this.projectId = id; store('cn_project', id) },
    toggleSidebar() { this.sidebarCollapsed = !this.sidebarCollapsed; store('cn_sidebar', this.sidebarCollapsed ? 'collapsed' : 'expanded') },
    applyTheme() {
      const dark = this.user?.theme === 'dark'
      document.documentElement.dataset.theme = dark ? 'dark' : 'light'
      document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#1d2232' : '#ffffff')
      const accent = this.user?.accent || '#5368d9'
      const rgb = [1, 3, 5].map(i => parseInt(accent.slice(i, i + 2), 16))
      const luminance = values => values.map(v => { const c = v / 255; return c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4 }).reduce((sum, v, i) => sum + v * [.2126, .7152, .0722][i], 0)
      const lum = luminance(rgb)
      const onAccent = lum > .179 ? '#111827' : '#ffffff'
      const target = dark ? [255, 255, 255] : [0, 0, 0]
      const backgroundLum = dark ? luminance([29, 34, 50]) : 1
      let ink = rgb
      for (let i = 0; i < 20; i++) {
        const l = luminance(ink), contrast = (Math.max(l, backgroundLum) + .05) / (Math.min(l, backgroundLum) + .05)
        if (contrast >= 4.5) break
        ink = ink.map((v, n) => Math.round(v * .88 + target[n] * .12))
      }
      document.documentElement.style.setProperty('--accent', accent)
      document.documentElement.style.setProperty('--on-accent', onAccent)
      document.documentElement.style.setProperty('--accent-text', `rgb(${ink.join(',')})`)
    },
    async preferences(theme, accent) { this.user = await api('/auth/preferences', { method: 'PATCH', body: body({ theme, accent }) }); this.applyTheme() },
    flash(message) { this.toast = message; setTimeout(() => { if (this.toast === message) this.toast = '' }, 5000) },
    fail(error) { this.error = error.message || 'Ocorreu um erro'; setTimeout(() => { this.error = '' }, 7000) },
    async logout() {
      // On a shared browser, the next person must not receive the previous account's notifications.
      try {
        const subscription = await (await navigator.serviceWorker?.getRegistration())?.pushManager?.getSubscription()
        if (subscription) { await api('/push/subscriptions', { method: 'DELETE', body: body({ endpoint: subscription.endpoint }) }); await subscription.unsubscribe() }
      } catch (_) {}
      await api('/auth/logout', { method: 'POST' }); this.user = null; this.projects = []; this.projectId = null
    }
  }
})
