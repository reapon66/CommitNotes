/**
 * Small force-directed layout for the notes overview: nodes repel each other, links act as springs,
 * notes that share a category (tag or folder) drift toward the same cluster and a weak gravity keeps
 * everything centered. Positions are mutated in place so the view can animate tick by tick.
 */
export function initLayout(items, width, height) {
  const cx = width / 2, cy = height / 2, spread = Math.min(width, height) * 0.42
  return items.map((item, i) => {
    const radius = spread * Math.sqrt((i + 0.5) / Math.max(1, items.length)), angle = i * 2.399963
    return { r: 8, w: 90, group: null, ...item, x: cx + radius * Math.cos(angle), y: cy + radius * Math.sin(angle), vx: 0, vy: 0, fx: null, fy: null }
  })
}

export function tickLayout(nodes, links, { width, height, alpha, distance = 150 }) {
  const n = nodes.length, cx = width / 2, cy = height / 2
  for (let i = 0; i < n; i++) {
    const a = nodes[i]
    for (let j = i + 1; j < n; j++) {
      const b = nodes[j]
      let dx = b.x - a.x, dy = b.y - a.y, d2 = dx * dx + dy * dy
      if (d2 < 0.01) { dx = 0.1 * ((i % 7) - 3 || 1); dy = 0.1 * ((j % 5) - 2 || 1); d2 = dx * dx + dy * dy }
      // Labels sit under the nodes, so neighbours need more room sideways than vertically.
      const d = Math.sqrt(d2), ex = dx / ((a.w + b.w) / 2 + 14), ey = dy / (a.r + b.r + 38), q = Math.hypot(ex, ey)
      if (q < 1) {
        const push = Math.min(2, (1 - q) / Math.max(q, 0.05)) * 0.3
        a.vx -= dx * push; a.vy -= dy * push; b.vx += dx * push; b.vy += dy * push
      }
      if (d2 > 160000) continue
      const repel = 1100 * alpha / d2
      a.vx -= dx * repel; a.vy -= dy * repel; b.vx += dx * repel; b.vy += dy * repel
    }
  }
  const degree = new Array(n).fill(0)
  for (const [s, t] of links) { degree[s]++; degree[t]++ }
  for (const [s, t] of links) {
    const a = nodes[s], b = nodes[t]
    const dx = b.x + b.vx - a.x - a.vx, dy = b.y + b.vy - a.y - a.vy, d = Math.hypot(dx, dy) || 1
    const pull = (d - distance) / d * alpha * 0.5 / Math.min(degree[s], degree[t])
    a.vx += dx * pull; a.vy += dy * pull; b.vx -= dx * pull; b.vy -= dy * pull
  }
  const groups = new Map()
  for (const node of nodes) if (node.group) {
    const g = groups.get(node.group) || { x: 0, y: 0, count: 0 }
    g.x += node.x; g.y += node.y; g.count++; groups.set(node.group, g)
  }
  for (const node of nodes) {
    const g = node.group && groups.get(node.group)
    if (g && g.count > 1) { node.vx += (g.x / g.count - node.x) * 0.035 * alpha; node.vy += (g.y / g.count - node.y) * 0.035 * alpha }
    const rx = node.x - cx, ry = (node.y - cy) * width / height, dist = Math.hypot(rx, ry), comfort = Math.min(width, height) * 0.36
    if (dist > comfort) { const pull = (dist - comfort) / dist * 0.12 * alpha; node.vx -= rx * pull; node.vy -= (node.y - cy) * pull }
    node.vx += (cx - node.x) * 0.008 * alpha; node.vy += (cy - node.y) * 0.012 * alpha
    if (node.fx != null) { node.x = node.fx; node.y = node.fy; node.vx = node.vy = 0; continue }
    node.vx *= 0.6; node.vy *= 0.6
    node.x += node.vx; node.y += node.vy
  }
}

export function runLayout(nodes, links, options, ticks = 300) {
  let alpha = 1
  for (let i = 0; i < ticks && alpha > 0.005; i++) { tickLayout(nodes, links, { ...options, alpha }); alpha *= 0.975 }
  return nodes
}

/** Bounding box of the nodes including their labels (labelScale > 1 when labels are drawn larger to stay legible). */
export function bounds(nodes, margin = 0, labelScale = 1) {
  if (!nodes.length) return { x: 0, y: 0, width: 0, height: 0 }
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const n of nodes) {
    const half = Math.max(n.r, (n.w || 0) / 2 * labelScale)
    minX = Math.min(minX, n.x - half); maxX = Math.max(maxX, n.x + half); minY = Math.min(minY, n.y - n.r); maxY = Math.max(maxY, n.y + n.r + (n.w ? 20 * labelScale : 0))
  }
  return { x: minX - margin, y: minY - margin, width: maxX - minX + 2 * margin, height: maxY - minY + 2 * margin }
}
