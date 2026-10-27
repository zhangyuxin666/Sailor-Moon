import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { formatDate } from '../lib/utils';
import DataState from '../components/DataState';
import type { MyParticipation, ParticipationActivity } from '../types/participation';

const statusLabels: Record<string, string> = {
  queued: '排队中', running: '执行中', waiting_input: '待确认', ready: '已就绪',
  finished: '已结束', cancelled: '已取消', failed: '执行失败',
};
const fieldsetStyle = { border: 0, margin: 0, padding: 0, minWidth: 0, display: 'grid', gap: 12 };

interface DetailProps {
  activityId: string;
  onChanged: () => void;
  onDirtyChange: (dirty: boolean) => void;
  onBusyChange: (busy: boolean) => void;
}

function ParticipationDetail({ activityId, onChanged, onDirtyChange, onBusyChange }: DetailProps) {
  const [revision, setRevision] = useState(0);
  const { data, loading, error: loadError, reload } = useApi(
    () => api<MyParticipation>(`/activities/${encodeURIComponent(activityId)}/participation/me`),
    [activityId, revision],
  );
  const lastData = useRef<MyParticipation | null>(null);
  if (data?.activity.id === activityId) lastData.current = data;
  const me = lastData.current?.activity.id === activityId ? lastData.current : null;
  const [rating, setRating] = useState('');
  const [comment, setComment] = useState('');
  const [feedbackVersion, setFeedbackVersion] = useState(0);
  const [busy, setBusy] = useState('');
  const [actionError, setActionError] = useState('');
  const [message, setMessage] = useState('');
  const dirtyRef = useRef(false);
  const [dirty, setDirty] = useState(false);
  const mounted = useRef(false);
  const inFlight = useRef(false);
  const requests = useRef(new Map<string, string>());

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    if (data?.activity.id !== activityId || dirtyRef.current) return;
    setRating(data.feedback ? String(data.feedback.rating) : '');
    setComment(data.feedback?.comment ?? '');
    setFeedbackVersion(data.feedback?.version ?? 0);
  }, [data, activityId]);

  function markDirty() {
    dirtyRef.current = true;
    setDirty(true);
    onDirtyChange(true);
  }

  async function mutate(kind: 'check-in' | 'feedback', body: Record<string, unknown>) {
    if (inFlight.current) return;
    const path = `/activities/${encodeURIComponent(activityId)}/${kind === 'check-in' ? 'check-ins' : 'feedback'}`;
    const fingerprint = JSON.stringify({ path, body });
    if (!requests.current.has(fingerprint)) requests.current.set(fingerprint, crypto.randomUUID());
    inFlight.current = true;
    setBusy(kind);
    onBusyChange(true);
    setActionError('');
    setMessage('');
    try {
      await api(path, { method: 'POST', body: JSON.stringify({ ...body, request_id: requests.current.get(fingerprint) }) });
      if (!mounted.current) return;
      if (kind === 'feedback') {
        dirtyRef.current = false;
        setDirty(false);
        onDirtyChange(false);
      }
      setMessage(kind === 'check-in' ? '签到已记录，重复操作不会重复计数。' : '你的反馈已保存，仅活动负责人可查看原文。');
      setRevision(value => value + 1);
      onChanged();
    } catch (cause) {
      if (mounted.current) setActionError(cause instanceof Error ? cause.message : '操作失败，输入已保留，可以重试原请求。');
    } finally {
      inFlight.current = false;
      if (mounted.current) {
        setBusy('');
        onBusyChange(false);
      }
    }
  }

  function saveFeedback(event: FormEvent) {
    event.preventDefault();
    const value = Number(rating);
    if (!Number.isInteger(value) || value < 1 || value > 5) {
      setActionError('请选择1至5分的评分。');
      return;
    }
    void mutate('feedback', { rating: value, comment: comment.trim(), expected_version: feedbackVersion });
  }

  function restoreFeedback() {
    if (busy || (dirtyRef.current && !window.confirm('重新读取会放弃尚未提交的反馈修改，确认继续？'))) return;
    dirtyRef.current = false;
    setDirty(false);
    onDirtyChange(false);
    setRevision(value => value + 1);
  }

  const eligible = me?.eligible !== false;
  const ready = me?.activity.status === 'ready';
  const feedbackStatus = ready || me?.activity.status === 'finished';
  const canSubmitFeedback = !!me && eligible && me.checked_in && me.feedback_open && feedbackStatus;

  return (
    <section className="ws-panel" aria-label="我的签到与反馈">
      <div className="ws-panel-title"><div><h2>{me?.activity.title || '我的签到与反馈'}</h2>
        {me && <p>{formatDate(me.activity.event_time)} · {statusLabels[me.activity.status] || me.activity.status}</p>}
      </div><button className="ws-button light" type="button" disabled={!!busy || loading} onClick={reload}>刷新本人状态</button></div>
      <DataState loading={loading} error={loadError} onRetry={reload} loadingText="正在读取本人参与状态…" />
      {actionError && <p className="auth-error" role="alert">{actionError}</p>}
      {message && <p role="status">{message}</p>}
      {me && <>
        {me.message && <p className="reason-box">{me.message}</p>}
        {!eligible && !me.message && <p className="reason-box">当前账号尚未进入可参与的成员快照，请联系活动负责人确认。</p>}
        <div className="reason-box"><p>签到状态：{me.checked_in ? '已签到' : '未签到'}{me.checked_in_at ? ' · ' + formatDate(me.checked_in_at) : ''}</p>
          <p>{ready ? me.attendance_open ? '签到已开放。' : '签到暂未开放，请等待负责人开启。'
            : me.activity.status === 'finished' ? '活动已结束，不能新增签到。'
              : me.activity.status === 'cancelled' ? '活动已取消，不能签到或提交反馈。' : '当前流程尚未就绪，不能签到。'}</p>
          <button className="ws-button" type="button" disabled={!!busy || loading || !!loadError || !ready || !eligible || !me.attendance_open || me.checked_in}
            onClick={() => void mutate('check-in', {})}>
            {busy === 'check-in' ? '正在签到…' : me.checked_in ? '已签到' : '确认本人签到'}
          </button>
        </div>
        <h3 style={{ marginTop: 20 }}>我的活动反馈</h3>
        <p>评分和文字原文仅活动负责人可见；复盘只使用人数、评分等汇总，不发送反馈原文。</p>
        {!me.checked_in && <p>请先完成签到，再提交反馈。</p>}
        {!me.feedback_open && <p>反馈暂未开放，已填写内容会保留。</p>}
        {dirty && <p role="status">反馈有未保存的修改；刷新本人状态不会覆盖输入。</p>}
        <form className="ws-form" onSubmit={saveFeedback}>
          <fieldset disabled={!!busy || loading || !!loadError || !canSubmitFeedback} style={fieldsetStyle}>
            <label>评分（1至5分）<select required value={rating} onChange={event => { setRating(event.target.value); markDirty(); }}>
              <option value="">请选择评分</option>{[1, 2, 3, 4, 5].map(value => <option value={value} key={value}>{value}分</option>)}
            </select></label>
            <label>反馈内容（选填，最多2000字）<textarea rows={5} maxLength={2000} value={comment}
              onChange={event => { setComment(event.target.value); markDirty(); }} /></label>
            <button className="ws-button" type="submit">{busy === 'feedback' ? '正在保存…' : me.feedback ? '更新我的反馈' : '提交反馈'}</button>
          </fieldset>
          <button className="ws-button light" type="button" disabled={!!busy || loading} onClick={restoreFeedback}>重新读取已提交反馈</button>
        </form>
        {me.feedback && <p>已有一份反馈，当前保存版本 {me.feedback.version}。重新提交会更新这份反馈，不重复计人数。</p>}
      </>}
    </section>
  );
}

export default function Participation() {
  const [params] = useSearchParams();
  const [selectedId, setSelectedId] = useState<string | null>(params.get('activity'));
  const [detailDirty, setDetailDirty] = useState(false);
  const [detailBusy, setDetailBusy] = useState(false);
  const { data, loading, error, reload } = useApi(
    () => api<{ activities: ParticipationActivity[] }>('/participation/activities'), [],
  );
  const activities = data?.activities ?? [];

  useEffect(() => {
    if (!selectedId && data?.activities.length) setSelectedId(data.activities[0].id);
  }, [data, selectedId]);

  function choose(id: string) {
    if (detailBusy || selectedId === id) return;
    if (detailDirty && !window.confirm('切换活动会放弃尚未提交的反馈修改，确认继续？')) return;
    setDetailDirty(false);
    setSelectedId(id);
  }

  return (
    <>
      <header className="ws-header"><div><p className="ws-kicker">PARTICIPATION</p><h1>活动签到与反馈</h1><p>这里只显示当前账号有权参与的活动，签到和反馈均记录到本人账号。</p></div></header>
      <div className="ws-grid">
        <section className="ws-panel" aria-label="可参与活动">
          <div className="ws-panel-title"><h2>我的活动</h2><button className="ws-button light" type="button" disabled={loading || detailBusy} onClick={reload}>刷新我的活动</button></div>
          <DataState loading={loading} error={error} onRetry={reload} empty={!loading && !error && !activities.length} emptyText="暂无可参与活动，请联系负责人确认班级成员关系。" />
          <div className="ws-list">{activities.map(activity => <button className="ws-list-item" type="button" key={activity.id}
            style={{ gridTemplateColumns: 'minmax(0, 1fr)', borderColor: selectedId === activity.id ? 'var(--line-strong)' : undefined }}
            aria-pressed={selectedId === activity.id} disabled={detailBusy} onClick={() => choose(activity.id)}>
            <span><strong style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>{activity.title}</strong>
              <small>{formatDate(activity.event_time)} · {statusLabels[activity.status] || activity.status}</small>
              <small style={{ whiteSpace: 'normal' }}>{activity.checked_in ? '已签到' : '未签到'} · {activity.feedback_submitted ? '已反馈' : '未反馈'} · {activity.attendance_open ? '签到开放' : '签到关闭'} / {activity.feedback_open ? '反馈开放' : '反馈关闭'}</small>
            </span>
          </button>)}</div>
        </section>
        {selectedId ? <ParticipationDetail key={selectedId} activityId={selectedId} onChanged={reload}
          onDirtyChange={setDetailDirty} onBusyChange={setDetailBusy} />
          : <section className="ws-panel"><div className="ws-empty">选择一个活动查看本人签到和反馈。</div></section>}
      </div>
    </>
  );
}
