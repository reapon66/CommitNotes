import { reactive } from 'vue'
import { api, ApiError } from './api'

export const uploadLimits = reactive({ maxBytes: 20 * 1024 * 1024, extensions: ['.pdf', '.docx', '.txt', '.png', '.jpg', '.jpeg', '.webp'] })
let loading = null
export function loadUploadLimits() {
  loading ||= api('/files/limits').then(limits => Object.assign(uploadLimits, limits)).catch(() => { loading = null })
  return loading
}
export const acceptTypes = () => uploadLimits.extensions.join(',')

const sizeFormat = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 })
export function formatSize(bytes = 0) {
  const value = Number(bytes)
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${sizeFormat.format(value / 1024)} KB`
  return `${sizeFormat.format(value / 1024 / 1024)} MB`
}

/** Checks a file before uploading, so oversized or unsupported files never leave the browser. */
export function validateUpload(file, limits = uploadLimits) {
  const extension = file.name.includes('.') ? `.${file.name.split('.').pop().toLowerCase()}` : ''
  if (!limits.extensions.includes(extension)) return `${file.name}: formato não permitido. Use PDF, DOCX, TXT, PNG, JPG ou WEBP.`
  if (!file.size) return `${file.name} está vazio.`
  if (file.size > limits.maxBytes) return `${file.name} tem ${formatSize(file.size)}; o limite por arquivo é ${formatSize(limits.maxBytes)}.`
  return ''
}

/** Multipart upload with progress (fetch cannot report upload progress). */
export function uploadFile(projectId, file, { noteId, taskId, onProgress } = {}) {
  return new Promise((resolve, reject) => {
    const error = validateUpload(file)
    if (error) return reject(new ApiError(error, 400))
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `/api/projects/${projectId}/files${noteId ? `?noteId=${noteId}` : taskId ? `?taskId=${taskId}` : ''}`)
    xhr.withCredentials = true
    xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest')
    xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress?.(event.loaded / event.total) }
    xhr.onload = () => {
      let data = {}
      try { data = JSON.parse(xhr.responseText) } catch (_) {}
      if (xhr.status >= 200 && xhr.status < 300) resolve(data)
      else reject(new ApiError(data.error || (xhr.status === 413 ? `Arquivo acima do limite de ${formatSize(uploadLimits.maxBytes)}` : `Erro ${xhr.status}`), xhr.status))
    }
    xhr.onerror = () => reject(new ApiError('Falha de conexão durante o envio', 0))
    const form = new FormData(); form.append('file', file); xhr.send(form)
  })
}

export function fileKind(mime = '', name = '') {
  if (mime.startsWith('image/')) return 'image'
  if (mime === 'application/pdf') return 'pdf'
  if (mime === 'text/plain') return 'text'
  if (mime.includes('wordprocessingml') || name.toLowerCase().endsWith('.docx')) return 'docx'
  return 'other'
}
export const kindIcon = { image: 'file-image', pdf: 'file-text', text: 'file-text', docx: 'file-doc', other: 'file' }
export const kindLabel = { image: 'Imagem', pdf: 'PDF', text: 'Texto', docx: 'Word', other: 'Arquivo' }
export const fileUrl = (id, download = false) => `/api/files/${id}/content${download ? '?download=true' : ''}`
