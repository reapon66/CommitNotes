import { Marked } from 'marked'
import DOMPurify from 'dompurify'

export const LINK_KINDS = { note: 'Nota', file: 'Arquivo', reminder: 'Lembrete' }
const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
const WIKILINK = new RegExp(`^\\[\\[(?:(note|file|reminder):)?(${UUID})(?:\\|([^\\]\\n]*))?\\]\\]`)
const escapeHtml = value => value.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
let titles = new Map()

export const wikilinkHref = (kind, id) => kind === 'note' ? `/notes/${id}` : kind === 'file' ? `/files?preview=${id}` : `/reminders?focus=${id}`
/** Syntax stored in the note body: notes keep the original [[id|Título]] form; files and reminders carry a prefix. */
export function wikilink(kind, id, label) {
  const clean = (label || '').replace(/[[\]|\n]/g, ' ').replace(/\s+/g, ' ').trim()
  return `[[${kind === 'note' ? '' : `${kind}:`}${id}${clean ? `|${clean}` : ''}]]`
}

const marked = new Marked({
  gfm: true, breaks: true,
  extensions: [{
    name: 'wikilink', level: 'inline',
    start(src) { const index = src.indexOf('[['); return index < 0 ? undefined : index },
    tokenizer(src) {
      const match = WIKILINK.exec(src)
      if (match) return { type: 'wikilink', raw: match[0], kind: match[1] || 'note', id: match[2].toLowerCase(), label: match[3]?.trim() || '' }
    },
    renderer(token) {
      const label = token.label || titles.get(token.id) || LINK_KINDS[token.kind]
      return `<a href="${wikilinkHref(token.kind, token.id)}" class="wikilink wikilink-${token.kind}" data-kind="${token.kind}" data-id="${token.id}" title="${LINK_KINDS[token.kind]}">${escapeHtml(label)}</a>`
    }
  }]
})

/** Markdown to HTML with internal links resolved; not sanitized (used by tests and by renderMarkdown). */
export function markdownToHtml(source, noteTitles = new Map()) {
  titles = noteTitles
  try { return marked.parse(source || '') } finally { titles = new Map() }
}

// Notes are shared with the whole project: no styles or form controls that could restyle the app or imitate a login.
const SANITIZE = { ADD_ATTR: ['target'], FORBID_TAGS: ['style', 'form', 'button', 'textarea', 'select', 'option', 'link', 'meta'], FORBID_ATTR: ['style'] }
let hooked = false
export function renderMarkdown(source, noteTitles) {
  if (!hooked) {
    DOMPurify.addHook('afterSanitizeAttributes', node => {
      if (node.tagName === 'A' && /^https?:\/\//i.test(node.getAttribute('href') || '')) { node.setAttribute('target', '_blank'); node.setAttribute('rel', 'noopener noreferrer') }
      if (node.tagName === 'INPUT') {
        if (node.getAttribute('type') !== 'checkbox') node.remove()
        else { [...node.attributes].forEach(a => { if (!['type', 'checked'].includes(a.name)) node.removeAttribute(a.name) }); node.setAttribute('disabled', '') }
      }
    })
    hooked = true
  }
  return DOMPurify.sanitize(markdownToHtml(source, noteTitles), SANITIZE)
}

/** Plain-text excerpt for previews: drops Markdown syntax and keeps link labels. */
export function plainText(source = '') {
  return source
    .replace(/```[\s\S]*?(```|$)/g, ' ')
    .replace(new RegExp(`\\[\\[(?:(?:note|file|reminder):)?${UUID}(?:\\|([^\\]\\n]*))?\\]\\]`, 'g'), (_, label) => label || '')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^[\s|:-]+$/gm, ' ')
    .replace(/^\s{0,3}(#{1,6}|>|[-*+]\s+\[[ xX]\]|[-*+]|\d+\.)\s+/gm, '')
    .replace(/[*_~`|#>]/g, '')
    .replace(/\s+/g, ' ').trim()
}

/** Text editing helpers for the Markdown textarea; they return the new value and selection. */
export function insertText(value, start, end, text) {
  const next = value.slice(0, start) + text + value.slice(end)
  return { value: next, start: start + text.length, end: start + text.length }
}
export function wrapText(value, start, end, before, after = before, placeholder = 'texto') {
  const selected = value.slice(start, end) || placeholder
  const next = value.slice(0, start) + before + selected + after + value.slice(end)
  return { value: next, start: start + before.length, end: start + before.length + selected.length }
}
export function prefixLines(value, start, end, prefix) {
  const lineStart = value.lastIndexOf('\n', start - 1) + 1
  const block = value.slice(lineStart, end) || ''
  const prefixed = block.split('\n').map(line => prefix + line).join('\n')
  const next = value.slice(0, lineStart) + prefixed + value.slice(end)
  return { value: next, start: start + prefix.length, end: lineStart + prefixed.length }
}
