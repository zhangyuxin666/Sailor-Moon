/**
 * 活动详情页 /activity/:id（仅管理者，最复杂）
 * 对应旧 frontend/activity-detail.js + activity-detail.html。
 * - GET /activities/{id} 返回 ActivityDetail，10 秒轮询刷新
 * - 面板：活动策划+物料(wide) / 任务分工 / 报名统计 / 自动执行记录 /
 *   定时提醒 / QQ 群消息(wide) / 复盘
 */
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '../lib/api';
import { useToast } from '../components/Toast';
import { formatDate } from '../lib/utils';
import type { ActivityDetail, ActivityTask, FormStats, RecapResult } from '../types';

/** 执行步骤 step 名 → 中文标签 */
const STEP_LABELS: Record<string, string> = {
  generate_plan: '生成活动策划',
  create_form: '创建报名问卷',
  assign_tasks: '分配任务并通知',
  create_calendar_event: '创建日历事件',
  schedule_reminder: '安排定时提醒',
  publish_qq: '发布到 QQ 活动群',
};

/** QQ 投递 kind → 中文标签 */
const DELIVERY_KIND_LABELS: Record<string, string> = {
  announcement: '活动发布',
  reminder: '定时提醒',
  manual: '手动催办',
};

/** 活动状态 → 中文标签 */
const STATUS_LABELS: Record<string, string> = {
  queued: '排队中',
  running: '执行中',
  ready: '已就绪',
  failed: '失败',
  finished: '已复盘',
};

export default function ActivityDetail() {
  const { id } = useParams<{ id: string }>();
  const { showToast } = useToast();

  const [detail, setDetail] = useState<ActivityDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [stats, setStats] = useState<FormStats | null>(null);

  const [messageContent, setMessageContent] = useState('');
  const [sending, setSending] = useState(false);
  const [recap, setRecap] = useState<string | null>(null);
  const [recapping, setRecapping] = useState(false);

  const [pendingTaskId, setPendingTaskId] = useState<string | null>(null);
  const [nudgingTaskId, setNudgingTaskId] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [refreshingStats, setRefreshingStats] = useState(false);

  /** 拉取活动详情；若存在报名问卷则顺带拉取报名统计 */
  const load = useCallback(
    async (silent = false) => {
      if (!id) return;
      try {
        const d = await api<ActivityDetail>(`/activities/${id}`);
        setDetail(d);
        if (d.forms && d.forms.length > 0) {
          try {
            const s = await api<FormStats>(`/activities/${id}/form-stats`);
            setStats(s);
          } catch {
            // 统计拉取失败不阻塞详情展示
          }
        } else {
          setStats(null);
        }
      } catch (e) {
        if (!silent) {
          showToast(e instanceof Error ? e.message : '加载失败', 'error');
        }
      } finally {
        if (!silent) setLoading(false);
      }
    },
    [id, showToast],
  );

  // 初次加载 + 10 秒轮询
  useEffect(() => {
    setLoading(true);
    load(false);
    const timer = setInterval(() => load(true), 10000);
    return () => clearInterval(timer);
  }, [load]);

  /** 勾选/取消任务 */
  const toggleTask = async (task: ActivityTask) => {
    const next: 'done' | 'pending' = task.status === 'done' ? 'pending' : 'done';
    setPendingTaskId(task.id);
    try {
      await api(`/tasks/${task.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ status: next }),
      });
      await load(true);
      showToast(next === 'done' ? '任务已完成' : '任务已恢复');
    } catch (e) {
      showToast(e instanceof Error ? e.message : '操作失败', 'error');
    } finally {
      setPendingTaskId(null);
    }
  };

  /** 催办任务 */
  const nudgeTask = async (task: ActivityTask) => {
    setNudgingTaskId(task.id);
    try {
      await api(`/activities/${id}/messages`, {
        method: 'POST',
        body: JSON.stringify({
          content: `【任务催办】请 ${task.assignee} 尽快完成「${task.title}」，完成后向活动负责人反馈进度。`,
          request_id: crypto.randomUUID(),
        }),
      });
      await load(true);
      showToast(`已在 QQ 群催办 ${task.assignee}`);
    } catch (e) {
      showToast(e instanceof Error ? e.message : '催办失败', 'error');
    } finally {
      setNudgingTaskId(null);
    }
  };

  /** 发送 QQ 群消息 */
  const sendMessage = async (e: FormEvent) => {
    e.preventDefault();
    const content = messageContent.trim();
    if (!content) {
      showToast('请填写通知内容', 'error');
      return;
    }
    setSending(true);
    try {
      await api(`/activities/${id}/messages`, {
        method: 'POST',
        body: JSON.stringify({ content, request_id: crypto.randomUUID() }),
      });
      setMessageContent('');
      await load(true);
      showToast('消息已发送到 QQ 群');
    } catch (e) {
      showToast(e instanceof Error ? e.message : '发送失败', 'error');
    } finally {
      setSending(false);
    }
  };

  /** 取消定时提醒 */
  const cancelReminder = async (reminderId: string) => {
    setCancelling(true);
    try {
      await api(`/reminders/${reminderId}/cancel`, { method: 'POST' });
      await load(true);
      showToast('提醒已取消');
    } catch (e) {
      showToast(e instanceof Error ? e.message : '取消失败', 'error');
    } finally {
      setCancelling(false);
    }
  };

  /** 手动刷新报名统计 */
  const refreshStats = async () => {
    if (!id || !detail || !detail.forms || detail.forms.length === 0) return;
    setRefreshingStats(true);
    try {
      const s = await api<FormStats>(`/activities/${id}/form-stats`);
      setStats(s);
      showToast('报名数据已刷新');
    } catch (e) {
      showToast(e instanceof Error ? e.message : '刷新失败', 'error');
    } finally {
      setRefreshingStats(false);
    }
  };

  /** 复制报名链接 */
  const copyFormLink = async (formId: string) => {
    try {
      await navigator.clipboard.writeText(`${window.location.origin}/forms/${formId}`);
      showToast('报名链接已复制');
    } catch {
      showToast('复制失败，请手动复制报名链接', 'error');
    }
  };

  /** 生成复盘 */
  const generateRecap = async () => {
    setRecapping(true);
    try {
      const result = await api<RecapResult>(`/activities/${id}/recap`, { method: 'POST' });
      setRecap(result.recap);
      await load(true);
      showToast('复盘已生成');
    } catch (e) {
      showToast(e instanceof Error ? e.message : '生成失败', 'error');
    } finally {
      setRecapping(false);
    }
  };

  if (!id) {
    return <div className="page-loading">活动不存在。</div>;
  }

  if (loading || !detail) {
    return <div className="page-loading">正在载入活动…</div>;
  }

  const activity = detail.activity;
  const plan = detail.plan ?? {};
  const tasks = detail.tasks ?? [];
  const steps = detail.steps ?? [];
  const deliveries = detail.deliveries ?? [];
  const reminders = detail.reminders ?? [];
  const forms = detail.forms ?? [];
  const reminder = reminders[0];
  const firstFormId = forms[0]?.id;

  const title = plan.title || activity.title || '未命名活动';
  const pillClass =
    activity.status === 'finished' ? 'done' : activity.status === 'failed' ? 'fail' : 'pending';
  const pillLabel = STATUS_LABELS[activity.status] || activity.status || '未知';

  const doneCount = tasks.filter((t) => t.status === 'done').length;
  const sentCount = deliveries.filter((d) => d.status === 'sent').length;
  const failedCount = deliveries.filter((d) => d.status === 'failed').length;

  const materials = plan.materials ?? [];
  const cancelledReminder = reminder?.status === 'cancelled';

  return (
    <>
      <header className="ws-header">
        <div>
          <p className="ws-kicker">ACTIVITY</p>
          <h1>{title}</h1>
          <p>
            {activity.raw_input ? `${activity.raw_input} · ` : ''}
            {formatDate(activity.created_at)}
          </p>
        </div>
        <div className="ws-actions">
          <span className={`ws-pill ${pillClass}`}>{pillLabel}</span>
        </div>
      </header>

      <div className="ws-grid">
        {/* 活动策划 + 物料（跨两列） */}
        <section className="ws-panel wide">
          <div className="ws-panel-title">
            <div>
              <h2>活动策划</h2>
              <p>活动时间 {formatDate(plan.event_time)}</p>
            </div>
            <a className="ws-button light" href={`/activities/${id}/calendar.ics`}>
              下载日历
            </a>
          </div>
          <p className="plan-description">{plan.description || '暂无活动说明'}</p>
          <h3 className="panel-sub">物料清单</h3>
          {materials.length > 0 ? (
            <div className="tag-list">
              {materials.map((m) => (
                <span key={m} className="tag">
                  {m}
                </span>
              ))}
            </div>
          ) : (
            <div className="ws-empty">暂无物料</div>
          )}
        </section>

        {/* 任务分工 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>任务分工</h2>
              <p>勾选标记完成，可一键催办 · {doneCount}/{tasks.length}</p>
            </div>
          </div>
          {tasks.length > 0 ? (
            <div className="task-list">
              {tasks.map((task) => (
                <label key={task.id} className={`task-item ${task.status === 'done' ? 'done' : ''}`}>
                  <input
                    className="task-toggle"
                    type="checkbox"
                    checked={task.status === 'done'}
                    disabled={pendingTaskId === task.id}
                    onChange={() => toggleTask(task)}
                  />
                  <span>
                    <strong>{task.title}</strong>
                    <small>负责人 {task.assignee}</small>
                  </span>
                  <span className="task-owner">
                    <button
                      className="task-nudge"
                      type="button"
                      disabled={nudgingTaskId === task.id}
                      onClick={() => nudgeTask(task)}
                    >
                      催一下
                    </button>
                  </span>
                </label>
              ))}
            </div>
          ) : (
            <div className="ws-empty">暂无任务</div>
          )}
        </section>

        {/* 报名统计 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>报名管理</h2>
              <p>
                公开链接无需登录即可填写 · {stats ? `${stats.count} 人已报名` : '–'}
              </p>
            </div>
            <button
              className="ws-button light"
              type="button"
              disabled={refreshingStats}
              onClick={refreshStats}
            >
              刷新
            </button>
          </div>
          {forms.length === 0 ? (
            <div className="ws-empty">还没有报名问卷</div>
          ) : (
            <>
              {firstFormId && (
                <div className="publish-bar" style={{ paddingTop: 0, marginBottom: 8 }}>
                  <button
                    className="ws-button light"
                    type="button"
                    onClick={() => copyFormLink(firstFormId)}
                  >
                    复制报名链接
                  </button>
                </div>
              )}
              {stats && stats.registrations.length > 0 ? (
                <div className="reg-list">
                  {stats.registrations.map((reg, idx) => (
                    <div key={idx} className="reg-row">
                      <strong>{reg.name}</strong>
                      <span>{reg.contact}</span>
                      <span>{formatDate(reg.created_at)}</span>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="ws-empty">还没有人报名</div>
              )}
            </>
          )}
        </section>

        {/* 自动执行记录 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>自动执行记录</h2>
              <p>Agent 各步骤的执行状态</p>
            </div>
          </div>
          {steps.length > 0 ? (
            <div className="step-list">
              {steps.map((step, idx) => (
                <div key={idx} className="step-item">
                  <span className="step-check">✓</span>
                  <div>
                    <strong>{STEP_LABELS[step.step] || step.step}</strong>
                    <small>{step.detail || '执行完成'}</small>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="ws-empty">暂无执行记录</div>
          )}
        </section>

        {/* 定时提醒 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>定时提醒</h2>
              <p>
                {!reminder
                  ? '未安排'
                  : cancelledReminder
                    ? '已取消'
                    : reminder.status === 'sent'
                      ? '已发送'
                      : '已安排'}
              </p>
            </div>
          </div>
          {!reminder ? (
            <div className="ws-empty">没有提醒任务</div>
          ) : (
            <div className="reminder-card">
              <strong>{formatDate(reminder.remind_at)}</strong>
              <span>{reminder.message}</span>
              {cancelledReminder ? (
                <small>该提醒已取消</small>
              ) : (
                <button
                  className="ws-button danger"
                  type="button"
                  disabled={cancelling}
                  onClick={() => cancelReminder(reminder.id)}
                >
                  取消提醒
                </button>
              )}
            </div>
          )}
        </section>

        {/* QQ 群消息（跨两列） */}
        <section className="ws-panel wide">
          <div className="ws-panel-title">
            <div>
              <h2>QQ 群消息</h2>
              <p>
                {deliveries.length > 0
                  ? `${sentCount} 条成功${failedCount ? ` · ${failedCount} 条失败` : ''}`
                  : '—'}
              </p>
            </div>
          </div>
          <form className="ws-form" onSubmit={sendMessage}>
            <label>
              通知内容
              <textarea
                rows={3}
                placeholder="输入要发到活动群的通知，例如：请各负责人今晚 8 点前反馈准备进度"
                value={messageContent}
                onChange={(e) => setMessageContent(e.target.value)}
              />
            </label>
            <div className="publish-bar">
              <button className="ws-button" type="submit" disabled={sending}>
                {sending ? '发送中…' : '发送到 QQ 群'}
              </button>
            </div>
          </form>
          {deliveries.length > 0 ? (
            <div className="delivery-list">
              {deliveries.map((item, idx) => (
                <div key={idx} className="delivery-row">
                  <span className="delivery-kind">
                    {DELIVERY_KIND_LABELS[item.kind] || item.kind}
                  </span>
                  <span className="delivery-content">{item.content}</span>
                  <span className={`delivery-status ${item.status === 'failed' ? 'fail' : ''}`}>
                    {item.status === 'sent' ? '已送达' : '发送失败'}
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <div className="ws-empty">还没有 QQ 投递记录</div>
          )}
        </section>

        {/* 复盘 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>活动复盘</h2>
              <p>基于报名与任务完成情况生成</p>
            </div>
            <button className="ws-button light" type="button" disabled={recapping} onClick={generateRecap}>
              {recapping ? '生成中…' : recap ? '重新生成' : '生成复盘'}
            </button>
          </div>
          {recap ? (
            <div className="recap-content">{recap}</div>
          ) : (
            <div className="ws-empty">活动结束后，AI 会结合报名与任务完成情况生成复盘。</div>
          )}
        </section>
      </div>
    </>
  );
}
