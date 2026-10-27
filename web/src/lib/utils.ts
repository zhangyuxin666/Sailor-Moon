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

/**
 * Parse ISO date-times only. Values without an offset are browser-local wall time,
 * matching datetime-local inputs; API writes always carry an explicit UTC offset.
 * Calendar overflows and nonexistent local times (DST gaps) are rejected.
 */
function parseDateTime(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?(Z|[+-]\d{2}:\d{2})?$/i.exec(value);
  if (!match) return null;
  const [, yearText, monthText, dayText, hourText, minuteText, secondText, fraction, zone] = match;
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText ?? 0);
  const millisecond = Number((fraction ?? '').padEnd(3, '0'));
  if (year < 1 || month < 1 || month > 12 || day < 1 ||
      day > new Date(Date.UTC(year, month, 0)).getUTCDate() ||
      hour > 23 || minute > 59 || second > 59) return null;
  if (zone) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date;
  }
  const date = new Date(0);
  date.setFullYear(year, month - 1, day);
  date.setHours(hour, minute, second, millisecond);
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 ||
      date.getDate() !== day || date.getHours() !== hour ||
      date.getMinutes() !== minute || date.getSeconds() !== second) return null;
  return date;
}

/** datetime-local uses browser-local time; empty fields stay empty. */
export function localToISO(value: string): string {
  if (!value) return '';
  // An offset is not a valid datetime-local value. Do not silently reinterpret it.
  const date = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value) ? null : parseDateTime(value);
  if (!date) throw new Error('请输入有效的日期时间');
  return date.toISOString();
}

/** Convert an ISO instant to local input fields without discarding seconds. */
export function isoToLocal(value?: string | null): string {
  if (!value) return '';
  const date = parseDateTime(value);
  if (!date) return '';
  const pad = (part: number, width = 2) => String(part).padStart(width, '0');
  const day = [pad(date.getFullYear(), 4), pad(date.getMonth() + 1), pad(date.getDate())].join('-');
  let time = [pad(date.getHours()), pad(date.getMinutes())].join(':');
  if (date.getSeconds() || date.getMilliseconds()) time += ':' + pad(date.getSeconds());
  if (date.getMilliseconds()) time += '.' + pad(date.getMilliseconds(), 3);
  return day + 'T' + time;
}
