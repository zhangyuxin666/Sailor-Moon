import { useEffect, useState } from 'react';
import { api, useApi } from '../lib/api';
import DataState from './DataState';
import { formatDate } from '../lib/utils';

interface Notice { id: number; content: string; created_at: string }

export default function Notifications() {
  const { data, loading, error, reload } = useApi<{ notifications: Notice[] }>(
    () => api<{ notifications: Notice[] }>('/notifications'), []);
  const [live, setLive] = useState<'connecting' | 'live' | 'offline'>('connecting');

  // 站内消息 SSE：收到推送立刻刷新；断开后降级为 30 秒轮询，重连成功自动停掉。
  useEffect(() => {
    let fallback: number | undefined;
    const source = new EventSource('/notifications/stream', { withCredentials: true });

    source.addEventListener('ready', () => {
      setLive('live');
      reload();                                   // 连上先补拉一次，覆盖断线期间错过的消息
      if (fallback !== undefined) { window.clearInterval(fallback); fallback = undefined; }
    });

    source.addEventListener('message', () => {
      setLive('live');
      reload();                                   // 收到 id 后重新取最近消息，正文仍从接口拿
    });

    source.onerror = () => {
      setLive('offline');
      if (fallback === undefined) fallback = window.setInterval(reload, 30_000);
    };

    return () => {
      source.close();
      if (fallback !== undefined) window.clearInterval(fallback);
    };
  }, [reload]);

  const label = live === 'live' ? '实时接收中' : live === 'connecting' ? '连接中…' : '已降级为轮询';

  return <section className="ws-panel wide" aria-label="我的变更通知">
    <div className="ws-panel-title"><div><h2>我的变更通知</h2><p>活动改期、取消与任务改派的站内记录。</p></div>
      <div className="notification-actions">
        <span className={`analytics-live ${live}`}>{label}</span>
        <button className="ws-button light" type="button" onClick={reload}>刷新通知</button>
      </div></div>
    <DataState loading={loading} error={error} onRetry={reload} empty={!loading && !error && !data?.notifications.length} emptyText="暂无变更通知" />
    <div className="ws-list">{data?.notifications.map(n => <div className="ws-list-item" key={n.id}>
      <span><strong>{n.content}</strong><small>{formatDate(n.created_at)}</small></span></div>)}</div>
  </section>;
}
