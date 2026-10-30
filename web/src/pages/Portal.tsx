import Notifications from '../components/Notifications';
import { Link } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { useAuth } from '../lib/auth';
import DataState from '../components/DataState';
import ParticipantTasks from '../components/ParticipantTasks';
import { formatDate } from '../lib/utils';
import type { DashboardData, DraftListResponse } from '../types';

interface Metric { label: string; value: string | number; hint?: string }

export default function Portal() {
  const { account } = useAuth();
  const isManager = account?.role === 'manager';
  const { data: dashboard, loading, error, reload } = useApi<DashboardData>(
    () => api<DashboardData>('/portal/dashboard'), []);
  const { data: draftsData, loading: draftsLoading, error: draftsError, reload: reloadDrafts } = useApi<DraftListResponse>(
    () => isManager ? api<DraftListResponse>('/agent/drafts') : Promise.resolve({ drafts: [] }), [isManager]);

  const todos = dashboard?.todos ?? [];
  const classes = dashboard?.classes ?? [];
  const activityTasks = dashboard?.activity_tasks ?? [];
  const drafts = draftsData?.drafts ?? [];
  const total = isManager ? todos.reduce((sum, todo) => sum + (todo.total_count ?? 0), 0) : todos.length + activityTasks.length;
  const done = isManager
    ? todos.reduce((sum, todo) => sum + (todo.done_count ?? 0), 0)
    : todos.filter((todo) => todo.my_status === 'done').length + activityTasks.filter((task) => task.status === 'done').length;
  const overdue = todos.filter((todo) => new Date(todo.deadline).getTime() < Date.now()
    && (isManager ? (todo.done_count ?? 0) < (todo.total_count ?? 0) : todo.my_status !== 'done')).length;
  const progress = done + '/' + total;
  const metrics: Metric[] = isManager
    ? [
      { label: '成员总数', value: classes.reduce((sum, classroom) => sum + (classroom.member_count ?? 0), 0) },
      { label: '待办项目数', value: todos.length }, { label: '完成进度', value: progress },
      { label: '已逾期数', value: overdue, hint: overdue ? '需要处理' : '状态正常' },
    ]
    : [
      { label: '我的任务数', value: total }, { label: '活动任务数', value: activityTasks.length },
      { label: '完成进度', value: progress }, { label: '逾期作业数', value: overdue },
    ];

  return (
    <>
      <Notifications />
      <header className="ws-header"><div>
        <p className="ws-kicker">DASHBOARD</p><h1>总览</h1>
        <p>班级作业、待办和活动进度一目了然。</p>
      </div></header>
      <DataState loading={loading} error={error} onRetry={reload} loadingText="总览加载中…" />
      {!loading && !error && dashboard && <>
        <section className="ws-metrics">
          {metrics.map((metric) => <article key={metric.label} className="ws-metric">
            <span>{metric.label}</span><strong>{metric.value}</strong>{metric.hint && <em>{metric.hint}</em>}
          </article>)}
        </section>
        <div className="ws-grid">
          <section className="ws-panel">
            <div className="ws-panel-title"><div><h2>最近作业与待办</h2><p>班级作业和提交情况</p></div>
              <Link className="ws-button light" to="/assignments">查看全部</Link></div>
            <div className="ws-list">
              {todos.length === 0 && <div className="ws-empty">暂无作业与待办</div>}
              {todos.slice(0, 6).map((todo) => {
                const finished = isManager
                  ? (todo.total_count ?? 0) > 0 && todo.done_count === todo.total_count : todo.my_status === 'done';
                return <Link key={todo.id} to="/assignments" className="ws-list-item">
                  <span className={'ws-tag ' + (finished ? 'green' : 'amber')}>
                    {isManager ? (todo.done_count ?? 0) + '/' + (todo.total_count ?? 0) : finished ? '完成' : '待办'}
                  </span><span><strong>{todo.title}</strong><small>{todo.class_name} · {formatDate(todo.deadline)}</small></span><b>›</b>
                </Link>;
              })}
            </div>
          </section>
          {isManager ? <section className="ws-panel">
            <div className="ws-panel-title"><div><h2>Agent 草案</h2><p>待确认和最近发布的方案</p></div>
              <Link className="ws-button light" to="/agent">打开助手</Link></div>
            <DataState loading={draftsLoading} error={draftsError} onRetry={reloadDrafts}
              empty={!draftsLoading && !draftsError && drafts.length === 0} emptyText="还没有 Agent 草案" />
            {!draftsLoading && !draftsError && <div className="ws-list">
              {drafts.slice(0, 6).map((draft) => <Link key={draft.id} to="/agent" className="ws-list-item">
                <span className={'ws-tag' + (draft.status === 'published' ? ' green' : '')}>
                  {draft.status === 'published' ? '已发布' : '草案'}</span>
                <span><strong>{draft.title}</strong><small>{draft.summary}</small></span><b>›</b>
              </Link>)}
            </div>}
          </section> : <section className="ws-panel">
            <div className="ws-panel-title"><div><h2>我的活动任务</h2><p>只展示分配给你的任务，可直接更新完成状态</p></div>
              <Link className="ws-button light" to="/assignments">查看全部任务</Link></div>
            <ParticipantTasks tasks={activityTasks} onChanged={reload} limit={6} />
          </section>}
        </div>
      </>}
    </>
  );
}
