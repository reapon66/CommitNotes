import { afterEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, dateInput, isoInput } from './api'

describe('API client', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('sends same-origin credentials and mutation header', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, headers: { get: () => 'application/json' }, json: async () => ({ ok: true }) })
    vi.stubGlobal('fetch', fetchMock)
    expect(await api('/projects', { method: 'POST', body: JSON.stringify({ name: 'Test' }) })).toEqual({ ok: true })
    expect(fetchMock.mock.calls[0][1].credentials).toBe('include')
    expect(fetchMock.mock.calls[0][1].headers['X-Requested-With']).toBe('XMLHttpRequest')
  })
  it('reports structured API errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ error: 'Conflito de edição' }) }))
    await expect(api('/notes/id')).rejects.toMatchObject({ message: 'Conflito de edição', status: 409 })
  })
  it('converts local datetime to an absolute instant', () => {
    expect(new Date(isoInput('2026-10-12T10:30')).toISOString()).toMatch(/^2026-10-12T/)
    expect(dateInput('2026-10-12T10:30:00Z')).toMatch(/^2026-10-12T/)
  })
})
