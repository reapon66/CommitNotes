<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, dateTime } from '../api'
import { fileKind, fileUrl, formatSize, kindIcon, kindLabel } from '../files'
import Icon from './Icon.vue'
import Modal from './Modal.vue'

const props = defineProps({ files: { type: Array, required: true }, index: { type: Number, default: 0 } })
const emit = defineEmits(['close'])
const current = ref(props.index), status = ref('loading'), message = ref(''), text = ref(''), zoomed = ref(false), pages = ref(0), shownPages = ref(0), refs = ref(null)
const canvasHost = ref(null), docxHost = ref(null)
const file = computed(() => props.files[current.value])
const kind = computed(() => fileKind(file.value?.mime, file.value?.name))
const nativePdf = typeof navigator !== 'undefined' && navigator.pdfViewerEnabled && matchMedia('(pointer: fine)').matches
const MAX_PDF_PAGES = 40
let session = 0

async function load() {
  const id = ++session, item = file.value
  status.value = 'loading'; message.value = ''; text.value = ''; zoomed.value = false; pages.value = 0; shownPages.value = 0; refs.value = null
  api(`/files/${item.id}`).then(data => { if (id === session) refs.value = data }).catch(() => {})
  try {
    if (kind.value === 'text') {
      const response = await fetch(fileUrl(item.id), { credentials: 'include' })
      if (!response.ok) throw new Error('Não foi possível carregar o arquivo')
      const content = await response.text()
      if (id === session) { text.value = content.length > 300000 ? `${content.slice(0, 300000)}\n\n[Prévia limitada aos primeiros 300 mil caracteres]` : content; status.value = 'ready' }
    } else if (kind.value === 'pdf' && !nativePdf) {
      await nextTick(); await renderPdf(item, id)
    } else if (kind.value === 'docx') {
      await nextTick(); await renderDocx(item, id)
    } else status.value = kind.value === 'other' ? 'unsupported' : 'ready'
  } catch (error) {
    if (id === session) { status.value = 'error'; message.value = error.message || 'Não foi possível gerar a prévia' }
  }
}
async function renderPdf(item, id) {
  const [pdfjs, worker] = await Promise.all([import('pdfjs-dist'), import('pdfjs-dist/build/pdf.worker.min.mjs?url')])
  pdfjs.GlobalWorkerOptions.workerSrc = worker.default
  const task = pdfjs.getDocument({ url: fileUrl(item.id), withCredentials: true })
  try {
    const doc = await task.promise
    if (id !== session) return
    pages.value = doc.numPages; status.value = 'ready'; await nextTick()
    // Each preview renders into its own container, so a slower previous file can never draw over the current one.
    const host = canvasHost.value, pagesBox = document.createElement('div'); host.replaceChildren(pagesBox)
    const width = Math.max(240, host.clientWidth - 2), ratio = Math.min(window.devicePixelRatio || 1, 2)
    for (let number = 1; number <= Math.min(doc.numPages, MAX_PDF_PAGES) && id === session; number++) {
      const page = await doc.getPage(number), base = page.getViewport({ scale: 1 })
      const viewport = page.getViewport({ scale: width / base.width * ratio })
      const canvas = document.createElement('canvas')
      canvas.width = Math.floor(viewport.width); canvas.height = Math.floor(viewport.height); canvas.className = 'pdf-page'
      canvas.setAttribute('aria-label', `Página ${number}`)
      pagesBox.appendChild(canvas)
      await page.render({ canvas, viewport }).promise
      if (id === session) shownPages.value = number
    }
  } finally { task.destroy() }
}
async function renderDocx(item, id) {
  const [{ renderAsync }, response] = await Promise.all([import('docx-preview'), fetch(fileUrl(item.id), { credentials: 'include' })])
  if (!response.ok) throw new Error('Não foi possível carregar o arquivo')
  const blob = await response.blob()
  if (id !== session) return
  // The document's own CSS stays inside a shadow root, so a crafted .docx cannot restyle the application.
  const content = document.createElement('div'), styles = document.createElement('div')
  await renderAsync(blob, content, styles, { className: 'docx', inWrapper: true, breakPages: true, useBase64URL: true, renderAltChunks: false, renderComments: false, renderChanges: false, experimental: false })
  if (id !== session) return
  content.querySelectorAll('a[href]').forEach(link => {
    const href = link.getAttribute('href')
    if (/^https?:/i.test(href)) { link.target = '_blank'; link.rel = 'noopener noreferrer' }
    else if (!/^(mailto:|#)/i.test(href)) link.removeAttribute('href')
  })
  status.value = 'ready'; await nextTick()
  const host = docxHost.value, root = host.shadowRoot || host.attachShadow({ mode: 'open' })
  root.replaceChildren(styles, content)
  const page = root.querySelector('section.docx'), wrapper = root.querySelector('.docx-wrapper')
  if (page && wrapper && page.offsetWidth > host.clientWidth) wrapper.style.zoom = String(host.clientWidth / (page.offsetWidth + 40))
}
function go(step) { if (props.files.length > 1) current.value = (current.value + step + props.files.length) % props.files.length }
function onKey(event) {
  if (event.target.closest?.('input,textarea,select')) return
  if (event.key === 'ArrowRight') go(1)
  if (event.key === 'ArrowLeft') go(-1)
}
watch(current, load)
onMounted(() => { load(); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => { session++; window.removeEventListener('keydown', onKey) })
</script>

<template>
  <Modal :title="file?.name || 'Arquivo'" size="xl" panel-class="file-preview" @close="emit('close')">
    <template #header>
      <div class="preview-title">
        <span class="file-icon" :class="`kind-${kind}`"><Icon :name="kindIcon[kind]" /></span>
        <div>
          <h2>{{ file.name }}</h2>
          <small>{{ kindLabel[kind] }} · {{ formatSize(file.size_bytes) }}<template v-if="file.uploaded_by_name || refs?.uploaded_by_name"> · {{ file.uploaded_by_name || refs.uploaded_by_name }}</template><template v-if="file.created_at"> · {{ dateTime(file.created_at) }}</template></small>
        </div>
      </div>
      <div class="preview-actions">
        <template v-if="files.length > 1">
          <button class="icon-btn" type="button" aria-label="Arquivo anterior" @click="go(-1)"><Icon name="chevron-left" /></button>
          <span class="count-pill">{{ current + 1 }}/{{ files.length }}</span>
          <button class="icon-btn" type="button" aria-label="Próximo arquivo" @click="go(1)"><Icon name="chevron-right" /></button>
        </template>
        <a class="icon-btn" :href="fileUrl(file.id)" target="_blank" rel="noopener" title="Abrir em nova aba" aria-label="Abrir em nova aba"><Icon name="external" /></a>
        <a class="icon-btn" :href="fileUrl(file.id, true)" title="Baixar" aria-label="Baixar arquivo"><Icon name="download" /></a>
      </div>
    </template>

    <div class="preview-stage" :class="`stage-${kind}`">
      <div v-if="status === 'loading'" class="preview-state"><Icon name="loading" class="spin" :size="28" /><span>Gerando prévia…</span></div>
      <div v-else-if="status === 'error'" class="preview-state"><Icon name="alert" :size="28" /><span>{{ message }}</span><a class="btn btn-ghost btn-sm" :href="fileUrl(file.id, true)">Baixar arquivo</a></div>
      <div v-else-if="status === 'unsupported'" class="preview-state"><Icon :name="kindIcon[kind]" :size="36" /><span>Prévia indisponível para este formato.</span><a class="btn btn-primary btn-sm" :href="fileUrl(file.id, true)"><Icon name="download" />Baixar</a></div>
      <template v-if="status !== 'error'">
        <button v-if="kind === 'image'" type="button" class="image-frame" :class="{ zoomed }" :aria-label="zoomed ? 'Ajustar à tela' : 'Ver no tamanho original'" @click="zoomed = !zoomed">
          <img :src="fileUrl(file.id)" :alt="file.name" @load="status = 'ready'" @error="status = 'error'; message = 'Imagem indisponível'" />
        </button>
        <iframe v-else-if="kind === 'pdf' && nativePdf" :src="fileUrl(file.id)" :title="file.name" @load="status = 'ready'"></iframe>
        <div v-else-if="kind === 'pdf'" class="pdf-pages">
          <div ref="canvasHost"></div>
          <p v-if="pages > MAX_PDF_PAGES && shownPages === MAX_PDF_PAGES" class="hint">Mostrando {{ MAX_PDF_PAGES }} de {{ pages }} páginas. <a :href="fileUrl(file.id)" target="_blank" rel="noopener">Abra o PDF completo</a>.</p>
        </div>
        <pre v-else-if="kind === 'text' && status === 'ready'" class="text-preview">{{ text }}</pre>
        <div v-else-if="kind === 'docx'" ref="docxHost" class="docx-host"></div>
      </template>
    </div>

    <footer v-if="refs?.notes?.length || refs?.tasks?.length" class="preview-refs">
      <span class="muted">Usado em</span>
      <router-link v-for="n in refs.notes" :key="n.id" :to="`/notes/${n.id}`" class="ref-chip" @click="emit('close')"><Icon name="notes" :size="14" />{{ n.title }}</router-link>
      <router-link v-for="t in refs.tasks" :key="t.id" :to="`/board?task=${t.id}`" class="ref-chip" @click="emit('close')"><Icon name="kanban" :size="14" />{{ t.title }}</router-link>
    </footer>
  </Modal>
</template>
