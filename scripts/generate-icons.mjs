// Generates the PWA icons in frontend/public/icons from the CommitNotes logo (no dependencies).
// Run with: node scripts/generate-icons.mjs
import { mkdirSync, writeFileSync } from 'node:fs'
import { deflateSync } from 'node:zlib'

const ACCENT = [0x53, 0x68, 0xd9]
const CHECK = [[17, 34], [27, 44], [47, 21]]  // same path as frontend/public/icon.svg (64x64 grid)
const STROKE = 3.5                              // half of the SVG stroke width

const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0 })
const crc32 = bytes => { let c = 0xffffffff; for (const b of bytes) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
function chunk(type, data) {
  const out = Buffer.alloc(12 + data.length)
  out.writeUInt32BE(data.length, 0); out.write(type, 4, 'ascii'); data.copy(out, 8)
  out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length)
  return out
}
function png(size, pixel) {
  const raw = Buffer.alloc((size * 4 + 1) * size)
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0
    for (let x = 0; x < size; x++) raw.set(pixel(x, y), y * (size * 4 + 1) + 1 + x * 4)
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(size, 0); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 6
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))])
}
function segmentDistance(px, py, [ax, ay], [bx, by]) {
  const dx = bx - ax, dy = by - ay, t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
  return Math.hypot(px - ax - t * dx, py - ay - t * dy)
}
const onCheck = (x, y) => segmentDistance(x, y, CHECK[0], CHECK[1]) <= STROKE || segmentDistance(x, y, CHECK[1], CHECK[2]) <= STROKE
const inRoundedSquare = (x, y, radius) => {
  const cx = Math.max(radius, Math.min(64 - radius, x)), cy = Math.max(radius, Math.min(64 - radius, y))
  return x >= 0 && x <= 64 && y >= 0 && y <= 64 && Math.hypot(x - cx, y - cy) <= radius
}
/**
 * radius: corner radius of the background (0 = full bleed), scale: size of the check mark around the center,
 * background: false draws only a white check (notification badge).
 */
function icon(size, { radius = 15, scale = 1, background = true } = {}) {
  const samples = 4
  return png(size, (px, py) => {
    let cover = 0, mark = 0
    for (let sy = 0; sy < samples; sy++) for (let sx = 0; sx < samples; sx++) {
      const x = (px + (sx + 0.5) / samples) * 64 / size, y = (py + (sy + 0.5) / samples) * 64 / size
      const inside = !background || inRoundedSquare(x, y, radius)
      const checked = onCheck(32 + (x - 32) / scale, 32.5 + (y - 32.5) / scale)
      if (inside && background) cover++
      if (inside && checked) mark++
    }
    const total = samples * samples, m = mark / total
    if (!background) return [255, 255, 255, Math.round(255 * m)]
    const a = cover / total, base = cover ? m * total / cover : 0
    return [...ACCENT.map(c => Math.round(c + (255 - c) * base)), Math.round(255 * a)]
  })
}

const dir = new URL('../frontend/public/icons/', import.meta.url)
mkdirSync(dir, { recursive: true })
const files = {
  'icon-192.png': icon(192), 'icon-512.png': icon(512),
  'maskable-512.png': icon(512, { radius: 0, scale: 0.78 }), 'apple-touch-icon.png': icon(180, { radius: 0, scale: 0.9 }),
  'favicon-32.png': icon(32), 'badge-96.png': icon(96, { background: false, scale: 1.25 })
}
for (const [name, data] of Object.entries(files)) writeFileSync(new URL(name, dir), data)
console.log(`Ícones gerados: ${Object.keys(files).join(', ')}`)
