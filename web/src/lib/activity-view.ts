/** Presentation rules shared by activity pages. No transport or browser state. */
export interface RunView {
  id?: string;
  status?: string;
  error?: string | null;
  agent_run?: { status?: string; phase?: string; error?: string | null };
  result?: { status?: string; data?: Record<string, unknown> } | null;
  input?: Record<string, unknown> | null;
  plan?: { event_time?: string };
}

export interface EditableQuestion {
  name: string;
  label: string;
  type: string;
  required?: boolean;
  options?: string[];
}

export function effectiveRunStatus(run: RunView | null): string {
  const status = run?.agent_run?.status || run?.result?.status || run?.status || '';
  return status === 'queued' ? 'pending' : status;
}

export function mayResumeRun(status: string): boolean {
  return status === 'waiting_input' || status === 'failed';
}

export function missingInformation(run: RunView | null): string[] {
  const items = run?.result?.data?.missing_information;
  return Array.isArray(items)
    ? [...new Set(items.filter((item): item is string => typeof item === 'string' && item.trim().length > 0))]
    : [];
}

/** Operational fields are untrusted; never echo credentials or upstream response dumps. */
export function visibleOperationalError(value: unknown, fallback = '执行未完成，请重试；如仍失败请联系管理者。'): string {
  if (typeof value !== 'string' || !value.trim()) return fallback;
  if (/authorization|api[_-]?key|token|password|secret|jdbc:|postgres(?:ql)?:\/\/|https?:\/\//i.test(value)) return fallback;
  return value.trim().slice(0, 300);
}

export function stepPresentation(status?: string) {
  switch (status) {
    case 'done':
    case 'succeeded': return { label: '已完成', symbol: '✓', color: 'var(--green)' };
    case 'running': return { label: '执行中', symbol: '…', color: 'var(--blue)' };
    case 'waiting_input': return { label: '等待补充', symbol: '待', color: 'var(--amber)' };
    case 'failed': return { label: '失败', symbol: '!', color: 'var(--red)' };
    case 'cancelled': return { label: '已取消', symbol: '×', color: 'var(--muted)' };
    case 'pending':
    case 'queued': return { label: '待执行', symbol: '·', color: 'var(--muted)' };
    default: return { label: status || '状态未记录', symbol: '?', color: 'var(--muted)' };
  }
}

export function answerText(value: unknown): string {
  if (value === null || value === undefined || value === '') return '未填写';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) return value.length ? value.map(answerText).join('、') : '未填写';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

export function questionnaireAnswers(fields: Array<{ name: string; label: string }>, extra: Record<string, unknown> = {}) {
  const seen = new Set<string>(['name', 'contact']);
  const rows: Array<{ name: string; label: string; value: string }> = [];
  for (const field of fields) {
    if (seen.has(field.name)) continue;
    seen.add(field.name);
    rows.push({ name: field.name, label: field.label || field.name, value: answerText(extra[field.name]) });
  }
  for (const [name, value] of Object.entries(extra)) {
    if (seen.has(name)) continue;
    seen.add(name);
    rows.push({ name, label: name + '（历史字段）', value: answerText(value) });
  }
  return rows;
}

export function validateFormFields(fields: EditableQuestion[]): EditableQuestion[] {
  const names = new Set<string>();
  return fields.map((field, index) => {
    const label = field.label.trim();
    if (!/^[A-Za-z][A-Za-z0-9_]{0,30}$/.test(field.name) || names.has(field.name)) {
      throw new Error(`第 ${index + 1} 题的字段标识无效或重复`);
    }
    names.add(field.name);
    if (!label) throw new Error(`请填写第 ${index + 1} 题的题目`);
    const options = (field.options ?? []).map((option) => option.trim());
    if (field.type === 'select' && (!options.length || options.some((option) => !option) || new Set(options).size !== options.length)) {
      throw new Error(`第 ${index + 1} 题需要不重复的非空选项`);
    }
    return { ...field, label, type: field.type === 'select' ? 'select' : 'text', options: field.type === 'select' ? options : [] };
  });
}

/** Public registration always needs these fields; clone without discarding existing labels/options. */
export function registrationQuestions(fields: EditableQuestion[]): EditableQuestion[] {
  const result = fields.map((field) => ({ ...field, options: [...(field.options ?? [])] }));
  for (const standard of [
    { name: 'name', label: '姓名', type: 'text', required: true, options: [] },
    { name: 'contact', label: '联系方式', type: 'text', required: true, options: [] },
  ]) {
    const existing = result.find((field) => field.name === standard.name);
    if (existing) existing.required = true;
    else result.push(standard);
  }
  return result;
}
