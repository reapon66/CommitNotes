import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const PUBLIC_SHELL = ['/index.html', '/manifest.webmanifest', '/icon.svg', '/icons/icon-192.png', '/icons/icon-512.png', '/icons/maskable-512.png', '/icons/apple-touch-icon.png', '/icons/badge-96.png']
// Large viewers (PDF, DOCX) are only cached after their first use instead of being downloaded on install.
const ON_DEMAND = /pdfjs-dist|docx-preview|jszip/

/** Emits /sw.js from src/sw.js with the list of built files to precache for offline use. */
function serviceWorker() {
  const source = () => readFileSync(new URL('./src/sw.js', import.meta.url), 'utf8')
  const render = (version, precache) => source().replace('__SW_VERSION__', version).replace('__SW_PRECACHE__', JSON.stringify(precache))
  return {
    name: 'commitnotes-service-worker',
    configureServer(server) {
      server.middlewares.use('/sw.js', (_, res) => { res.setHeader('Content-Type', 'text/javascript'); res.end(render('dev', null)) })
    },
    generateBundle(_, bundle) {
      const built = Object.values(bundle)
        .filter(item => item.fileName !== 'index.html' && !item.fileName.endsWith('.map') && !/pdf\.worker/.test(item.fileName))
        .filter(item => !(item.type === 'chunk' && item.isDynamicEntry && item.moduleIds.some(id => ON_DEMAND.test(id))))
        .map(item => `/${item.fileName}`)
      const precache = [...PUBLIC_SHELL, ...built]
      const version = createHash('sha256').update(precache.join('\n') + source()).digest('hex').slice(0, 12)
      this.emitFile({ type: 'asset', fileName: 'sw.js', source: render(version, precache) })
    }
  }
}

export default defineConfig({
  plugins: [vue(), serviceWorker()],
  server: { proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } },
  test: { environment: 'node' }
})
