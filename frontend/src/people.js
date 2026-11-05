const AVATAR_COLORS = ['#5368d9', '#0f8b8d', '#c2410c', '#7c3aed', '#be185d', '#15803d', '#b45309', '#0369a1']
export function initials(name = '') {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  return ((parts[0]?.[0] || '?') + (parts.length > 1 ? parts.at(-1)[0] : '')).toUpperCase()
}
export function avatarColor(seed = '') {
  let hash = 0
  for (const char of seed) hash = (hash * 31 + char.charCodeAt(0)) | 0
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length]
}
