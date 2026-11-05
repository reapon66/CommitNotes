<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, triggerRef, watch } from 'vue'
import { dateTime } from '../api'
import { bounds, initLayout, runLayout, tickLayout } from '../graphLayout'
import { plainText } from '../markdown'
import Icon from './Icon.vue'

const props = defineProps({ graph: { type: Object, required: true } })
const emit = defineEmits(['open'])
const PALETTE = ['#6366f1', '#f97316', '#22c55e', '#ec4899', '#0ea5e9', '#eab308', '#8b5cf6', '#ef4444', '#14b8a6', '#64748b']
const NEUTRAL = '#94a3b8'
const host = ref(null), size = reactive({ w: 900, h: 560 }), view = reactive({ x: 0, y: 0, k: 1 })
const sim = shallowRef([]), hovered = ref(null), selected = ref(null), colorBy = ref('tag'), filter = ref(null), search = ref('')
const reducedMotion = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
let frame = 0, alpha = 0, interacted = false, hideTimer = 0, index = new Map(), edgesByIndex = [], observer, drag = null
const pointers = new Map()
const normalize = value => (value || '').normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()

const nodeById = computed(() => new Map(props.graph.nodes.map(n => [n.id, n])))
const tagById = computed(() => new Map(props.graph.tags.map(t => [t.id, t])))
const folderById = computed(() => new Map(props.graph.folders.map((f, i) => [f.id, { ...f, color: PALETTE[i % PALETTE.length] }])))
const adjacency = computed(() => {
  const map = new Map(props.graph.nodes.map(n => [n.id, { out: new Set(), in: new Set() }]))
  for (const l of props.graph.links) { map.get(l.source_id)?.out.add(l.target_id); map.get(l.target_id)?.in.add(l.source_id) }
  return map
})
const neighborsOf = id => { const a = adjacency.value.get(id); return a ? new Set([...a.out, ...a.in]) : new Set() }
const tagsOf = node => (node.tag_ids || []).map(id => tagById.value.get(id)).filter(Boolean)
function colorsOf(node) {
  if (colorBy.value === 'folder') return [folderById.value.get(node.folder_id)?.color || NEUTRAL]
  const colors = tagsOf(node).map(t => t.color)
  return colors.length ? colors : [NEUTRAL]
}
const groupOf = node => colorBy.value === 'folder' ? node.folder_id : node.tag_ids?.[0] || null

const legend = computed(() => {
  const nodes = props.graph.nodes
  const items = colorBy.value === 'folder'
    ? [...props.graph.folders.map(f => ({ id: f.id, name: f.name, color: folderById.value.get(f.id).color, count: nodes.filter(n => n.folder_id === f.id).length })),
      { id: 'none', name: 'Sem pasta', color: NEUTRAL, count: nodes.filter(n => !n.folder_id).length }]
    : [...props.graph.tags.map(t => ({ id: t.id, name: t.name, color: t.color, count: nodes.filter(n => n.tag_ids?.includes(t.id)).length })),
      { id: 'none', name: 'Sem etiqueta', color: NEUTRAL, count: nodes.filter(n => !n.tag_ids?.length).length }]
  return items.filter(i => i.count)
})
function inFilter(node) {
  if (!filter.value) return true
  if (colorBy.value === 'folder') return filter.value === 'none' ? !node.folder_id : node.folder_id === filter.value
  return filter.value === 'none' ? !node.tag_ids?.length : node.tag_ids?.includes(filter.value)
}
const term = computed(() => normalize(search.value.trim()))
const visible = node => inFilter(node) && (!term.value || normalize(node.title).includes(term.value))
const focusId = computed(() => hovered.value || selected.value)
const related = computed(() => focusId.value ? new Set([focusId.value, ...neighborsOf(focusId.value)]) : null)
function nodeState(id) {
  if (related.value) return id === focusId.value ? 'focus' : related.value.has(id) ? 'neighbor' : 'dim'
  return visible(nodeById.value.get(id)) ? (filter.value || term.value ? 'match' : '') : 'dim'
}
function edgeState(link) {
  if (focusId.value) return link.source_id === focusId.value || link.target_id === focusId.value ? 'active' : 'dim'
  if (filter.value || term.value) return visible(nodeById.value.get(link.source_id)) && visible(nodeById.value.get(link.target_id)) ? '' : 'dim'
  return ''
}

const positions = computed(() => new Map(sim.value.map(n => [n.id, n])))
const edges = computed(() => props.graph.links.map(link => {
  const a = positions.value.get(link.source_id), b = positions.value.get(link.target_id)
  if (!a || !b) return null
  const dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy) || 1, cut = Math.min(d / 2, b.r + 5)
  return { key: `${link.source_id}>${link.target_id}`, link, x1: a.x, y1: a.y, x2: b.x - dx / d * cut, y2: b.y - dy / d * cut }
}).filter(Boolean))
const ring = (r, count, i) => { const c = 2 * Math.PI * (r + 4), seg = c / count; return { dasharray: `${seg - (count > 1 ? 2 : 0)} ${c - seg + (count > 1 ? 2 : 0)}`, dashoffset: -seg * i } }
const showLabel = id => sim.value.length <= 60 || view.k >= 0.75 || ['focus', 'neighbor', 'match'].includes(nodeState(id))
const labelScale = computed(() => 1 / Math.min(1, view.k))
const label = title => title.length > 28 ? `${title.slice(0, 27)}…` : title

const card = computed(() => {
  const node = focusId.value && nodeById.value.get(focusId.value), pos = node && positions.value.get(node.id)
  if (!node || !pos) return null
  const a = adjacency.value.get(node.id), sx = pos.x * view.k + view.x, sy = pos.y * view.k + view.y, gap = pos.r * view.k + 14
  const style = sx + gap + 320 < size.w ? { left: `${sx + gap}px` } : { right: `${Math.max(8, size.w - sx + gap)}px` }
  Object.assign(style, sy > size.h / 2 ? { bottom: `${Math.max(8, size.h - sy - 24)}px` } : { top: `${Math.max(8, sy - 24)}px` })
  const excerpt = plainText(node.excerpt)
  return {
    node, style, excerpt: excerpt.length > 220 ? `${excerpt.slice(0, 219)}…` : excerpt, tags: tagsOf(node), folder: folderById.value.get(node.folder_id)?.name,
    out: a.out.size, in: a.in.size, neighbors: [...neighborsOf(node.id)].map(id => nodeById.value.get(id)).filter(Boolean).slice(0, 6)
  }
})

function measure() {
  const rect = host.value?.getBoundingClientRect()
  if (rect?.width) { size.w = rect.width; size.h = rect.height }
}
function build() {
  measure()
  const previous = positions.value
  const nodes = initLayout(props.graph.nodes.map(n => ({ id: n.id, r: 7 + Math.min(11, Math.sqrt(neighborsOf(n.id).size) * 3.4), w: Math.min(28, n.title.length) * 6.6 + 12, group: groupOf(n) })), size.w, size.h)
  for (const node of nodes) { const old = previous.get(node.id); if (old) { node.x = old.x; node.y = old.y } }
  index = new Map(nodes.map((n, i) => [n.id, i]))
  edgesByIndex = props.graph.links.map(l => [index.get(l.source_id), index.get(l.target_id)]).filter(([s, t]) => s !== undefined && t !== undefined)
  // Most of the layout is computed up front so notes can be hovered right away; only the final settling is animated.
  runLayout(nodes, edgesByIndex, { width: size.w, height: size.h }, reducedMotion ? 300 : 170)
  sim.value = nodes; fit()
  if (!reducedMotion) heat(0.03)
}
function heat(value) { alpha = Math.max(alpha, value); if (!frame) frame = requestAnimationFrame(step) }
function step() {
  frame = 0
  tickLayout(sim.value, edgesByIndex, { width: size.w, height: size.h, alpha })
  alpha *= 0.975
  if (!interacted) fit()
  triggerRef(sim)
  if (alpha > 0.005) frame = requestAnimationFrame(step)
}
function fit() {
  if (!sim.value.length) return
  let k = 1, box
  for (let i = 0; i < 3; i++) {
    box = bounds(sim.value, 18, sim.value.length <= 60 ? 1 / Math.min(1, k) : 1)
    k = Math.min(1.6, Math.max(0.2, Math.min(size.w / box.width, size.h / box.height)))
  }
  view.k = k; view.x = size.w / 2 - (box.x + box.width / 2) * k; view.y = size.h / 2 - (box.y + box.height / 2) * k
}
function zoom(factor, px = size.w / 2, py = size.h / 2) {
  const k = Math.min(4, Math.max(0.2, view.k * factor))
  view.x = px - (px - view.x) * k / view.k; view.y = py - (py - view.y) * k / view.k; view.k = k; interacted = true
}
function resetView() { interacted = false; fit() }
function center(id) {
  const node = positions.value.get(id); if (!node) return
  interacted = true; view.k = Math.max(view.k, 1); view.x = size.w / 2 - node.x * view.k; view.y = size.h / 2 - node.y * view.k
  clearTimeout(hideTimer); hovered.value = null; selected.value = id
}
function local(event) { const rect = host.value.getBoundingClientRect(); return { x: event.clientX - rect.left, y: event.clientY - rect.top } }

function onPointerDown(event) {
  const nodeId = event.target.closest?.('[data-node]')?.dataset.node || null
  try { event.currentTarget.setPointerCapture(event.pointerId) } catch (_) {}
  pointers.set(event.pointerId, local(event))
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()], held = drag?.type === 'node' && sim.value[index.get(drag.id)]
    if (held) { held.fx = held.fy = null }
    drag = { type: 'pinch', distance: Math.hypot(a.x - b.x, a.y - b.y), k: view.k, moved: true }
    return
  }
  const p = local(event)
  drag = { type: nodeId ? 'node' : 'pan', id: nodeId, start: p, view: { x: view.x, y: view.y }, moved: false, pointerType: event.pointerType }
}
function onPointerMove(event) {
  if (!pointers.has(event.pointerId) || !drag) return
  const p = local(event); pointers.set(event.pointerId, p)
  if (drag.type === 'pinch') {
    if (pointers.size < 2) return
    const [a, b] = [...pointers.values()], distance = Math.hypot(a.x - b.x, a.y - b.y)
    zoom(drag.k * distance / drag.distance / view.k, (a.x + b.x) / 2, (a.y + b.y) / 2)
    return
  }
  const dx = p.x - drag.start.x, dy = p.y - drag.start.y
  if (!drag.moved && Math.hypot(dx, dy) < 5) return
  drag.moved = true; interacted = true
  if (drag.type === 'pan') { view.x = drag.view.x + dx; view.y = drag.view.y + dy; return }
  const node = sim.value[index.get(drag.id)]
  if (!node) return
  node.fx = node.x = (p.x - view.x) / view.k; node.fy = node.y = (p.y - view.y) / view.k
  hovered.value = null; selected.value = drag.id; heat(0.3); triggerRef(sim)
}
function onPointerUp(event) {
  pointers.delete(event.pointerId)
  const current = drag
  if (!current || (current.type === 'pinch' && pointers.size)) return
  drag = null
  if (current.type === 'node') {
    const node = sim.value[index.get(current.id)]
    if (node) { node.fx = node.fy = null }
    if (!current.moved) activate(current.id, current.pointerType)
  } else if (current.type === 'pan' && !current.moved) selected.value = null
}
function activate(id, pointerType) {
  if (pointerType === 'mouse' || selected.value === id) emit('open', id)
  else selected.value = id
}
function onWheel(event) { const p = local(event); zoom(Math.exp(-event.deltaY * 0.0015), p.x, p.y) }
function enter(id, event) { if (event.pointerType !== 'mouse' || drag) return; clearTimeout(hideTimer); hovered.value = id }
function leave(event) { if (event.pointerType !== 'mouse') return; clearTimeout(hideTimer); hideTimer = setTimeout(() => { hovered.value = null }, 180) }
function keepCard() { clearTimeout(hideTimer) }
function findFirst() { const match = props.graph.nodes.find(n => visible(n)); if (match && term.value) center(match.id) }

watch(() => props.graph, build)
watch(colorBy, () => { filter.value = null; sim.value.forEach(n => { n.group = groupOf(nodeById.value.get(n.id)) }); if (!reducedMotion) heat(0.35) })
onMounted(() => {
  build()
  observer = new ResizeObserver(() => { measure(); if (!interacted) fit() })
  observer.observe(host.value)
})
onBeforeUnmount(() => { cancelAnimationFrame(frame); observer?.disconnect(); clearTimeout(hideTimer) })
</script>

<template>
  <div class="graph-shell">
    <div class="graph-toolbar">
      <label class="search-field graph-search">
        <Icon name="search" :size="16" /><span class="sr-only">Encontrar nota</span>
        <input v-model="search" type="search" placeholder="Encontrar nota…" @keydown.enter.prevent="findFirst" />
      </label>
      <div class="segmented" role="group" aria-label="Colorir notas por">
        <button type="button" :class="{ active: colorBy === 'tag' }" :aria-pressed="colorBy === 'tag'" @click="colorBy = 'tag'"><Icon name="tags" :size="15" />Etiquetas</button>
        <button type="button" :class="{ active: colorBy === 'folder' }" :aria-pressed="colorBy === 'folder'" @click="colorBy = 'folder'"><Icon name="folder" :size="15" />Pastas</button>
      </div>
      <span class="graph-stats">{{ graph.nodes.length }} notas · {{ graph.links.length }} ligações</span>
    </div>
    <div class="graph-legend" role="group" aria-label="Filtrar por categoria">
      <button v-for="item in legend" :key="item.id" type="button" class="legend-chip" :class="{ active: filter === item.id, faded: filter && filter !== item.id }"
        :style="{ '--chip': item.color }" :aria-pressed="filter === item.id" @click="filter = filter === item.id ? null : item.id">
        <span class="legend-dot"></span>{{ item.name }}<small>{{ item.count }}</small>
      </button>
      <span v-if="!legend.length" class="hint">Crie etiquetas ou pastas para diferenciar as notas por cor.</span>
    </div>

    <div ref="host" class="graph-stage">
      <svg class="graph" :viewBox="`0 0 ${size.w} ${size.h}`" role="img" aria-label="Visão geral das notas e suas ligações"
        @pointerdown="onPointerDown" @pointermove="onPointerMove" @pointerup="onPointerUp" @pointercancel="onPointerUp" @wheel.prevent="onWheel">
        <defs>
          <marker id="graph-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0 10 5 0 10z" class="graph-arrow" /></marker>
        </defs>
        <g :transform="`translate(${view.x} ${view.y}) scale(${view.k})`">
          <line v-for="e in edges" :key="e.key" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2" class="graph-edge" :class="edgeState(e.link)"
            :marker-end="edgeState(e.link) === 'active' ? 'url(#graph-arrow)' : undefined" />
          <g v-for="n in sim" :key="n.id" class="graph-node" :class="[nodeState(n.id), { archived: nodeById.get(n.id)?.archived_at }]" :data-node="n.id"
            :transform="`translate(${n.x} ${n.y})`" tabindex="0" role="button" :aria-label="`${nodeById.get(n.id)?.title}, ${neighborsOf(n.id).size} ligações`"
            @pointerenter="enter(n.id, $event)" @pointerleave="leave" @focus="selected = n.id" @keydown.enter="emit('open', n.id)" @keydown.esc="selected = null">
            <circle class="graph-halo" :r="n.r + 9" />
            <template v-if="colorsOf(nodeById.get(n.id)).length > 1">
              <circle v-for="(color, i) in colorsOf(nodeById.get(n.id))" :key="color + i" class="graph-ring" :r="n.r + 4" :stroke="color"
                :stroke-dasharray="ring(n.r, colorsOf(nodeById.get(n.id)).length, i).dasharray" :stroke-dashoffset="ring(n.r, colorsOf(nodeById.get(n.id)).length, i).dashoffset" />
            </template>
            <circle class="graph-dot" :r="n.r" :fill="colorsOf(nodeById.get(n.id))[0]" />
            <text v-if="showLabel(n.id)" class="graph-label" :y="n.r + 15 * labelScale" text-anchor="middle" :style="{ fontSize: `${12 * labelScale}px`, strokeWidth: `${4 * labelScale}px` }">{{ label(nodeById.get(n.id)?.title || '') }}</text>
          </g>
        </g>
      </svg>

      <div class="graph-zoom">
        <button type="button" class="icon-btn" aria-label="Aproximar" @click="zoom(1.25)"><Icon name="zoom-in" /></button>
        <button type="button" class="icon-btn" aria-label="Afastar" @click="zoom(0.8)"><Icon name="zoom-out" /></button>
        <button type="button" class="icon-btn" aria-label="Enquadrar todas as notas" @click="resetView"><Icon name="fit" /></button>
      </div>

      <article v-if="card" class="graph-card" :style="card.style" @pointerenter="keepCard" @pointerleave="leave">
        <header>
          <span class="legend-dot" :style="{ '--chip': colorsOf(card.node)[0] }"></span>
          <strong>{{ card.node.title }}</strong>
          <Icon v-if="card.node.favorite" name="star" :size="15" class="star-on" />
        </header>
        <div v-if="card.tags.length" class="tag-row"><span v-for="t in card.tags" :key="t.id" class="tag-pill" :style="{ '--chip': t.color }">{{ t.name }}</span></div>
        <p class="graph-excerpt">{{ card.excerpt || 'Esta nota ainda não tem conteúdo.' }}</p>
        <dl class="graph-meta">
          <div><dt>Criada</dt><dd>{{ card.node.created_by_name }} · {{ dateTime(card.node.created_at) }}</dd></div>
          <div><dt>Atualizada</dt><dd>{{ card.node.updated_by_name }} · {{ dateTime(card.node.updated_at) }}</dd></div>
          <div v-if="card.folder"><dt>Pasta</dt><dd>{{ card.folder }}</dd></div>
          <div><dt>Ligações</dt><dd>{{ card.out }} saindo · {{ card.in }} chegando</dd></div>
        </dl>
        <div v-if="card.neighbors.length" class="graph-neighbors">
          <button v-for="nb in card.neighbors" :key="nb.id" type="button" class="ref-chip" @click="center(nb.id)"><Icon name="link" :size="13" />{{ nb.title }}</button>
        </div>
        <button type="button" class="btn btn-primary btn-sm btn-block" @click="emit('open', card.node.id)">Abrir nota<Icon name="arrow-right" :size="16" /></button>
      </article>

      <div v-if="!graph.nodes.length" class="graph-empty"><Icon name="overview" :size="34" /><strong>Nenhuma nota ainda</strong><span>Crie notas e conecte-as com links para formar a visão geral.</span></div>
    </div>
    <p class="hint graph-help">Passe o mouse (ou toque) sobre uma nota para ver a prévia e destacar suas ligações. Arraste para mover, use a roda ou o gesto de pinça para aproximar.</p>
  </div>
</template>
