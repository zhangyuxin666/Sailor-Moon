/** Activity delivery, results and recoverable workflow status. */
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '../lib/api';
import { useToast } from '../components/Toast';
import DataState from '../components/DataState';
import RecapPanel from '../components/RecapPanel';
import ParticipationManagement from '../components/ParticipationManagement';
import ExportActions from '../components/ExportActions';
import GenerationInfo from '../components/GenerationInfo';
import RunPanel from '../components/RunPanel';
import ActivityManagement from '../components/ActivityManagement';
import { formatDate } from '../lib/utils';
import { questionnaireAnswers, stepPresentation, visibleOperationalError } from '../lib/activity-view';
import type { ActivityDetail, ActivityTask, FormStats } from '../types';

const STEP_LABELS: Record<string, string> = {
  generate_plan: '确认活动策划', create_form: '创建报名问卷', assign_tasks: '分配任务并通知',
  create_calendar_event: '创建日历事件', schedule_reminder: '安排定时提醒',
  publish_qq: '发布到 QQ 活动群', complete_delivery: '汇总交付结果',
};
const DELIVERY_KIND_LABELS: Record<string, string> = {
  announcement: '活动发布', reminder: '定时提醒', manual: '手动催办',
};
const STATUS_LABELS: Record<string, string> = {
  queued: '排队中', pending: '等待执行', running: '执行中', waiting_input: '等待补充信息',
  ready: '已就绪', succeeded: '已完成', failed: '失败', finished: '已结束', cancelled: '已取消',
};

export default function ActivityDetail() {
  const { id } = useParams<{ id: string }>();
  const { showToast } = useToast();
  const [detail, setDetail] = useState<ActivityDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [stats, setStats] = useState<FormStats | null>(null);
  const [statsError, setStatsError] = useState('');
  const [statsLoading, setStatsLoading] = useState(false);
  const [messageContent, setMessageContent] = useState('');
  const [sending, setSending] = useState(false);
  const [checkingDelivery,setCheckingDelivery]=useState<string|null>(null);
  const [pendingTaskId, setPendingTaskId] = useState<string | null>(null);
  const [nudgingTaskId, setNudgingTaskId] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [refreshingStats, setRefreshingStats] = useState(false);
  const messageRequests=useRef(new Map<string,string>());
  const requestSequence = useRef(0);
  const mounted = useRef(false);
  const activeId = useRef(id);

  const load = useCallback(async (silent = false): Promise<boolean> => {
    if (!id || activeId.current !== id) return false;
    const request = ++requestSequence.current;
    if (!silent) setLoading(true);
    const current = () => mounted.current && activeId.current === id && request === requestSequence.current;
    try {
      const result = await api<ActivityDetail>(`/activities/${id}`);
      if (!current()) return false;
      setDetail(result);
      setLoadError('');
      if (result.forms?.length) {
        setStatsLoading(true);
        try {
          const nextStats = await api<FormStats>(`/activities/${id}/form-stats`);
          if (!current()) return false;
          setStats(nextStats);
          setStatsError('');
        } catch (cause) {
          if (current()) setStatsError(visibleOperationalError(cause instanceof Error ? cause.message : '', '报名数据加载失败，请重试。'));
          return false;
        } finally {
          if (current()) setStatsLoading(false);
        }
      } else {
        setStats(null);
        setStatsError('');
        setStatsLoading(false);
      }
      return true;
    } catch (cause) {
      if (current()) setLoadError(visibleOperationalError(cause instanceof Error ? cause.message : '', '活动加载失败，请重试。'));
      return false;
    } finally {
      if (current()) { setLoading(false); setStatsLoading(false); }
    }
  }, [id]);

  async function checkDelivery(deliveryId:string){
    if(checkingDelivery)return;setCheckingDelivery(deliveryId);
    try{const result=await api<{status:string;message:string}>(`/activities/${id}/deliveries/${deliveryId}/check`,{method:'POST'});showToast(result.message,result.status==='sent'?'success':'error');if(result.status==='sent'){const original=detail?.deliveries?.find(item=>item.id===deliveryId);if(original){setMessageContent(value=>value.trim()===original.content?'':value);messageRequests.current.delete(id+':'+original.content);}}await load(true);}
    catch(cause){showToast(cause instanceof Error?cause.message:'核对失败，请重试','error');}finally{setCheckingDelivery(null);}
  }
  useEffect(() => {
    mounted.current = true;
    activeId.current = id;
    setDetail(null); setStats(null); setLoadError(''); setStatsError(''); setStatsLoading(false);
    setMessageContent(''); setPendingTaskId(null); setNudgingTaskId(null);
    setSending(false); setCancelling(false); setRefreshingStats(false);
    setLoading(true);
    load(false);
    const timer = window.setInterval(() => { load(true); }, 10000);
    return () => { mounted.current = false; requestSequence.current += 1; window.clearInterval(timer); };
  }, [id, load]);

  async function toggleTask(task: ActivityTask) {
    const next = task.status === 'done' ? 'pending' : 'done';
    setPendingTaskId(task.id);
    try {
      await api(`/tasks/${task.id}`, { method: 'PATCH', body: JSON.stringify({ status: next }) });
      await load(true);
      showToast(next === 'done' ? '任务已完成' : '任务已恢复');
    } catch (cause) {
      showToast(visibleOperationalError(cause instanceof Error ? cause.message : '', '操作失败'), 'error');
    } finally {
      if (mounted.current && activeId.current === id) setPendingTaskId(null);
    }
  }

  async function deliverManual(content:string){
    if(detail?.deliveries?.some(item=>item.content===content&&['unknown','sending'].includes(item.status)))throw new Error('投递结果未知，请先核对已有投递记录，再决定后续操作');
    const fingerprint=id+':'+content;
    if(!messageRequests.current.has(fingerprint))messageRequests.current.set(fingerprint,crypto.randomUUID());
    await api(`/activities/${id}/messages`,{method:'POST',body:JSON.stringify({content,request_id:messageRequests.current.get(fingerprint)})});
    messageRequests.current.delete(fingerprint);
  }
  async function nudgeTask(task: ActivityTask) {
    setNudgingTaskId(task.id);
    try {
      await deliverManual(`【任务催办】请 ${task.assignee} 尽快完成「${task.title}」，完成后向活动负责人反馈进度。`);
      await load(true);
      showToast(`已在 QQ 群催办 ${task.assignee}`);
    } catch (cause) {
      showToast(visibleOperationalError(cause instanceof Error ? cause.message : '', '催办失败'), 'error');
      await load(true);
    } finally {
      if (mounted.current && activeId.current === id) setNudgingTaskId(null);
    }
  }

  async function sendMessage(event: FormEvent) {
    event.preventDefault();
    const content = messageContent.trim();
    if (!content) { showToast('请填写通知内容', 'error'); return; }
    setSending(true);
    try {
      await deliverManual(content);
      if (activeId.current === id) setMessageContent('');
      await load(true);
      showToast('消息已发送到 QQ 群');
    } catch (cause) {
      showToast(visibleOperationalError(cause instanceof Error ? cause.message : '', '发送失败，通知内容已保留'), 'error');
      await load(true);
    } finally {
      if (mounted.current && activeId.current === id) setSending(false);
    }
  }

  async function cancelReminder(reminderId: string) {
    setCancelling(true);
    try {
      const result=await api<{status:string}>(`/reminders/${reminderId}/cancel`, { method: 'POST' });
      await load(true); showToast(result.status==='sent'?'提醒已经发送，不能撤回':'提醒已取消');
    } catch (cause) {
      showToast(visibleOperationalError(cause instanceof Error ? cause.message : '', '取消失败'), 'error');
    } finally {
      if (mounted.current && activeId.current === id) setCancelling(false);
    }
  }

  async function retryReminder(reminderId:string){
    if(cancelling)return;setCancelling(true);
    try{await api(`/activities/${id}/reminders/${reminderId}/retry`,{method:'POST'});await load(true);showToast('提醒已重新排队，使用原投递标识');}
    catch(cause){showToast(cause instanceof Error?cause.message:'重试失败','error');}finally{setCancelling(false);}
  }
  async function refreshStats() {
    setRefreshingStats(true);
    try { if (await load(true)) showToast('报名数据已刷新'); }
    finally { if (mounted.current && activeId.current === id) setRefreshingStats(false); }
  }

  async function copyFormLink(formId: string) {
    try {
      await navigator.clipboard.writeText(`${window.location.origin}/forms/${formId}`);
      showToast('报名链接已复制');
    } catch { showToast('复制失败，请手动复制报名链接', 'error'); }
  }

  async function cancelRegistration(registrationId:number){
    const reason=window.prompt('请填写取消这条报名的原因（取消后释放名额）：');if(!reason?.trim())return;
    try{await api(`/activities/${id}/registrations/${registrationId}/cancel`,{method:'POST',body:JSON.stringify({reason,request_id:crypto.randomUUID()})});await load(true);showToast('报名已取消，名额已释放');}
    catch(cause){showToast(cause instanceof Error?cause.message:'取消失败','error');}
  }
  if (!id) return <DataState error="活动不存在。" />;
  if (!detail) return <DataState loading={loading} loadingText="正在载入活动…" error={loadError || (!loading ? '活动暂时无法读取。' : null)} onRetry={() => load(false)} />;

  const { activity } = detail;
  const plan = detail.plan ?? {};
  const tasks = detail.tasks ?? [];
  const steps = detail.steps ?? [];
  const deliveries = detail.deliveries ?? [];
  const reminders = detail.reminders ?? [];
  const forms = detail.forms ?? [];
  const title = plan.title || activity.title || '未命名活动';
  const doneCount = tasks.filter((task) => task.status === 'done').length;
  const sentCount = deliveries.filter((delivery) => delivery.status === 'sent').length;
  const failedCount = deliveries.filter((delivery) => delivery.status === 'failed').length;
  const materials = plan.materials ?? [];
  const fields = stats?.fields ?? [];
  const activityState = stepPresentation(activity.status === 'ready' || activity.status === 'finished' ? 'succeeded' : activity.status);

  return (
    <>
      <header className="ws-header">
        <div><p className="ws-kicker">ACTIVITY</p><h1>{title}</h1>
          <p>{activity.raw_input ? `${activity.raw_input} · ` : ''}{formatDate(activity.created_at)}</p>
        </div>
        <span className="ws-tag" style={{ color: activityState.color }}>{STATUS_LABELS[activity.status] || activity.status || '状态未记录'}</span>
      </header>
      {loadError && <DataState error={'刷新失败，以下保留上次数据：' + loadError} onRetry={() => load(true)} />}
      <ExportActions activityId={id}/>
      <GenerationInfo value={plan.generation}/>
      <div className="ws-grid">
        <RunPanel key={detail.run_id ?? id} runId={detail.run_id} onChanged={() => load(true).then(() => undefined)} />
        <ActivityManagement id={id} detail={detail} stats={stats} onChanged={() => load(true)} />
        <section className="ws-panel wide">
          <div className="ws-panel-title"><div><h2>活动策划</h2><p>活动时间 {formatDate(plan.event_time)}</p></div>
            <a className="ws-button light" href={`/activities/${id}/calendar.ics`}>下载日历</a>
          </div>
          <p className="plan-description">{plan.description || '暂无活动说明'}</p>
          <h3 className="panel-sub">物料清单</h3>
          {materials.length ? <div className="tag-list">{materials.map((material, index) => <span key={index} className="tag">{material}</span>)}</div> : <div className="ws-empty">暂无物料</div>}
        </section>

        <section className="ws-panel">
          <div className="ws-panel-title"><div><h2>任务分工</h2><p>标记完成或催办 · {doneCount}/{tasks.length}</p></div></div>
          {tasks.length ? <div className="task-list">{tasks.map((task) => (
            <div key={task.id} className={`task-item ${task.status === 'done' ? 'done' : ''}`}>
              <input className="task-toggle" type="checkbox" aria-label={`完成任务：${task.title}`}
                checked={task.status === 'done'} disabled={pendingTaskId === task.id || activity.status === 'cancelled'} onChange={() => toggleTask(task)} />
              <span><strong>{task.title}</strong><small>负责人 {task.assignee}</small></span>
              <button className="task-nudge" type="button" disabled={nudgingTaskId === task.id || activity.status === 'cancelled'} onClick={() => nudgeTask(task)}>催一下</button>
            </div>
          ))}</div> : <div className="ws-empty">暂无任务</div>}
        </section>

        <section className="ws-panel">
          <div className="ws-panel-title"><div><h2>报名管理</h2><p>公开链接无需登录即可填写 · {stats ? `${stats.count} 人已报名` : '尚未取得统计'}</p></div>
            <button className="ws-button light" type="button" disabled={refreshingStats || statsLoading || !forms.length} onClick={refreshStats}>刷新报名数据</button>
          </div>
          {!forms.length ? <div className="ws-empty">还没有报名问卷</div> : <>
            <button className="ws-button light" type="button" onClick={() => copyFormLink(forms[0].id)}>复制报名链接</button>
            <DataState loading={statsLoading && !stats} error={statsError || null} loadingText="正在读取报名回答…" onRetry={refreshStats} />
            {stats && stats.registrations.length > 0 && <div className="reg-list">{stats.registrations.map((registration, index) => (
              <article key={index} className="reg-row">
                <strong>{registration.name}{registration.status === 'cancelled' ? '（已取消）' : ''}</strong>{registration.id && registration.status !== 'cancelled' && <button className="ws-button light" type="button" onClick={()=>cancelRegistration(registration.id!)}>取消该报名：{registration.name}</button>}<span>{registration.contact}</span><span>{formatDate(registration.created_at)}</span>
                <dl style={{ gridColumn: '1 / -1', margin: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                  {questionnaireAnswers(fields, registration.extra ?? {}).map((answer) => (
                    <div key={answer.name} style={{ marginTop: 8 }}><dt style={{ fontWeight: 650 }}>{answer.label}</dt><dd style={{ margin: '3px 0 0' }}>{answer.value}</dd></div>
                  ))}
                </dl>
              </article>
            ))}</div>}
            {stats && !stats.registrations.length && !statsError && <div className="ws-empty">还没有人报名</div>}
          </>}
        </section>

        <section className="ws-panel">
          <div className="ws-panel-title"><div><h2>自动执行记录</h2><p>显示每一步的真实状态、尝试次数和结果</p></div></div>
          {steps.length ? <div className="step-list">{steps.map((step, index) => {
            const state = stepPresentation(step.status);
            return <div key={index} className="step-item">
              <span className="step-check" aria-hidden="true" style={{ background: state.color }}>{state.symbol}</span>
              <div><strong>{STEP_LABELS[step.step] || step.step} · {state.label}</strong>
                <small>{step.phase ? `阶段：${step.phase} · ` : ''}尝试 {step.attempts ?? 1} 次</small>
                {step.detail && <small>{visibleOperationalError(step.detail, '详情请查看运行状态')}</small>}
                {step.error && <small role="alert" style={{ color: 'var(--red)' }}>{visibleOperationalError(step.error)}</small>}
              </div>
            </div>;
          })}</div> : <div className="ws-empty">暂无执行记录</div>}
        </section>

        <section className="ws-panel">
          <div className="ws-panel-title"><div><h2>定时提醒</h2><p>失败可重试；未知结果须先核对，已发送记录不能撤回。</p></div></div>
          {reminders.length ? <div className="ws-list">{reminders.map(item=><div className="reminder-card" key={item.id}><strong>{formatDate(item.remind_at)} · {({scheduled:'已安排',sent:'已发送',cancelled:'已取消',failed:'失败',unknown:'待核对'} as Record<string,string>)[item.status]||item.status}</strong><span>{item.message}</span>
            {item.error&&<p role="alert">{item.error}</p>}
            {item.status==='scheduled'&&<button className="ws-button danger" type="button" disabled={cancelling} onClick={()=>cancelReminder(item.id)}>取消提醒</button>}
            {item.status==='failed'&&activity.status!=='cancelled'&&<button className="ws-button light" type="button" disabled={cancelling} onClick={()=>retryReminder(item.id)}>重试提醒</button>}
          </div>)}</div> : <div className="ws-empty">没有提醒任务</div>}
        </section>

        <section className="ws-panel wide">
          <div className="ws-panel-title"><div><h2>QQ 群消息</h2><p>{deliveries.length ? `${sentCount} 条成功${failedCount ? ` · ${failedCount} 条失败` : ''}` : '还没有投递'}</p></div></div>
          <form className="ws-form" onSubmit={sendMessage}>
            <label>通知内容<textarea rows={3} placeholder="输入要发到活动群的通知" value={messageContent} disabled={sending || activity.status === 'cancelled'} onChange={(event) => setMessageContent(event.target.value)} /></label>
            <div className="publish-bar"><button className="ws-button" type="submit" disabled={sending || activity.status === 'cancelled'}>{sending ? '发送中…' : '发送到 QQ 群'}</button></div>
          </form>
          {deliveries.length ? <div className="delivery-list">{deliveries.map((item, index) => (
            <div key={index} className="delivery-row"><span className="delivery-kind">{DELIVERY_KIND_LABELS[item.kind] || item.kind}</span>
              <span className="delivery-content">{item.content}</span>
              <span className={`delivery-status ${item.status === 'failed' ? 'fail' : ''}`}>{({ sent: '已送达', failed: '发送失败', unknown: '待核对', sending:'发送中或待核对' } as Record<string, string>)[item.status] || item.status}</span>
              {(item.status==='unknown'||item.status==='sending')&&<button className="ws-button light" type="button" disabled={!!checkingDelivery} onClick={()=>checkDelivery(item.id)}>核对投递结果</button>}
            </div>
          ))}</div> : <div className="ws-empty">还没有 QQ 投递记录</div>}
        </section>

        <ParticipationManagement key={id} activityId={id} status={activity.status} onChanged={()=>load(true)}/>
        <RecapPanel key={id} id={id} status={activity.status} version={activity.version} onChanged={()=>load(true)}/>
      </div>
    </>
  );
}
