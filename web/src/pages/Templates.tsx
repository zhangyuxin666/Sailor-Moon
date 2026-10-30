import {useEffect,useMemo,useRef,useState,type FormEvent} from 'react';
import {useNavigate} from 'react-router-dom';
import {api,useApi} from '../lib/api';
import DataState from '../components/DataState';
import type {DashboardData,DraftDetail} from '../types';

type Blueprint=DraftDetail&{execution_plan?:{tasks?:Array<{title:string;depends_on?:string[];priority?:string}>;materials?:string[]}};
type BusinessTemplate={id:string;name:string;description:string;version:number;builtin:boolean;editable:boolean;blueprint:Blueprint};
type SourceItem={id:string;title:string;status:string};
const EMPTY:Blueprint={intent_type:'activity',complexity:'complex',title:'新活动模板',description:'请填写每次活动通用的要求。',workflow:{create_plan:true,create_form:true,assign_tasks:true,create_calendar:true,schedule_reminder:true,publish_message:false},form_fields:[],execution_plan:{tasks:[],materials:[]}};
function readBlueprint(text:string):Blueprint|null{
 try{
  const value=JSON.parse(text) as Blueprint;
  if(!value||typeof value!=='object'||Array.isArray(value))return null;
  const tasks=value.execution_plan?.tasks,fields=value.form_fields;
  if(tasks!==undefined&&(!Array.isArray(tasks)||tasks.some(item=>!item||typeof item!=='object')))return null;
  if(fields!==undefined&&(!Array.isArray(fields)||fields.some(item=>!item||typeof item!=='object'||item.options!==undefined&&!Array.isArray(item.options))))return null;
  return value;
 }catch{return null;}
}
export default function Templates(){
 const navigate=useNavigate();const [revision,setRevision]=useState(0);
 const {data:list,loading,error,reload}=useApi(()=>api<{templates:BusinessTemplate[]}>('/business-templates'),[revision]);
 const {data:dashboard,loading:classesLoading,error:classesError,reload:reloadClasses}=useApi(()=>api<DashboardData>('/portal/dashboard'),[]);
 const {data:origins,loading:originsLoading,error:originsError,reload:reloadOrigins}=useApi(()=>api<{drafts:SourceItem[];activities:SourceItem[]}>('/business-templates/sources'),[revision]);
 const [selected,setSelected]=useState<BusinessTemplate|null>(null),[classId,setClassId]=useState('');
 const [name,setName]=useState(''),[description,setDescription]=useState(''),[blueprint,setBlueprint]=useState(JSON.stringify(EMPTY,null,2));
 const [sourceType,setSourceType]=useState<'blueprint'|'draft'|'activity'>('blueprint'),[sourceId,setSourceId]=useState('');
 const [busy,setBusy]=useState(''),[actionError,setActionError]=useState(''),[message,setMessage]=useState('');
 const inFlight=useRef(false),dirty=useRef(false);
 const basic=useMemo(()=>readBlueprint(blueprint),[blueprint]);
 function editBasic(change:(next:Blueprint)=>void){const next=readBlueprint(blueprint);if(!next)return;change(next);dirty.current=true;setBlueprint(JSON.stringify(next,null,2));}
 useEffect(()=>{if(!classId&&dashboard?.classes?.length)setClassId(dashboard.classes[0].id);},[dashboard,classId]);
 function choose(item:BusinessTemplate|null){if(dirty.current&&!window.confirm('放弃尚未保存的模板修改？'))return;dirty.current=false;setSelected(item);setName(item?.name??'');setDescription(item?.description??'');setBlueprint(JSON.stringify(item?.blueprint??EMPTY,null,2));setSourceType('blueprint');setSourceId('');setActionError('');setMessage('');}
 async function apply(){
  if(!selected||!classId||inFlight.current)return;
  if(dirty.current){setActionError('请先保存模板修改，再创建本次草案。');return;}
  inFlight.current=true;setBusy('apply');setActionError('');
  try{
   const storageKey='business-template-apply:v1:'+selected.id+':'+classId;
   let requestId:string;
   try{
    const pending=sessionStorage.getItem(storageKey);
    requestId=pending&&/^[A-Za-z0-9_-]{8,80}$/.test(pending)?pending:crypto.randomUUID();
    // Persist before sending: refreshes and switching classes must reuse an uncertain request.
    sessionStorage.setItem(storageKey,requestId);
   }catch{throw new Error('浏览器无法保存重试标识，请开启会话存储后再创建草案。');}
   const result=await api<{draft_id:string}>('/business-templates/'+selected.id+'/apply',{method:'POST',body:JSON.stringify({class_id:classId,request_id:requestId})});
   sessionStorage.removeItem(storageKey);
   navigate('/agent?draft='+encodeURIComponent(result.draft_id));
  }catch(e){setActionError((e instanceof Error?e.message:'创建失败')+'；未确认的请求会保留，重试不会另建草案。');}
  finally{inFlight.current=false;setBusy('');}
 }
 async function save(event:FormEvent){event.preventDefault();if(inFlight.current)return;inFlight.current=true;setBusy('save');setActionError('');setMessage('');try{let body:Record<string,unknown>={name,description};if(sourceType==='blueprint')body.blueprint=JSON.parse(blueprint);else{if(!sourceId)throw new Error('请选择要复制结构的来源');body[sourceType==='draft'?'source_draft_id':'source_activity_id']=sourceId;}const editing=selected?.editable&&sourceType==='blueprint';if(editing)body.expected_version=selected.version;const result=await api<BusinessTemplate>(editing?'/business-templates/'+selected.id:'/business-templates',{method:editing?'PUT':'POST',body:JSON.stringify(body)});dirty.current=false;setSelected(result);setName(result.name);setDescription(result.description);setBlueprint(JSON.stringify(result.blueprint,null,2));setSourceType('blueprint');setSourceId('');setRevision(v=>v+1);setMessage('模板已保存。请检查通用内容，再选择当前班级创建待确认草案。');}catch(e){setActionError(e instanceof Error?e.message:'保存失败，修改已保留');}finally{inFlight.current=false;setBusy('');}}
 async function remove(){if(!selected?.editable||inFlight.current||!window.confirm('确认删除此模板？已生成草案不会删除。'))return;inFlight.current=true;setBusy('delete');setActionError('');try{await api('/business-templates/'+selected.id+'?expectedVersion='+selected.version,{method:'DELETE'});dirty.current=false;choose(null);setRevision(v=>v+1);setMessage('模板已删除，原有草案仍保留。');}catch(e){setActionError(e instanceof Error?e.message:'删除失败，请刷新后重试');}finally{inFlight.current=false;setBusy('');}}
 const preview=selected?.blueprint;
 return <><header className="ws-header"><div><p className="ws-kicker">TEMPLATES</p><h1>可复用业务模板</h1><p>先选模板和本次班级，再确认新草案。日期、负责人和群通知都要重新确认。</p></div></header>{actionError&&<p role="alert" className="auth-error">{actionError}</p>}{message&&<p role="status">{message}</p>}<div className="ws-grid">
 <section className="ws-panel" aria-label="模板列表"><div className="ws-panel-title"><h2>模板列表</h2><button className="ws-button light" disabled={!!busy} onClick={()=>choose(null)}>新建模板</button></div><DataState loading={loading} error={error} onRetry={reload}/><div className="ws-list">{list?.templates.map(item=><button className="ws-list-item" key={item.id} disabled={!!busy} onClick={()=>choose(item)}><span><strong>{item.name}</strong><small>{item.builtin?'内置只读':'本组织'} · v{item.version} · {item.description}</small></span></button>)}</div></section>
 <section className="ws-panel" aria-label="模板应用"><h2>创建本次待确认草案</h2>{selected?<><h3>{selected.name}</h3><p>{preview?.description}</p><p>类型：{preview?.intent_type==='assignment'?'作业收集':preview?.intent_type==='activity'?'活动':preview?.intent_type==='survey'?'信息收集':'通知'}；任务 {preview?.execution_plan?.tasks?.length??0} 项。</p><ul>{preview?.execution_plan?.tasks?.map((task,index)=><li key={index}>{task.title}</li>)}</ul><p>每次创建独立草案。请在AI助手中选择本次负责人、确认日期和群通知，再点击发布。</p></>:<p>从左侧选择班会、秋游、作业收集或组织自有模板。</p>}<DataState loading={classesLoading} error={classesError} onRetry={reloadClasses}/><label>本次班级<select className="ws-select" value={classId} disabled={!!busy} onChange={e=>setClassId(e.target.value)}><option value="">请选择班级</option>{dashboard?.classes?.map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></label><button className="ws-button" type="button" disabled={!!busy||!selected||!classId} onClick={apply}>创建待确认草案</button></section>
 <section className="ws-panel wide" aria-label="模板保存与编辑"><h2>{selected?.editable?'编辑自己的模板':'另存为自己的模板'}</h2><p>内置模板和他人创建的组织模板只读，可另存。若从旧草案或活动保存，仅复制任务依赖与问卷结构，旧自由文本改为占位、物料清空；请填写通用文案后再应用。</p><form className="ws-form" onSubmit={save}><fieldset disabled={!!busy} style={{border:0,minWidth:0,padding:0,display:'grid',gap:12}}><label>模板名称<input required maxLength={120} value={name} onChange={e=>{dirty.current=true;setName(e.target.value);}}/></label><label>模板简介<textarea maxLength={1000} value={description} onChange={e=>{dirty.current=true;setDescription(e.target.value);}}/></label><label>模板内容来源<select value={sourceType} onChange={e=>{dirty.current=true;setSourceType(e.target.value as typeof sourceType);setSourceId('');}}><option value="blueprint">当前模板内容或手工通用内容</option><option value="draft">我创建的草案结构</option><option value="activity">我创建的活动结构</option></select></label>{sourceType!=='blueprint'&&<><DataState loading={originsLoading} error={originsError} onRetry={reloadOrigins}/><label>选择来源<select required value={sourceId} onChange={e=>{dirty.current=true;setSourceId(e.target.value);}}><option value="">请选择来源</option>{sourceType==='draft'?origins?.drafts.map(item=><option key={item.id} value={item.id}>{item.title||'草案'} · {item.status}</option>):origins?.activities.map(item=><option key={item.id} value={item.id}>{item.title} · {item.status}</option>)}</select></label></>}{sourceType==='blueprint'&&<>
 <section aria-label="基础内容编辑"><h3>通用内容</h3>{basic?<>
 <label>事务说明<textarea maxLength={4000} value={basic.description??''} onChange={e=>editBasic(next=>{next.description=e.target.value;})}/></label>
 {(basic.execution_plan?.tasks??[]).map((task,index)=><label key={index}>任务 {index+1} 标题<input required maxLength={255} value={task.title??''} onChange={e=>editBasic(next=>{const item=next.execution_plan?.tasks?.[index];if(item)item.title=e.target.value;})}/></label>)}
 {(basic.form_fields??[]).map((field,index)=>field.name==='name'||field.name==='contact'?null:<div className="ws-panel" key={index}>
 <label>问题 {index+1} 标签<input required maxLength={255} value={field.label??''} onChange={e=>editBasic(next=>{const item=next.form_fields?.[index];if(item)item.label=e.target.value;})}/></label>
 {field.type==='select'&&(field.options??[]).map((option,optionIndex)=><label key={optionIndex}>选项 {optionIndex+1}<input required maxLength={1000} value={option} onChange={e=>editBasic(next=>{const item=next.form_fields?.[index];if(item?.options)item.options[optionIndex]=e.target.value;})}/></label>)}
 </div>)}
 </>:<p role="alert">蓝图格式无效，请在高级编辑中修正 JSON 后继续编辑通用内容。</p>}</section>
 <details><summary>高级：编辑通用蓝图</summary><p>仅保留允许的问卷、任务依赖和工作流字段；日期、旧身份及原始回答不会保存，发布群消息默认关闭。</p><label>通用蓝图<textarea rows={14} style={{fontFamily:'monospace'}} value={blueprint} onChange={e=>{dirty.current=true;setBlueprint(e.target.value);}}/></label></details></>}<div className="ws-actions"><button className="ws-button" type="submit">{selected?.editable&&sourceType==='blueprint'?'保存模板修改':'保存为新模板'}</button>{selected?.editable&&<button className="ws-button danger" type="button" onClick={remove}>删除模板</button>}</div></fieldset></form></section></div></>;
}
