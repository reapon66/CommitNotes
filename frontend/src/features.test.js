import { describe, expect, it } from 'vitest'
import { relativeTime } from './api'
import { fileKind, formatSize, validateUpload } from './files'
import { bounds, initLayout, runLayout } from './graphLayout'
import { activityDetail, taskFeedText } from './labels'
import { insertText, markdownToHtml, plainText, prefixLines, wikilink, wrapText } from './markdown'

const NOTE = '3f1c2b9a-1111-4a5b-9c7d-0123456789ab', FILE = '7d2e4f60-2222-4b6c-8d9e-abcdef012345', REMINDER = 'a1b2c3d4-3333-4c5d-9e8f-001122334455'

describe('note links', () => {
  it('writes the link syntax for each kind and strips characters that would break it', () => {
    expect(wikilink('note', NOTE, 'Decisões')).toBe(`[[${NOTE}|Decisões]]`)
    expect(wikilink('file', FILE, 'plano [v2] | final')).toBe(`[[file:${FILE}|plano v2 final]]`)
    expect(wikilink('reminder', REMINDER, '')).toBe(`[[reminder:${REMINDER}]]`)
  })
  it('renders notes, files and reminders as typed internal links with escaped labels', () => {
    const html = markdownToHtml(`Veja [[${NOTE}|Arquitetura]], [[file:${FILE}|<b>plano</b>.pdf]] e [[reminder:${REMINDER}|Reunião]].`)
    expect(html).toContain(`href="/notes/${NOTE}" class="wikilink wikilink-note" data-kind="note" data-id="${NOTE}"`)
    expect(html).toContain(`href="/files?preview=${FILE}" class="wikilink wikilink-file"`)
    expect(html).toContain('&lt;b&gt;plano&lt;/b&gt;.pdf')
    expect(html).toContain(`href="/reminders?focus=${REMINDER}"`)
  })
  it('only treats real UUIDs as links', () => {
    expect(markdownToHtml('[[0123456789abcdef0123456789abcdef0123|falso]]')).not.toContain('wikilink')
  })
  it('uses the current note title when a link has no label and leaves code untouched', () => {
    const html = markdownToHtml(`[[${NOTE}]]\n\n\`[[${NOTE}|literal]]\``, new Map([[NOTE, 'Título atual']]))
    expect(html).toContain('>Título atual</a>')
    expect(html).toContain(`<code>[[${NOTE}|literal]]</code>`)
  })
  it('extracts a plain excerpt for previews', () => {
    expect(plainText(`# Título\n\n**Contexto** com [link](https://x.y) e [[file:${FILE}|anexo.pdf]]\n\n\`\`\`js\ncode()\n\`\`\`\n- [ ] etapa`)).toBe('Título Contexto com link e anexo.pdf etapa')
  })
})

describe('editor helpers', () => {
  it('inserts at the cursor and wraps or prefixes the selection', () => {
    expect(insertText('abcdef', 3, 3, 'XY')).toEqual({ value: 'abcXYdef', start: 5, end: 5 })
    expect(wrapText('um texto', 3, 8, '**')).toEqual({ value: 'um **texto**', start: 5, end: 10 })
    expect(wrapText('', 0, 0, '_').value).toBe('_texto_')
    expect(prefixLines('a\nb\nc', 2, 5, '- ').value).toBe('a\n- b\n- c')
  })
})

describe('uploads', () => {
  const limits = { maxBytes: 20 * 1024 * 1024, extensions: ['.pdf', '.docx', '.txt', '.png', '.jpg', '.jpeg', '.webp'] }
  it('formats sizes in Portuguese', () => {
    expect(formatSize(512)).toBe('512 B')
    expect(formatSize(1536)).toBe('1,5 KB')
    expect(formatSize(20 * 1024 * 1024)).toBe('20 MB')
  })
  it('rejects oversized, empty and unsupported files before sending', () => {
    expect(validateUpload({ name: 'video.mp4', size: 10 }, limits)).toMatch(/formato não permitido/)
    expect(validateUpload({ name: 'vazio.txt', size: 0 }, limits)).toMatch(/vazio/)
    expect(validateUpload({ name: 'grande.PDF', size: 25 * 1024 * 1024 }, limits)).toBe('grande.PDF tem 25 MB; o limite por arquivo é 20 MB.')
    expect(validateUpload({ name: 'ok.jpeg', size: 1024 }, limits)).toBe('')
  })
  it('classifies files for previews', () => {
    expect(fileKind('image/webp')).toBe('image')
    expect(fileKind('application/pdf')).toBe('pdf')
    expect(fileKind('application/vnd.openxmlformats-officedocument.wordprocessingml.document')).toBe('docx')
    expect(fileKind('application/zip', 'x.zip')).toBe('other')
  })
})

describe('overview layout', () => {
  it('pulls linked notes together and keeps every position finite', () => {
    const nodes = initLayout(Array.from({ length: 12 }, (_, i) => ({ id: String(i), r: 9 })), 900, 600)
    const links = [[0, 1], [1, 2], [2, 0], [3, 4]]
    runLayout(nodes, links, { width: 900, height: 600 })
    const dist = (a, b) => Math.hypot(nodes[a].x - nodes[b].x, nodes[a].y - nodes[b].y)
    let total = 0, pairs = 0
    for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) { total += dist(i, j); pairs++ }
    expect(nodes.every(n => Number.isFinite(n.x) && Number.isFinite(n.y))).toBe(true)
    expect((dist(0, 1) + dist(1, 2) + dist(3, 4)) / 3).toBeLessThan(total / pairs)
    expect(bounds(nodes, 10).width).toBeGreaterThan(0)
  })
  it('clusters notes that share a category', () => {
    const nodes = initLayout(Array.from({ length: 10 }, (_, i) => ({ id: String(i), r: 8, group: i % 2 ? 'a' : 'b' })), 900, 600)
    runLayout(nodes, [], { width: 900, height: 600 })
    const center = g => { const m = nodes.filter(n => n.group === g); return [m.reduce((s, n) => s + n.x, 0) / m.length, m.reduce((s, n) => s + n.y, 0) / m.length] }
    const [ax, ay] = center('a'), [bx, by] = center('b')
    expect(Math.hypot(ax - bx, ay - by)).toBeGreaterThan(40)
  })
})

describe('task updates', () => {
  it('describes feed entries in Portuguese', () => {
    expect(taskFeedText({ action: 'status_changed', detail: 'Em revisão' })).toBe('moveu para Em revisão')
    expect(taskFeedText({ action: 'assignee_added', detail: 'Ana' })).toBe('atribuiu a tarefa a Ana')
    expect(taskFeedText({ action: 'step_completed', detail: 'Testar' })).toBe('concluiu a etapa “Testar”')
    expect(taskFeedText({ action: 'due_changed', detail: '' })).toBe('removeu o prazo')
    expect(activityDetail({ action: 'assignee_changed', detail: NOTE })).toBe('')
  })
  it('shows relative times for notifications', () => {
    const now = Date.parse('2026-10-12T12:00:00Z')
    expect(relativeTime('2026-10-12T11:59:40Z', now)).toBe('agora')
    expect(relativeTime('2026-10-12T11:55:00Z', now)).toBe('há 5 minutos')
    expect(relativeTime('2026-10-11T12:00:00Z', now)).toBe('ontem')
  })
})
