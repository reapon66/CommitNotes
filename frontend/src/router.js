import { createRouter, createWebHistory } from 'vue-router'
import AuthView from './views/AuthView.vue'
import HomeView from './views/HomeView.vue'
import { useApp } from './store'

const routes = [
  { path: '/login', component: AuthView }, { path: '/register', component: AuthView },
  { path: '/forgot', component: AuthView }, { path: '/reset', component: AuthView },
  { path: '/', component: HomeView }, { path: '/notes/:id?', component: () => import('./views/NotesView.vue') },
  { path: '/files', component: () => import('./views/FilesView.vue') }, { path: '/reminders', component: () => import('./views/RemindersView.vue') },
  { path: '/board', component: () => import('./views/BoardView.vue') }, { path: '/timeline', component: () => import('./views/TimelineView.vue') },
  { path: '/project', component: () => import('./views/ProjectView.vue') }, { path: '/dev', component: () => import('./views/AdminView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]
export const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach(async to => {
  const app = useApp()
  if (app.loading) await app.bootstrap()
  if (app.offline) return
  const publicPath = ['/login', '/register', '/forgot', '/reset'].includes(to.path)
  if (!app.user && !publicPath) return '/login'
  if (app.user && publicPath && to.path !== '/reset') return '/'
  if (to.path === '/dev' && !app.user?.platform_admin) return '/'
})
// After a deploy, an open tab may request a page chunk that no longer exists: reload once to get the new version.
router.onError((error, to) => {
  if (!/dynamically imported module|Importing a module script failed|Failed to fetch/i.test(error?.message || '')) return
  try { if (sessionStorage.getItem('cn_chunk_reload')) return; sessionStorage.setItem('cn_chunk_reload', '1') } catch (_) { return }
  location.assign(to.fullPath)
})
router.afterEach(() => { try { sessionStorage.removeItem('cn_chunk_reload') } catch (_) {} })
