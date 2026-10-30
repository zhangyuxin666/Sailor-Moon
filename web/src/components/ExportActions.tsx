import {useRef,useState} from 'react';
import {sessionRequired} from '../lib/session-policy';
import {visibleOperationalError} from '../lib/activity-view';

/** Downloads stay in the authenticated browser; export bodies are never sent to another service. */
export default function ExportActions({activityId,todoId}:{activityId?:string;todoId?:string}) {
  const [busy,setBusy]=useState(''),[error,setError]=useState(''),[message,setMessage]=useState('');
  const inFlight=useRef(false);
  async function download(path:string,filename:string) {
    if(inFlight.current)return;inFlight.current=true;setBusy(filename);setError('');setMessage('');
    try {
      const response=await fetch(path,{credentials:'include',headers:{Accept:'text/csv'}});
      if(!response.ok) {
        let detail='导出失败，请稍后重试',code:string|undefined;
        try {const result=await response.json();detail=String(result.detail||detail);code=result.code;}catch{/* Keep the safe fallback. */}
        if(code==='password_change_required')window.dispatchEvent(new Event('activity:password-change-required'));
        if(sessionRequired(path,response.status,code,detail))window.dispatchEvent(new Event('activity:authentication-required'));
        throw new Error(detail);
      }
      const blob=await response.blob(),url=URL.createObjectURL(blob),link=document.createElement('a');
      link.href=url;link.download=filename;document.body.appendChild(link);link.click();link.remove();
      window.setTimeout(()=>URL.revokeObjectURL(url),1000);setMessage('导出已生成');
    }catch(cause){setError(visibleOperationalError(cause instanceof Error?cause.message:'','导出失败，可重试'));}
    finally{inFlight.current=false;setBusy('');}
  }
  return <div aria-label="数据导出"><div className="ws-actions">
    {activityId&&<><button className="ws-button light" type="button" disabled={!!busy} onClick={()=>download(`/activities/${encodeURIComponent(activityId)}/exports/registrations.csv`,'activity-registrations.csv')}>导出报名和问卷</button><button className="ws-button light" type="button" disabled={!!busy} onClick={()=>download(`/activities/${encodeURIComponent(activityId)}/exports/tasks.csv`,'activity-tasks.csv')}>导出任务分工</button></>}
    {todoId&&<button className="ws-button light" type="button" disabled={!!busy} onClick={()=>download(`/todos/${encodeURIComponent(todoId)}/exports/missing.csv`,'todo-missing.csv')}>导出未交名单</button>}
  </div>{busy&&<p role="status">正在导出…</p>}{message&&<p role="status">{message}</p>}{error&&<p role="alert">{error}</p>}</div>;
}
