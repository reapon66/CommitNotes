import { reactive } from 'vue'

/** Installable web app state: install prompt, offline status and service worker registration. */
export const pwa = reactive({ installable: false, installed: false, offline: typeof navigator !== 'undefined' && navigator.onLine === false, ios: false })
let deferredPrompt = null

export function setupPwa() {
  pwa.installed = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true
  pwa.ios = /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  window.addEventListener('beforeinstallprompt', event => { event.preventDefault(); deferredPrompt = event; pwa.installable = true })
  window.addEventListener('appinstalled', () => { deferredPrompt = null; pwa.installable = false; pwa.installed = true })
  window.addEventListener('online', () => { pwa.offline = false })
  window.addEventListener('offline', () => { pwa.offline = true })
  if ('serviceWorker' in navigator && import.meta.env.PROD) {
    const register = () => navigator.serviceWorker.register('/sw.js').then(registration => {
      setInterval(() => registration.update().catch(() => {}), 60 * 60 * 1000)
    }).catch(() => {})
    if (document.readyState === 'complete') register(); else window.addEventListener('load', register, { once: true })
  }
}

export async function promptInstall() {
  if (!deferredPrompt) return 'unavailable'
  deferredPrompt.prompt()
  const { outcome } = await deferredPrompt.userChoice
  deferredPrompt = null; pwa.installable = false
  return outcome
}
