import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { api } from '../lib/api';
import { isoToLocal, localToISO } from '../lib/utils';
import { effectiveRunStatus, mayResumeRun, missingInformation, visibleOperationalError, type RunView } from '../lib/activity-view';
import DataState from './DataState';

const LABELS: Record<string, string> = {
  pending: '等待执行', running: '执行中', waiting_input: '等待补充信息',
  succeeded: '执行完成', failed: '执行失败', cancelled: '已取消',
};

export default function RunPanel({ runId, onChanged }: { runId?: string | null; onChanged: () => void | Promise<void> }) {
  const [run, setRun] = useState<RunView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState(false);
  const [eventTime, setEventTime] = useState('');
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const sequence = useRef(0);
  const mounted = useRef(false);
  const actionInFlight = useRef(false);
  const initializedInput = useRef(false);
  const activeRunId = useRef(runId);
  const observedStatus = useRef('');
  const onChangedRef = useRef(onChanged);
  onChangedRef.current = onChanged;

  const load = useCallback(async (initial = false) => {
    if (!runId || activeRunId.current !== runId || actionInFlight.current) return;
    const request = ++sequence.current;
    if (initial) setLoading(true);
    try {
      const result = await api<RunView>(`/runs/${runId}`);
      if (!mounted.current || activeRunId.current !== runId || request !== sequence.current) return;
      const nextStatus = effectiveRunStatus(result);
      const statusChanged = observedStatus.current !== nextStatus;
      observedStatus.current = nextStatus;
      setRun(result);
      setError('');
      if (statusChanged) void Promise.resolve(onChangedRef.current()).catch(() => undefined);
      if (!initializedInput.current && effectiveRunStatus(result) === 'waiting_input') {
        initializedInput.current = true;
        const inputTime = result.input?.event_time ?? result.plan?.event_time;
        setEventTime(isoToLocal(typeof inputTime === 'string' ? inputTime : ''));
        const stored = result.input?.answers;
        if (stored && typeof stored === 'object' && !Array.isArray(stored)) {
          setAnswers(Object.fromEntries(Object.entries(stored).map(([key, value]) => [key, String(value ?? '')])));
        }
      }
    } catch (cause) {
      if (mounted.current && activeRunId.current === runId && request === sequence.current) {
        setError(visibleOperationalError(cause instanceof Error ? cause.message : '', '运行记录加载失败，请重试。'));
      }
    } finally {
      if (mounted.current && activeRunId.current === runId && request === sequence.current) setLoading(false);
    }
  }, [runId]);

  useEffect(() => {
    mounted.current = true;
    activeRunId.current = runId;
    setBusy(false);
    initializedInput.current = false;
    observedStatus.current = '';
    actionInFlight.current = false;
    setRun(null); setError(''); setActionError(''); setEventTime(''); setAnswers({});
    load(true);
    const timer = window.setInterval(() => { load(false); }, 10000);
    return () => { mounted.current = false; sequence.current += 1; window.clearInterval(timer); };
  }, [load]);

  const status = effectiveRunStatus(run);
  const missing = missingInformation(run);
  const timeQuestion = (question: string) => /活动时间|开始时间|活动日期|event_time/.test(question);
  const needsTime = missing.some(timeQuestion);
  const otherQuestions = missing.filter((question) => !timeQuestion(question));

  async function resume(input?: Record<string, unknown>) {
    if (!runId || activeRunId.current !== runId || actionInFlight.current || busy || error || !mayResumeRun(status)) return;
    actionInFlight.current = true;
    sequence.current += 1;
    setBusy(true); setActionError('');
    try {
      if (input) await api(`/runs/${runId}/input`, { method: 'POST', body: JSON.stringify({ input }) });
      await api(`/runs/${runId}/resume`, { method: 'POST' });
      actionInFlight.current = false;
      await load(false);
      await onChanged();
    } catch (cause) {
      if (mounted.current && activeRunId.current === runId) setActionError(visibleOperationalError(cause instanceof Error ? cause.message : '', '暂时未能继续执行，填写内容已保留。'));
      actionInFlight.current = false;
      await load(false);
    } finally {
      actionInFlight.current = false;
      if (mounted.current && activeRunId.current === runId) setBusy(false);
    }
  }

  function submitInput(event: FormEvent) {
    event.preventDefault();
    try {
      const input: Record<string, unknown> = {};
      if (eventTime) input.event_time = localToISO(eventTime);
      if (otherQuestions.length) input.answers = Object.fromEntries(otherQuestions.map((question) => [question, (answers[question] ?? '').trim()]));
      if (!Object.keys(input).length) {
        setActionError('请先补充页面提示的信息。');
        return;
      }
      resume(input);
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : '补充信息格式无效');
    }
  }

  return (
    <section className="ws-panel wide" aria-label="工作流状态">
      <div className="ws-panel-title">
        <div><h2>工作流状态</h2><p>{runId ? LABELS[status] || (loading ? '正在读取运行状态' : '等待状态信息') : '该活动没有运行记录'}</p></div>
        {runId && <button className="ws-button light" type="button" disabled={busy || loading} onClick={() => load(!run)}>刷新运行状态</button>}
      </div>
      <DataState loading={loading && !run} error={error || null} onRetry={() => load(!run)} loadingText="正在读取运行记录…" />
      {run && (
        <>
          {run.agent_run?.phase && <p>当前阶段：{({ planning: '规划', execution: '执行', delivery: '交付' } as Record<string, string>)[run.agent_run.phase] || run.agent_run.phase}</p>}
          {status === 'failed' && <p className="reason-box" role="alert">{visibleOperationalError(run.agent_run?.error ?? run.error)}</p>}
          {actionError && <p className="auth-error" role="alert">{actionError}</p>}
          {status === 'waiting_input' && (
            <form className="ws-form" onSubmit={submitInput}>
              {missing.length > 0 && <ul>{missing.map((question) => <li key={question}>{question}</li>)}</ul>}
              <label>
                活动时间{needsTime ? '（必填）' : '（需要时补充）'}
                <input type="datetime-local" step="any" value={eventTime} required={needsTime} disabled={busy}
                  onChange={(event) => setEventTime(event.target.value)} />
              </label>
              {otherQuestions.map((question) => (
                <label key={question}>
                  {question}
                  <input value={answers[question] ?? ''} required disabled={busy}
                    onChange={(event) => setAnswers((old) => ({ ...old, [question]: event.target.value }))} />
                </label>
              ))}
              <p>定时刷新不会覆盖正在填写的补充信息。保存并继续后，将复用已完成的步骤。</p>
              <button className="ws-button" type="submit" disabled={busy || !!error}>{busy ? '正在继续…' : '保存补充信息并继续'}</button>
            </form>
          )}
          {status === 'failed' && <button className="ws-button" type="button" disabled={busy || !!error} onClick={() => resume()}>{busy ? '正在重新排队…' : '从失败处重试'}</button>}
          {(status === 'pending' || status === 'running') && <p role="status">流程正在处理，页面会自动刷新。请勿重复发布。</p>}
          {status === 'succeeded' && <p>流程已完成，可在下方查看表单、任务和投递结果。</p>}
          {status === 'cancelled' && <p>流程已取消，不能恢复。</p>}
        </>
      )}
    </section>
  );
}
