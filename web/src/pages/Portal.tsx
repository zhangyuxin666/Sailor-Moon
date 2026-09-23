/**
 * 总览页 /portal
 * - GET /portal/dashboard 获取 { classes, todos }
 * - 管理者额外 GET /agent/drafts 获取 { drafts }
 * - 4 个指标卡 + 最近待办列表 + 最近草案列表
 */
import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useToast } from '../components/Toast';
import { formatDate } from '../lib/utils';
import type { DashboardData, DraftListResponse } from '../types';

interface Metric {
  label: string;
  value: string | number;
  hint?: string;
}

export default function Portal() {
  const { account } = useAuth();
  const isManager = account?.role === 'manager';

  const { data: dashboard, loading, error } = useApi<DashboardData>(
    () => api<DashboardData>('/portal/dashboard'),
    [],
  );

  // 仅管理者拉取草案列表；参与者直接返回空，避免 403
  const { data: draftsData } = useApi<DraftListResponse>(
    () =>
      isManager
        ? api<DraftListResponse>('/agent/drafts')
        : Promise.resolve<DraftListResponse>({ drafts: [] }),
    [isManager],
  );

  const { showToast } = useToast();
  useEffect(() => {
    if (error) showToast(error, 'error');
  }, [error, showToast]);

  if (loading) return <div className="page-loading">总览加载中…</div>;
  if (error || !dashboard) {
    return <div className="page-loading">加载失败，请稍后重试</div>;
  }

  const todos = dashboard.todos;
  const classes = dashboard.classes;
  const drafts = draftsData?.drafts ?? [];
  const now = Date.now();

  // 完成口径：管理者按人数汇总，参与者按自己的提交状态
  const total = isManager
    ? todos.reduce((n, x) => n + (x.total_count ?? 0), 0)
    : todos.length;
  const done = isManager
    ? todos.reduce((n, x) => n + (x.done_count ?? 0), 0)
    : todos.filter((x) => x.my_status === 'done').length;

  // 逾期：截止时间已过且未完成
  const overdue = todos.filter(
    (x) =>
      new Date(x.deadline).getTime() < now &&
      (isManager ? (x.done_count ?? 0) < (x.total_count ?? 0) : x.my_status !== 'done'),
  ).length;

  const members = isManager
    ? classes.reduce((n, x) => n + (x.member_count ?? 0), 0)
    : '—';

  const metrics: Metric[] = isManager
    ? [
        { label: '成员总数', value: members },
        { label: '待办项目数', value: todos.length },
        { label: '完成进度', value: `${done}/${total}` },
        { label: '已逾期数', value: overdue, hint: overdue > 0 ? '需要处理' : '状态正常' },
      ]
    : [
        { label: '我的任务数', value: todos.length },
        { label: '待办项目数', value: todos.length },
        { label: '完成进度', value: `${done}/${total}` },
        { label: '已逾期数', value: overdue, hint: overdue > 0 ? '需要处理' : '状态正常' },
      ];

  return (
    <>
      <header className="ws-header">
        <div>
          <p className="ws-kicker">DASHBOARD</p>
          <h1>总览</h1>
          <p>班级作业、待办和活动进度一目了然。</p>
        </div>
      </header>

      <section className="ws-metrics">
        {metrics.map((m, i) => (
          <article key={i} className="ws-metric">
            <span>{m.label}</span>
            <strong>{m.value}</strong>
            {m.hint ? <em>{m.hint}</em> : null}
          </article>
        ))}
      </section>

      <div className="ws-grid">
        {/* 最近待办 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>最近待办</h2>
              <p>班级作业和任务完成情况</p>
            </div>
            <Link className="ws-button light" to="/assignments">
              查看全部
            </Link>
          </div>
          <div className="ws-list">
            {todos.length === 0 ? (
              <div className="ws-empty">暂无待办</div>
            ) : (
              todos.slice(0, 6).map((x) => {
                const finished = isManager
                  ? (x.done_count ?? 0) === (x.total_count ?? 0)
                  : x.my_status === 'done';
                return (
                  <Link key={x.id} to="/assignments" className="ws-list-item">
                    <span className={`ws-tag${finished ? ' green' : ' amber'}`}>
                      {isManager
                        ? `${x.done_count ?? 0}/${x.total_count ?? 0}`
                        : x.my_status === 'done'
                          ? '完成'
                          : '待办'}
                    </span>
                    <span>
                      <strong>{x.title}</strong>
                      <small>
                        {x.class_name} · {formatDate(x.deadline)}
                      </small>
                    </span>
                    <b>›</b>
                  </Link>
                );
              })
            )}
          </div>
        </section>

        {/* Agent 草案（仅管理者） */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>Agent 草案</h2>
              <p>待确认和最近发布的方案</p>
            </div>
            {isManager ? (
              <Link className="ws-button light" to="/agent">
                打开助手
              </Link>
            ) : null}
          </div>
          <div className="ws-list">
            {isManager ? (
              drafts.length === 0 ? (
                <div className="ws-empty">还没有 Agent 草案</div>
              ) : (
                drafts.slice(0, 6).map((x) => (
                  <Link key={x.id} to="/agent" className="ws-list-item">
                    <span className={`ws-tag${x.status === 'published' ? ' green' : ''}`}>
                      {x.status === 'published' ? '已发布' : '草案'}
                    </span>
                    <span>
                      <strong>{x.title}</strong>
                      <small>{x.summary}</small>
                    </span>
                    <b>›</b>
                  </Link>
                ))
              )
            ) : (
              <div className="ws-empty">参与者可在左侧查看自己的任务</div>
            )}
          </div>
        </section>
      </div>
    </>
  );
}
