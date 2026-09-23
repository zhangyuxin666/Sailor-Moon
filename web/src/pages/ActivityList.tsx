/**
 * 活动管理列表页 /activity（仅管理者）
 * 对应旧 frontend/activities-page.js + activities.html。
 * - GET /portal/activities 返回 { activities: ActivityItem[] }
 * - 状态映射：queued/running=排队中/执行中(amber)、ready=已就绪(green)、
 *   failed=失败(complex)、finished=已复盘(green)
 * - 列表项用 NavLink 跳 /activity/{id}
 */
import { useEffect } from 'react';
import { Link, NavLink } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { useToast } from '../components/Toast';
import { formatDate } from '../lib/utils';
import type { ActivityListResponse } from '../types';

/** 活动状态 → 标签文案与颜色类 */
const ACTIVITY_STATUS: Record<string, { label: string; cls: string }> = {
  queued: { label: '排队中', cls: 'amber' },
  running: { label: '执行中', cls: 'amber' },
  ready: { label: '已就绪', cls: 'green' },
  failed: { label: '失败', cls: 'complex' },
  finished: { label: '已复盘', cls: 'green' },
};

export default function ActivityList() {
  const { showToast } = useToast();
  const { data, loading, error } = useApi<ActivityListResponse>(() =>
    api<ActivityListResponse>('/portal/activities'),
  );

  useEffect(() => {
    if (error) showToast(error, 'error');
  }, [error, showToast]);

  const activities = data?.activities ?? [];

  return (
    <>
      <header className="ws-header">
        <div>
          <p className="ws-kicker">ACTIVITIES</p>
          <h1>活动管理</h1>
          <p>查看所有由 Agent 发起的活动及其执行状态。</p>
        </div>
        <div className="ws-actions">
          <Link className="ws-button" to="/agent">
            ✦ 发起新活动
          </Link>
        </div>
      </header>

      <section className="ws-panel">
        <div className="ws-panel-title">
          <div>
            <h2>全部活动</h2>
            <p>AI 助手是唯一的发起入口，创建后在这里查看和推进</p>
          </div>
        </div>

        {loading ? (
          <div className="ws-empty">正在载入活动…</div>
        ) : activities.length === 0 ? (
          <div className="ws-empty">还没有活动，去 AI 助手用一句话发起</div>
        ) : (
          <div className="ws-list">
            {activities.map((item) => {
              const status = ACTIVITY_STATUS[item.status] ?? {
                label: item.status || '未知',
                cls: '',
              };
              return (
                <NavLink key={item.id} className="ws-list-item" to={`/activity/${item.id}`}>
                  <span className={`ws-tag ${status.cls}`}>{status.label}</span>
                  <span>
                    <strong>{item.title}</strong>
                    <small>
                      {item.raw_input ? `${item.raw_input} · ` : ''}
                      {formatDate(item.created_at)}
                    </small>
                  </span>
                  <b>›</b>
                </NavLink>
              );
            })}
          </div>
        )}
      </section>
    </>
  );
}
