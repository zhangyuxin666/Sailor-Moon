import { useEffect, useState } from 'react';

const WEEK = ['日', '一', '二', '三', '四', '五', '六'];

type Todo = { done_count?: number; my_status?: string };

/**
 * 固定在每个页面右上角的星露谷时钟：真实日期、真实时间、金币。
 * 金币 = 已完成待办数 × 100，数据从 /portal/dashboard 取。
 */
export default function GameClock() {
  const [now, setNow] = useState(() => new Date());
  const [gold, setGold] = useState(0);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 10_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    let cancelled = false;
    fetch('/portal/dashboard', { credentials: 'include' })
      .then((response) => (response.ok ? response.json() : null))
      .then((data) => {
        if (!data || cancelled) return;
        const todos: Todo[] = data.todos ?? [];
        const tasks: Todo[] = data.activity_tasks ?? [];
        const done = todos.reduce((sum, todo) => sum
          + (todo.done_count ?? (todo.my_status === 'done' ? 1 : 0)), 0)
          + tasks.filter((task) => task.my_status === 'done').length;
        setGold(done * 100);
      })
      .catch(() => { /* 未登录或接口异常时不显示金币，时钟本身仍然可用 */ });
    return () => { cancelled = true; };
  }, []);

  const date = `${now.getMonth() + 1}月${now.getDate()}日 星期${WEEK[now.getDay()]}`;
  const time = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;

  return (
    <div className="game-clock" aria-label={`日期 ${date}，时间 ${time}，金币 ${gold}`}>
      <img src="/assets/stardew/ui/clock.png" alt="" draggable={false} />
      <span className="game-clock-date">{date}</span>
      <span className="game-clock-time">{time}</span>
      <span className="game-clock-gold">{gold.toLocaleString('en-US')}</span>
    </div>
  );
}
