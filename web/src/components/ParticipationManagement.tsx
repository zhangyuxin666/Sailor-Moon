import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, useApi } from '../lib/api';
import { formatDate } from '../lib/utils';
import DataState from './DataState';
import type { ParticipationManagementData } from '../types/participation';

interface Props {
  activityId: string;
  status: string;
  onChanged?: () => Promise<unknown>;
}

type MemberFilter = 'all' | 'present' | 'absent';
const fieldsetStyle = { border: 0, margin: 0, padding: 0, minWidth: 0, display: 'grid', gap: 12 };

export default function ParticipationManagement({ activityId, status, onChanged }: Props) {
  const [revision, setRevision] = useState(0);
  const { data, loading, error: loadError, reload } = useApi(
    () => api<ParticipationManagementData>(`/activities/${encodeURIComponent(activityId)}/participation`),
    [activityId, revision],
  );
  const [attendanceOpen, setAttendanceOpen] = useState(false);
  const [feedbackOpen, setFeedbackOpen] = useState(false);
  const [settingsVersion, setSettingsVersion] = useState<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);
  const [filter, setFilter] = useState<MemberFilter>('all');
  const [busy, setBusy] = useState('');
  const [actionError, setActionError] = useState('');
  const [message, setMessage] = useState('');
  const inFlight = useRef(false);
  const requests = useRef(new Map<string, string>());
  const mounted = useRef(false);
  const activeId = useRef(activityId);
  activeId.current = activityId;

  useEffect(() => {
    mounted.current = true;
    dirtyRef.current = false;
    setDirty(false);
    setSettingsVersion(null);
    setAttendanceOpen(false);
    setFeedbackOpen(false);
    setActionError('');
    setMessage('');
    setBusy('');
    return () => { mounted.current = false; };
  }, [activityId]);

  useEffect(() => {
    if (data?.activity_id !== activityId || dirtyRef.current) return;
    setAttendanceOpen(data.attendance_open);
    setFeedbackOpen(data.feedback_open);
    setSettingsVersion(data.version);
  }, [data, activityId]);

  function markDirty() {
    dirtyRef.current = true;
    setDirty(true);
  }

  async function mutate(key: string, path: string, body: Record<string, unknown>, method = 'POST') {
    if (inFlight.current) return;
    const requestedActivity = activityId;
    const fingerprint = JSON.stringify({ path, body });
    if (!requests.current.has(fingerprint)) requests.current.set(fingerprint, crypto.randomUUID());
    inFlight.current = true;
    setBusy(key);
    setActionError('');
    setMessage('');
    try {
      await api(path, { method, body: JSON.stringify({ ...body, request_id: requests.current.get(fingerprint) }) });
      if (!mounted.current || activeId.current !== requestedActivity) return;
      if (key === 'settings') {
        dirtyRef.current = false;
        setDirty(false);
      }
      setMessage(key === 'settings' ? '签到与反馈设置已保存。' : '签到已记录，重复操作不会重复计数。');
      setRevision(value => value + 1);
      if (onChanged) {
        try { await onChanged(); }
        catch { if (mounted.current) setActionError('操作已保存，关联页面暂未刷新，可重新加载当前数据。'); }
      }
    } catch (cause) {
      if (mounted.current && activeId.current === requestedActivity) {
        setActionError(cause instanceof Error ? cause.message : '操作失败，输入已保留，可重试原请求。');
      }
    } finally {
      inFlight.current = false;
      if (mounted.current && activeId.current === requestedActivity) setBusy('');
    }
  }

  function saveSettings(event: FormEvent) {
    event.preventDefault();
    if (settingsVersion === null) return;
    void mutate('settings', `/activities/${encodeURIComponent(activityId)}/participation/settings`, {
      expected_version: settingsVersion,
      attendance_open: status === 'ready' && attendanceOpen,
      feedback_open: feedbackOpen,
    }, 'PUT');
  }

  function restoreSettings() {
    if (busy || (dirtyRef.current && !window.confirm('重新读取将放弃尚未保存的签到与反馈设置，确认继续？'))) return;
    dirtyRef.current = false;
    setDirty(false);
    if (data?.activity_id === activityId) {
      setAttendanceOpen(data.attendance_open);
      setFeedbackOpen(data.feedback_open);
      setSettingsVersion(data.version);
    }
    reload();
  }

  const current = data?.activity_id === activityId ? data : null;
  const canCheckIn = status === 'ready';
  const canFeedback = status === 'ready' || status === 'finished';
  const members = (current?.participants ?? []).filter(member => filter === 'all'
    || (filter === 'present' ? member.checked_in : !member.checked_in && (!current?.roster_confirmed || member.rostered !== false)));
  const summary = current?.summary;
  const average = summary && summary.feedback_count > 0 && typeof summary.rating_average === 'number'
    ? summary.rating_average.toFixed(2) : '暂无评分';

  return (
    <section className="ws-panel wide" aria-label="签到与反馈管理">
      <div className="ws-panel-title">
        <div><h2>签到、缺席与活动反馈</h2><p>签到按活动成员快照记录；反馈原文仅负责人可见，复盘只使用汇总。</p></div>
        <button className="ws-button light" type="button" disabled={!!busy || loading} onClick={reload}>刷新签到与反馈</button>
      </div>
      <DataState loading={loading} error={loadError} onRetry={reload} loadingText="正在读取签到与反馈…" />
      {actionError && <p className="auth-error" role="alert">{actionError}</p>}
      {message && <p role="status">{message}</p>}
      {current && <>
        <form className="ws-form" onSubmit={saveSettings}>
          <fieldset disabled={!!busy || loading || !canFeedback} style={fieldsetStyle}>
            <div className="ws-form-row">
              <label>签到开关<select value={canCheckIn && attendanceOpen ? 'open' : 'closed'} disabled={!canCheckIn}
                onChange={event => { setAttendanceOpen(event.target.value === 'open'); markDirty(); }}>
                <option value="closed">关闭签到</option><option value="open">开放签到</option>
              </select></label>
              <label>反馈开关<select value={feedbackOpen ? 'open' : 'closed'}
                onChange={event => { setFeedbackOpen(event.target.value === 'open'); markDirty(); }}>
                <option value="closed">关闭反馈</option><option value="open">开放反馈</option>
              </select></label>
            </div>
            <p>第一次开放时保存成员快照；保存开放设置会追加当前有效成员，离开班级的历史记录保留。活动结束后不能签到，仍可开放反馈。</p>
            {dirty && <p role="status">设置尚未保存；刷新数据不会覆盖当前修改。</p>}
            <button className="ws-button" type="submit" disabled={settingsVersion === null}>保存签到与反馈设置</button>
          </fieldset>
          <button className="ws-button light" type="button" disabled={!!busy || loading} onClick={restoreSettings}>重新读取已保存设置</button>
        </form>
        {summary && <div className="reason-box" style={{ marginTop: 16 }}>
          <p>成员快照 {summary.eligible} 人 · 已签到 {summary.checked_in} 人 · 缺席 {summary.absent} 人</p>
          <p>已反馈 {summary.feedback_count} 人 · 平均评分 {average}{average !== '暂无评分' ? ' / 5' : ''}</p>
          <p>评分分布：{[1, 2, 3, 4, 5].map(rating => `${rating}分 ${summary.rating_counts?.[String(rating)] ?? 0}人`).join('；')}</p>
        </div>}
        <div className="ws-panel-title" style={{ marginTop: 20 }}>
          <h3>到场与缺席名单</h3>
          <label>查看成员<select className="ws-select" value={filter} onChange={event => setFilter(event.target.value as MemberFilter)}>
            <option value="all">全部成员快照</option><option value="present">已签到</option><option value="absent">缺席</option>
          </select></label>
        </div>
        <div className="ws-list">
          {!members.length && <div className="ws-empty">暂无符合条件的成员</div>}
          {members.map(member => <article className="ws-list-item" key={member.account_id}
            style={{ gridTemplateColumns: 'minmax(0, 1fr) auto' }}>
            <span style={{ minWidth: 0 }}><strong style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>{member.display_name}</strong>
              <small style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>{member.student_no || member.username || member.account_id}</small>
              <small>{member.checked_in ? '已签到' : '缺席'}{member.checked_in_at ? ' · ' + formatDate(member.checked_in_at) : ''} · {member.feedback_submitted ? '已反馈' : '未反馈'}</small>
              {member.eligible === false && <small style={{ whiteSpace: 'normal' }}>{member.current_member ? '尚未加入成员快照，请再次保存开放设置' : '已不在当前班级，保留历史记录'}</small>}
            </span>
            <button className="ws-button light" type="button"
              aria-label={`代签：${member.display_name}（${member.student_no || member.username || member.account_id}）`}
              disabled={!!busy || loading || member.checked_in || member.eligible === false || !current.attendance_open || !canCheckIn}
              onClick={() => void mutate(member.account_id, `/activities/${encodeURIComponent(activityId)}/check-ins`, { account_id: member.account_id })}>
              {busy === member.account_id ? '正在记录…' : member.checked_in ? '已签到' : '代为签到'}
            </button>
          </article>)}
        </div>
        <details style={{ marginTop: 20 }}>
          <summary>查看反馈原文（仅活动负责人）</summary>
          <p>原文用于人工改进，不会发送给复盘模型。</p>
          <div className="ws-list">
            {!current.feedback.length && <div className="ws-empty">暂无反馈</div>}
            {current.feedback.map(feedback => <article className="reason-box" key={feedback.account_id}>
              <strong>{feedback.display_name} · {feedback.rating} / 5</strong>
              {feedback.updated_at && <p>{formatDate(feedback.updated_at)}</p>}
              <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{feedback.comment || '未填写文字反馈'}</p>
            </article>)}
          </div>
        </details>
      </>}
    </section>
  );
}
