/* Service worker template: vite.config.js replaces the two placeholders when it emits /sw.js. */
const VERSION = '__SW_VERSION__'
const PRECACHE = __SW_PRECACHE__
const SHELL = `commitnotes-shell-${VERSION}`
const RUNTIME = 'commitnotes-runtime'
const FONTS = 'commitnotes-fonts'

self.addEventListener('install', event => {
  self.skipWaiting()
  if (PRECACHE) event.waitUntil(caches.open(SHELL).then(cache => cache.addAll(PRECACHE)))
})
self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const keys = await caches.keys()
    await Promise.all(keys.filter(key => (key.startsWith('commitnotes-shell-') && key !== SHELL) || key === RUNTIME).map(key => caches.delete(key)))
    await self.clients.claim()
  })())
})

self.addEventListener('fetch', event => {
  const request = event.request
  if (!PRECACHE || request.method !== 'GET') return
  const url = new URL(request.url)
  if (url.origin === self.location.origin) {
    if (url.pathname.startsWith('/api/') || url.pathname === '/healthz' || url.pathname === '/sw.js') return
    if (request.mode === 'navigate') event.respondWith(page(request))
    else if (/^\/(assets|icons)\/|\.(svg|png|webmanifest)$/.test(url.pathname)) event.respondWith(cacheFirst(request))
  } else if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com') {
    event.respondWith(staleWhileRevalidate(request))
  }
})

// Pages always try the network first, so a new deploy is picked up immediately; offline, the cached app shell opens.
async function page(request) {
  try { return await fetch(request) } catch (_) { return (await caches.match('/index.html', { ignoreVary: true })) || Response.error() }
}
// Build files are content-hashed, so a cached copy is always valid regardless of Vary headers.
async function cacheFirst(request) {
  const cached = await caches.match(request, { ignoreVary: true })
  if (cached) return cached
  const response = await fetch(request)
  if (response.ok) (await caches.open(RUNTIME)).put(request, response.clone())
  return response
}
async function staleWhileRevalidate(request) {
  const cache = await caches.open(FONTS), cached = await cache.match(request)
  const network = fetch(request).then(response => { if (response.ok || response.type === 'opaque') cache.put(request, response.clone()); return response }).catch(() => cached || Response.error())
  return cached || network
}

self.addEventListener('push', event => {
  let data = {}
  try { data = event.data ? event.data.json() : {} } catch (_) {}
  event.waitUntil(self.registration.showNotification(data.title || 'CommitNotes', {
    body: data.body || 'Você tem uma nova atualização.', icon: '/icons/icon-192.png', badge: '/icons/badge-96.png',
    tag: data.tag || `commitnotes-${data.id || Date.now()}`, renotify: !!data.tag, data: { url: data.url || '/' }
  }))
})
self.addEventListener('notificationclick', event => {
  event.notification.close()
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(windows => {
    const target = new URL(event.notification.data?.url || '/', self.location.origin).href
    const open = windows.find(w => new URL(w.url).origin === self.location.origin)
    if (open) return open.focus().then(() => open.navigate(target))
    return self.clients.openWindow(target)
  }))
})
