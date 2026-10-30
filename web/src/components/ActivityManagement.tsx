import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, useApi } from '../lib/api';
import { isoToLocal, localToISO, formatDate } from '../lib/utils';
import DataState from './DataState';
import type { ActivityDetail, ActivityTask, RegistrationSettings, FormStats } from '../types';

type Candidate = { account_id: string; display_name: string; username?:string; student_no?:string; pending_count: number };
type Change = { id: string; kind: string; content: string; notification_status: string; error?: string | null; created_at: string; result?: { previous?: ActivityTask | null } };
const priorities: Record<string,string> = { low:'低', normal:'普通', high:'高', urgent:'紧急' };
const noticeStates: Record<string,string> = { pending:'通知待处理', local:'站内通知已记录', sent:'群通知已送达', failed:'群通知失败，可重试', unknown:'投递结果未知，请到群消息记录核对', cancelled:'后续通知已停止' };

export default function ActivityManagement({ id, detail, stats, onChanged }: { id:string; detail:ActivityDetail; stats:FormStats|null; onChanged:()=>Promise<unknown> }) {
  const [revision,setRevision]=useState(0);
  const {data:people,loading:peopleLoading,error:peopleError,reload:reloadPeople}=useApi(()=>api<{members:Candidate[]}>(`/activities/${id}/task-management`),[id,revision]);
  const {data:history,loading:historyLoading,error:historyError,reload:reloadHistory}=useApi(()=>api<{changes:Change[]}>(`/activities/${id}/changes`),[id,revision]);
  const formId=detail.forms?.[0]?.id;
  const {data:rules,loading:rulesLoading,error:rulesError,reload:reloadRules}=useApi(()=>formId?api<RegistrationSettings>(`/public/forms/${formId}`):Promise.resolve(null),[id,formId,detail.forms?.[0]?.version,revision]);
  const [busy,setBusy]=useState(''),[error,setError]=useState(''),[success,setSuccess]=useState('');
  const inFlight=useRef(false),request=useRef({payload:'',id:''});
  const [capacity,setCapacity]=useState(''),[registrationDeadline,setRegistrationDeadline]=useState(''),[registrationStatus,setRegistrationStatus]=useState('open');
  const rulesDirty=useRef(false);
  const [rulesVersion,setRulesVersion]=useState<number>();
  const [reason,setReason]=useState(''),[eventTime,setEventTime]=useState(''),[deadlinePolicy,setDeadlinePolicy]=useState('keep'),[newDeadline,setNewDeadline]=useState('');
  const [editing,setEditing]=useState<ActivityTask|null>(null),[title,setTitle]=useState(''),[description,setDescription]=useState(''),[taskDeadline,setTaskDeadline]=useState(''),[priority,setPriority]=useState('normal'),[account,setAccount]=useState(''),[dependencies,setDependencies]=useState<string[]>([]);
  const cancelled=detail.activity.status==='cancelled',closed=cancelled||detail.activity.status==='finished';
  useEffect(()=>{if(rules&&!rulesDirty.current){setCapacity(rules.capacity==null?'':String(rules.capacity));setRegistrationDeadline(isoToLocal(rules.registration_deadline));setRegistrationStatus(rules.registration_status);setRulesVersion(rules.version);}},[rules]);
  const members=people?.members??[];
  const currentCount=stats?.count??rules?.registration_count??0;
  const remaining=rules?.capacity==null?null:Math.max(0,rules.capacity-currentCount);
  useEffect(()=>{if(!history?.changes.some(c=>c.notification_status==='pending'))return;const timer=window.setTimeout(reloadHistory,2000);return()=>window.clearTimeout(timer);},[history,reloadHistory]);
  async function mutate(key:string,path:string,body:Record<string,unknown>,method='POST') {
    if(inFlight.current)return false;
    inFlight.current=true;setBusy(key);setError('');setSuccess('');
    try {
      const fingerprint=JSON.stringify({path,body});
      if(request.current.payload!==fingerprint)request.current={payload:fingerprint,id:crypto.randomUUID()};
      await api(path,{method,body:JSON.stringify({...body,request_id:request.current.id})});
      request.current={payload:'',id:''};rulesDirty.current=false;
      await onChanged();setRevision(v=>v+1);setSuccess('变更已保存，通知状态见下方记录。');return true;
    }catch(cause){setError(cause instanceof Error?cause.message:'操作失败，输入已保留');return false;}
    finally{inFlight.current=false;setBusy('');}
  }
  async function saveRules(event:FormEvent){event.preventDefault();await mutate('rules',`/activities/${id}/registration-settings`,{capacity:capacity===''?null:Number(capacity),registration_deadline:registrationDeadline?localToISO(registrationDeadline):'',registration_status:registrationStatus,expected_version:rulesVersion},'PUT');}
  function edit(task:ActivityTask|null){setEditing(task);setTitle(task?.title??'');setDescription(task?.description??'');setTaskDeadline(isoToLocal(task?.deadline));setPriority(task?.priority??'normal');setAccount(task?.account_id??'');setDependencies(task?.depends_on??[]);setError('');}
  async function suggest(){
    if(inFlight.current)return;inFlight.current=true;setBusy('suggestion');setError('');
    try{const fresh=await api<{members:Candidate[]}>(`/activities/${id}/task-management`);if(fresh.members.length)setAccount(fresh.members[0].account_id);reloadPeople();}
    catch(cause){setError(cause instanceof Error?cause.message:'负载读取失败');}finally{inFlight.current=false;setBusy('');}
  }
  async function saveTask(event:FormEvent){event.preventDefault();const done=await mutate('task',`/activities/${id}/tasks${editing?'/'+editing.id:''}`,{title,description,deadline:taskDeadline?localToISO(taskDeadline):'',priority,account_id:account,depends_on:dependencies,...(editing?{expected_version:editing.version}:{})},editing?'PUT':'POST');if(done)edit(null);}
  async function cancelActivity(){if(!reason.trim()){setError('请填写取消原因');return;}if(!window.confirm('取消后将关闭报名并停止后续步骤和未发送提醒。已完成或在途的外部操作不会被撤回。确认取消？'))return;await mutate('cancel',`/activities/${id}/cancel`,{reason});}
  async function reschedule(event:FormEvent){event.preventDefault();await mutate('reschedule',`/activities/${id}/reschedule`,{event_time:localToISO(eventTime),reason,expected_version:detail.activity.version,deadline_policy:deadlinePolicy,registration_deadline:deadlinePolicy==='set'?localToISO(newDeadline):''});}
  async function retry(change:Change){if(inFlight.current)return;inFlight.current=true;setBusy(change.id);setError('');try{await api(`/activities/${id}/changes/${change.id}/retry`,{method:'POST'});reloadHistory();}catch(e){setError(e instanceof Error?e.message:'重试失败');}finally{inFlight.current=false;setBusy('');}}
  return <>
    <section className="ws-panel wide" aria-label="活动管理">
      <div className="ws-panel-title"><div><h2>活动管理</h2><p>取消、改期和报名规则均记录变更；已完成的操作保留。</p></div></div>
      {error&&<p className="auth-error" role="alert">{error}</p>}{success&&<p role="status">{success}</p>}
      {cancelled&&<p className="reason-box">活动已取消：{detail.activity.cancel_reason||'见变更记录'}。已完成与已发送记录仍可查看。</p>}
      <div className="ws-grid">
        <section><h3>报名规则</h3>
          <DataState loading={rulesLoading} error={rulesError} onRetry={reloadRules} empty={!rulesLoading&&!rulesError&&!formId} emptyText="尚无报名表" />
          {rules&&<form className="ws-form" onSubmit={saveRules}><fieldset disabled={!!busy||closed} style={{border:0,padding:0,display:'grid',gap:12}} onChange={()=>{rulesDirty.current=true;}}>
            <label>报名容量（留空为不限）<input type="number" min="1" max="100000" value={capacity} onChange={e=>setCapacity(e.target.value)} /></label>
            <label>报名截止时间<input type="datetime-local" step="any" value={registrationDeadline} onChange={e=>setRegistrationDeadline(e.target.value)} /></label>
            <label>报名开关<select value={registrationStatus} onChange={e=>setRegistrationStatus(e.target.value)}><option value="open">开放</option><option value="closed">关闭</option></select></label>
            <p>有效报名 {currentCount} 人；{remaining==null?'不限名额':`剩余 ${remaining} 个名额`}。同姓名和联系方式不能重复报名，取消后释放名额。</p>
            <button className="ws-button" type="submit">保存报名规则</button>
          </fieldset></form>}
        </section>
        <section><h3>活动取消与改期</h3><form className="ws-form" onSubmit={reschedule}><fieldset disabled={!!busy||closed} style={{border:0,padding:0,display:'grid',gap:12}}>
          <label>变更原因<textarea required maxLength={1000} value={reason} onChange={e=>setReason(e.target.value)} /></label>
          <label>新的活动时间<input type="datetime-local" step="any" value={eventTime} onChange={e=>setEventTime(e.target.value)} /></label>
          <label>改期时的报名截止<select value={deadlinePolicy} onChange={e=>setDeadlinePolicy(e.target.value)}><option value="keep">保持原截止时间</option><option value="set">设置新截止时间</option><option value="clear">清除截止时间</option></select></label>
          {deadlinePolicy==='set'&&<label>新的报名截止时间<input type="datetime-local" step="any" required value={newDeadline} onChange={e=>setNewDeadline(e.target.value)} /></label>}
          <button className="ws-button" type="submit" disabled={detail.activity.status!=='ready'||!eventTime}>确认改期</button>
          <button className="ws-button danger" type="button" onClick={cancelActivity}>取消活动</button>
        </fieldset></form><p>改期仅用于已就绪活动；旧提醒失效，日历 UID 保持不变并增加版本。群通知失败可从变更记录重试。</p></section>
      </div>
    </section>
    <section className="ws-panel wide" aria-label="任务管理">
      <div className="ws-panel-title"><div><h2>任务管理与待分配池</h2><p>无效负责人保留为待分配，建议按当前待完成数量排序，选择后由你确认。</p></div><div className="ws-actions"><button className="ws-button light" type="button" onClick={reloadPeople} disabled={!!busy}>刷新候选负载</button><button className="ws-button light" type="button" onClick={()=>edit(null)} disabled={!!busy||closed}>新建任务</button></div></div>
      <DataState loading={peopleLoading} error={peopleError} onRetry={reloadPeople}/>
      <div className="ws-list">{(detail.tasks??[]).map(task=><div className="ws-list-item" key={task.id}><span className={'ws-tag '+(!task.account_id?'amber':'')}>{task.account_id?task.assignee:'待分配'}</span><span style={{flex:1}}><strong>{task.title}</strong><small>{priorities[task.priority??'normal']}优先级 · {task.deadline?formatDate(task.deadline):'无截止时间'}</small>{task.description&&<small>{task.description}</small>}{task.unassigned_reason&&<small>{task.unassigned_reason}</small>}{task.account_id&&<small>负责人账号：{members.find(m=>m.account_id===task.account_id)?.username??task.account_id}</small>}<small>前置任务：{task.depends_on?.map(dep=>detail.tasks?.find(t=>t.id===dep)?.title??dep).join('、')||'无'}</small></span><button className="ws-button light" type="button" disabled={!!busy||closed} onClick={()=>edit(task)}>编辑任务：{task.title}</button></div>)}</div>
      <h3>{editing?'编辑任务':'新建任务'}</h3><form className="ws-form" onSubmit={saveTask}><fieldset disabled={!!busy||closed} style={{border:0,padding:0,display:'grid',gap:12}}>
        <label>任务标题<input required maxLength={255} value={title} onChange={e=>setTitle(e.target.value)} /></label>
        <label>任务说明<textarea maxLength={4000} value={description} onChange={e=>setDescription(e.target.value)} /></label>
        <div className="ws-form-row"><label>任务截止时间<input type="datetime-local" step="any" value={taskDeadline} onChange={e=>setTaskDeadline(e.target.value)} /></label><label>任务优先级<select value={priority} onChange={e=>setPriority(e.target.value)}>{Object.entries(priorities).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label></div>
        <label>任务负责人<select value={account} onChange={e=>setAccount(e.target.value)}><option value="">待分配</option>{members.map(m=><option key={m.account_id} value={m.account_id}>{m.display_name}（{m.student_no||m.username||m.account_id}）· 待完成 {m.pending_count} 项</option>)}</select></label>
        <button className="ws-button light" type="button" disabled={!members.length} onClick={suggest}>采用最低负载建议</button>
        <div><p>前置依赖（完成所有前置任务后才能完成当前任务）</p>{(detail.tasks??[]).filter(t=>t.id!==editing?.id).map(t=><label className="workflow-item" key={t.id}><span>{t.title}</span><input type="checkbox" style={{width:18,minHeight:18}} checked={dependencies.includes(t.id)} onChange={e=>setDependencies(old=>e.target.checked?[...old,t.id]:old.filter(x=>x!==t.id))}/></label>)}</div>
        <button className="ws-button" type="submit">{editing?'保存任务变更':'创建任务'}</button>
      </fieldset></form>
    </section>
    <section className="ws-panel wide" aria-label="活动变更记录"><div className="ws-panel-title"><div><h2>活动变更记录</h2><p>站内通知按账号发送，群通知只使用当前授权群。</p></div><button className="ws-button light" type="button" onClick={reloadHistory}>刷新变更记录</button></div>
      <DataState loading={historyLoading} error={historyError} onRetry={reloadHistory} empty={!historyLoading&&!historyError&&!history?.changes.length} emptyText="暂无管理变更"/>
      <div className="ws-list">{history?.changes.map(change=><article className="ws-list-item" key={change.id}><span style={{flex:1}}><strong>{change.content}</strong><small>{formatDate(change.created_at)} · {noticeStates[change.notification_status]??change.notification_status}</small>{change.result?.previous&&<small>原负责人：{change.result.previous.assignee}；原状态：{change.result.previous.status==='done'?'已完成':'待完成'}</small>}{change.error&&<small role="alert">{change.error}</small>}</span>{change.notification_status==='failed'&&<button className="ws-button light" type="button" disabled={!!busy} onClick={()=>retry(change)}>重试变更通知</button>}</article>)}</div>
    </section>
  </>;
}
