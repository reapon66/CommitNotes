export class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status }
}
export async function api(path, options = {}) {
  const headers = { 'X-Requested-With': 'XMLHttpRequest', ...options.headers }
  if (options.body && !(options.body instanceof FormData)) headers['Content-Type'] = 'application/json'
  const response = await fetch(`/api${path}`, { credentials: 'include', ...options, headers })
  if (!response.ok) {
    let data = {}
    try { data = await response.json() } catch (_) {}
    throw new ApiError(data.error || `Erro ${response.status}`, response.status)
  }
  const type = response.headers.get('content-type') || ''
  return type.includes('application/json') ? response.json() : response.blob()
}
export const body = value => JSON.stringify(value)
export function dateInput(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const local = new Date(d.getTime() - d.getTimezoneOffset() * 60000)
  return local.toISOString().slice(0, 16)
}
export const isoInput = value => value ? new Date(value).toISOString() : null
export const dateTime = iso => iso ? new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(iso)) : '—'
const relative = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' })
export function relativeTime(iso, now = Date.now()) {
  const minutes = Math.round((new Date(iso).getTime() - now) / 60000)
  if (Math.abs(minutes) < 1) return 'agora'
  if (Math.abs(minutes) < 60) return relative.format(minutes, 'minute')
  if (Math.abs(minutes) < 60 * 24) return relative.format(Math.round(minutes / 60), 'hour')
  if (Math.abs(minutes) < 60 * 24 * 7) return relative.format(Math.round(minutes / 1440), 'day')
  return dateTime(iso)
}
export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a'); link.href = url; link.download = filename; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
