import { useEffect, useMemo, useState } from 'react';
import DataState from '../components/DataState';
import { api, useApi } from '../lib/api';
import type { AnalyticsOverview, StatusPoint, TrendPoint } from '../types/analytics';
import '../styles/analytics.css';

const statusNames: Record<string, string> = {
  queued: '排队中', waiting_input: '待补充', ready: '进行中', finished: '已完成',
  cancelled: '已取消', failed: '失败', pending: '待完成', done: '已完成', unassigned: '未分配',
};

function JunimoSticker({ color = 'green', mood = 'happy', className = '' }: {
  color?: 'green' | 'blue' | 'yellow' | 'pink' | 'purple';
  mood?: 'happy' | 'calm';
  className?: string;
}) {
  return <span className={`junimo-sticker ${color} ${className}`} aria-hidden="true">
    <i className="junimo-leaf" /><i className="junimo-body">
      <b className="junimo-eye left" /><b className="junimo-eye right" />
      <b className={`junimo-mouth ${mood}`} />
    </i><i className="junimo-arm left" /><i className="junimo-arm right" />
  </span>;
}

function TrendChart({ points }: { points: TrendPoint[] }) {
  const width = 760; const height = 230; const padding = 30;
  const max = Math.max(1, ...points.flatMap((point) => [point.activities, point.registrations]));
  const line = (key: 'activities' | 'registrations') => points.map((point, index) => {
    const x = padding + index * (width - padding * 2) / Math.max(1, points.length - 1);
    const y = height - padding - point[key] / max * (height - padding * 2);
    return `${x},${y}`;
  }).join(' ');
  const labels = points.length <= 7 ? points : points.filter((_, index) => index % Math.ceil(points.length / 6) === 0 || index === points.length - 1);
  return <div className="analytics-chart" role="img" aria-label="活动与报名趋势折线图">
    <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none">
      {[0, .25, .5, .75, 1].map((ratio) => <line key={ratio} x1={padding} x2={width - padding}
        y1={padding + ratio * (height - padding * 2)} y2={padding + ratio * (height - padding * 2)} className="chart-grid" />)}
      <polyline points={line('activities')} className="chart-line activities" />
      <polyline points={line('registrations')} className="chart-line registrations" />
    </svg>
    <div className="chart-axis">{labels.map((point) => <span key={point.date}>{point.date.slice(5)}</span>)}</div>
  </div>;
}

function StatusBars({ data }: { data: StatusPoint[] }) {
  const max = Math.max(1, ...data.map((item) => item.value));
  return <div className="analytics-bars">
    {data.length === 0 && <div className="analytics-no-data">暂无数据</div>}
    {data.map((item) => <div className="analytics-bar" key={item.status}>
      <div><span>{statusNames[item.status] ?? item.status}</span><strong>{item.value}</strong></div>
      <i><b style={{ width: `${item.value / max * 100}%` }} /></i>
    </div>)}
  </div>;
}

export default function Analytics() {
  const [days, setDays] = useState(30);
  const [classId, setClassId] = useState('');
  const endpoint = useMemo(() => `/analytics/overview?days=${days}${classId ? `&class_id=${encodeURIComponent(classId)}` : ''}`,
    [days, classId]);
  const { data, loading, error, reload } = useApi<AnalyticsOverview>(() => api(endpoint), [endpoint]);

  useEffect(() => {
    const timer = window.setInterval(reload, 30_000);
    return () => window.clearInterval(timer);
  }, [reload]);

  const metrics = data ? [
    ['活动总数', data.summary.activities, `进行中 ${data.summary.active_activities}`],
    ['组织成员', data.summary.members, '已纳入统计'],
    ['有效报名', data.summary.registrations, '未取消报名'],
    ['任务完成率', `${data.summary.task_completion_rate}%`, '活动任务'],
    ['签到率', `${data.summary.attendance_rate}%`, '参与名单'],
  ] : [];

  return <div className="analytics-page">
    <div className="stardew-sticker-field" aria-hidden="true">
      <span className="farm-sticker chicken"><img src="/assets/stardew/chicken.png" alt="" /></span>
      <span className="farm-sticker bird"><img src="/assets/stardew/bird.gif" alt="" /></span>
      <span className="farm-sticker crop">🌻</span>
      <span className="farm-sticker berry">🍓</span>
      <span className="farm-sticker star">⭐</span>
      <span className="farm-sticker chest">🧰</span>
      <span className="farm-sticker leaf">🍃</span>
    </div>
    <div className="analytics-meadow" aria-hidden="true"><i /><i /><i /><i /></div>
    <JunimoSticker color="green" className="header-junimo" />
    <header className="ws-header analytics-header"><div>
      <p className="ws-kicker">DATA CENTER</p><h1>活动数据大屏</h1>
      <p>用统一指标观察活动、报名、任务执行和参与效果。</p>
    </div><div className="analytics-filters">
      <label>班级<select className="ws-select" value={classId} onChange={(event) => setClassId(event.target.value)}>
        <option value="">全部班级</option>{data?.classes.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
      </select></label>
      <label>周期<select className="ws-select" value={days} onChange={(event) => setDays(Number(event.target.value))}>
        <option value={7}>近 7 天</option><option value={30}>近 30 天</option><option value={90}>近 90 天</option>
      </select></label>
      <button className="ws-button light" type="button" onClick={reload}>刷新</button>
    </div></header>
    <DataState loading={loading} error={error} onRetry={reload} loadingText="数据大屏加载中…" />
    {!loading && !error && data && <>
      <section className="analytics-metrics">{metrics.map(([label, value, hint], index) => <article key={label} className={`metric-${index + 1}`}>
        <JunimoSticker color={(['green', 'blue', 'yellow', 'pink', 'purple'] as const)[index]} mood={index % 2 ? 'calm' : 'happy'} />
        <span>{label}</span><strong>{value}</strong><small>{hint}</small>
      </article>)}</section>
      <div className="analytics-grid wide-left">
        <section className="analytics-panel trend-panel"><span className="pixel-sticker calendar" aria-hidden="true">📅</span><JunimoSticker color="blue" className="panel-junimo" /><div className="analytics-title"><div><h2>增长趋势</h2><p>活动创建量与有效报名量</p></div>
          <div className="chart-legend"><span className="blue">活动</span><span className="green">报名</span></div></div>
          <TrendChart points={data.trend} /></section>
        <section className="analytics-panel alert-panel"><span className="pixel-sticker bell" aria-hidden="true">🔔</span><JunimoSticker color="yellow" mood="calm" className="panel-junimo" /><div className="analytics-title"><div><h2>风险提醒</h2><p>需要管理员关注的异常</p></div></div>
          <div className="alert-list">{data.alerts.map((alert) => <article className={alert.count ? 'active' : ''} key={alert.code}>
            <b>{alert.count}</b><span><strong>{alert.title}</strong><small>{alert.description}</small></span>
          </article>)}</div></section>
      </div>
      <div className="analytics-grid thirds">
        <section className="analytics-panel harvest-panel"><span className="pixel-sticker" aria-hidden="true">🌱</span><span className="panel-sticker-label">今日收获</span><div className="analytics-title"><div><h2>活动状态</h2><p>全部活动的当前阶段</p></div></div>
          <StatusBars data={data.activity_status} /></section>
        <section className="analytics-panel task-panel"><span className="pixel-sticker" aria-hidden="true">📜</span><span className="panel-sticker-label blue">协作进度</span><div className="analytics-title"><div><h2>任务状态</h2><p>完成情况与分工完整度</p></div></div>
          <StatusBars data={data.task_status} /></section>
        <section className="analytics-panel participation-panel"><span className="pixel-sticker" aria-hidden="true">💗</span><JunimoSticker color="pink" className="panel-junimo" /><div className="analytics-title"><div><h2>参与效果</h2><p>签到与活动反馈</p></div></div>
          <div className="participation-ring" style={{ '--rate': `${data.summary.attendance_rate * 3.6}deg` } as React.CSSProperties}>
            <div><strong>{data.summary.attendance_rate}%</strong><span>签到率</span></div></div>
          <dl className="participation-detail"><div><dt>参与名单</dt><dd>{data.participation.participants}</dd></div>
            <div><dt>实际签到</dt><dd>{data.participation.checked_in}</dd></div>
            <div><dt>反馈数量</dt><dd>{data.participation.feedback_count}</dd></div>
            <div><dt>平均评分</dt><dd>{data.participation.average_rating}/5</dd></div></dl>
        </section>
      </div>
      <p className="analytics-updated">数据每 30 秒自动刷新 · 最近更新 {new Date(data.generated_at).toLocaleString('zh-CN')}</p>
    </>}
  </div>;
}
