import { dateTime } from './api'

const entities = { project:'projeto', note:'nota', task:'tarefa', file:'arquivo', member:'pessoa', folder:'pasta', tag:'etiqueta', board:'quadro', column:'coluna' }
const actions = {
  created:'criou', updated:'alterou', deleted:'excluiu', uploaded:'enviou', linked:'vinculou',
  archive:'arquivou', trash:'moveu para a lixeira', restore:'restaurou', renamed:'renomeou',
  added:'adicionou', removed:'removeu', role_changed:'alterou a permissão de',
  status_changed:'alterou o status da', assignee_changed:'alterou o responsável pela',
  assignee_added:'adicionou um responsável à', assignee_removed:'removeu um responsável da',
  due_changed:'alterou o prazo da', completed:'concluiu', reopened:'reabriu',
  step_added:'adicionou uma etapa à', step_completed:'concluiu uma etapa da',
  step_updated:'alterou uma etapa da', step_removed:'removeu uma etapa da', note_linked:'vinculou uma nota à',
  note_unlinked:'desvinculou uma nota da', commented:'comentou na', file_attached:'anexou um arquivo à', file_detached:'removeu um anexo da'
}
export function activityLabel(item) { return `${actions[item.action] || item.action} ${entities[item.entity_type] || item.entity_type}` }

const isUuid = value => /^[0-9a-f-]{36}$/i.test(value || '')
/** Readable detail for timeline rows (dates formatted, internal ids hidden). */
export function activityDetail({ action, detail }) {
  if (!detail || isUuid(detail)) return ''
  if (action === 'due_changed') return isNaN(Date.parse(detail)) ? detail : `prazo ${dateTime(detail)}`
  return detail
}
/** Sentence for one entry of a task's update feed (the actor's name is shown separately). */
export function taskFeedText({ action, detail }) {
  const quoted = detail && !isUuid(detail) ? `“${detail}”` : ''
  switch (action) {
    case 'created': return 'criou a tarefa'
    case 'updated': return 'editou os detalhes'
    case 'status_changed': return `moveu para ${detail}`
    case 'completed': return detail ? `concluiu a tarefa em ${detail}` : 'concluiu a tarefa'
    case 'reopened': return detail ? `reabriu a tarefa em ${detail}` : 'reabriu a tarefa'
    case 'assignee_added': return `atribuiu a tarefa a ${detail}`
    case 'assignee_removed': return `removeu ${detail} dos responsáveis`
    case 'assignee_changed': return 'alterou o responsável'
    case 'due_changed': return detail && !isNaN(Date.parse(detail)) ? `definiu o prazo para ${dateTime(detail)}` : 'removeu o prazo'
    case 'step_added': return `adicionou a etapa ${quoted}`
    case 'step_completed': return `concluiu a etapa ${quoted}`
    case 'step_updated': return `alterou a etapa ${quoted}`
    case 'step_removed': return `removeu a etapa ${quoted}`
    case 'note_linked': return quoted ? `vinculou a nota ${quoted}` : 'vinculou uma nota'
    case 'note_unlinked': return `desvinculou a nota ${quoted}`
    case 'file_attached': return `anexou ${quoted}`
    case 'file_detached': return `removeu o anexo ${quoted}`
    default: return actions[action] || action
  }
}
export const feedIcon = action => ({ created: 'plus', completed: 'done', reopened: 'restore', status_changed: 'move-right', assignee_added: 'user-plus', assignee_removed: 'users',
  due_changed: 'due', step_added: 'checklist', step_completed: 'check', step_updated: 'checklist', step_removed: 'checklist', note_linked: 'link', note_unlinked: 'unlink',
  file_attached: 'attach', file_detached: 'attach', updated: 'edit' })[action] || 'info'
