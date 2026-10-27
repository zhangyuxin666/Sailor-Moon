import { api,useApi } from '../lib/api';
import DataState from './DataState';
import { formatDate } from '../lib/utils';
export default function Notifications(){
 const {data,loading,error,reload}=useApi(()=>api<{notifications:Array<{id:number;content:string;created_at:string}>}>('/notifications'),[]);
 return <section className="ws-panel wide" aria-label="我的变更通知"><div className="ws-panel-title"><div><h2>我的变更通知</h2><p>活动改期、取消与任务改派的站内记录。</p></div><button className="ws-button light" type="button" onClick={reload}>刷新通知</button></div><DataState loading={loading} error={error} onRetry={reload} empty={!loading&&!error&&!data?.notifications.length} emptyText="暂无变更通知"/><div className="ws-list">{data?.notifications.map(n=><div className="ws-list-item" key={n.id}><span><strong>{n.content}</strong><small>{formatDate(n.created_at)}</small></span></div>)}</div></section>;
}
