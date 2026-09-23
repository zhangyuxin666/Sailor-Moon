/**
 * 工具函数
 * 对应旧 frontend 中 App.date()、App.escape()、formatDate() 等。
 */

/** 格式化日期为中文短格式，如 "12月5日 14:30" */
export function formatDate(value?: string | null): string {
  if (!value) return '时间待定';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

/** 格式化日期为中文长格式（含星期），如 "12月5日 周四 14:30" */
export function formatDateLong(value?: string | null): string {
  if (!value) return '时间待定';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

/** 格式化日期为完整日期时间（公开报名页用） */
export function formatDateFull(value?: string | null): string {
  if (!value) return '时间待定';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'full',
    timeStyle: 'short',
  }).format(date);
}

/** 将 datetime-local 输入值（"2026-01-01T12:00"）转为 ISO 字符串 */
export function localToISO(value: string): string {
  return value ? new Date(value).toISOString() : '';
}

/** 将 ISO 字符串转为 datetime-local 输入值 */
export function isoToLocal(value?: string): string {
  return value ? value.slice(0, 16) : '';
}
