/**
 * 工作台布局组件
 * 对应旧 frontend 中各页面共用的侧边栏壳（ws-shell / ws-sidebar / ws-nav）。
 */
import { useEffect, useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import { passwordChangeRequired } from '../lib/auth-policy';
import ValleyIcon from './ValleyIcon';

const navItems = [
  { to: '/portal', label: '总览', icon: '◈', managerOnly: false },
  { to: '/agent', label: 'AI助手', icon: '✦', managerOnly: true },
  { to: '/assignments', label: '作业与待办', icon: '☰', managerOnly: false },
  { to: '/activity', label: '活动管理', icon: '▣', managerOnly: true },
  { to: '/members', label: '成员管理', icon: '◉', managerOnly: true },
  { to: '/knowledge', label: '知识库', icon: '▤', managerOnly: true },
  { to: '/participation', label: '签到与反馈', icon: '✓', managerOnly: false },
  { to: '/templates', label: '业务模板', icon: '▦', managerOnly: true },
  { to: '/settings', label: '设置', icon: '⚙', managerOnly: false },
];

export default function Layout() {
  const { account, logout } = useAuth();
  const navigate = useNavigate();
  const isManager = account?.role === 'manager';
  const mustChangePassword = passwordChangeRequired(account);
  const [clock, setClock] = useState(() => new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setClock(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <div className="ws-shell">
      {/* 侧边栏 */}
      <aside className="ws-sidebar">
        <NavLink to="/portal" className="ws-logo">
          <i><img className="valley-brand-chicken" src="/assets/stardew/chicken.png" alt="" /></i>
          <span>
            <strong>活动管家</strong>
            <small>CLASS JOURNAL</small>
          </span>
        </NavLink>

        <nav className="ws-nav">
          {navItems
            .filter((item) => mustChangePassword ? item.to === '/settings' : !item.managerOnly || isManager)
            .map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                aria-label={item.label}
                className={({ isActive }) => (isActive ? 'active' : '')}
              >
                <span><ValleyIcon glyph={item.icon} /></span>
                {item.label}
              </NavLink>
            ))}
        </nav>

        <div className="valley-sidebar-farm" aria-hidden="true" />
        {/* 底部用户信息 */}
        <div className="ws-user">
          <div className="ws-avatar">{account?.display_name?.slice(0, 1) || '?'}</div>
          <span>
            <strong>{account?.display_name || '未登录'}</strong>
            <small>{isManager ? '管理者' : '参与者'}</small>
          </span>
          <button
            className="ws-button light"
            style={{ marginLeft: 'auto', padding: '5px 9px', minHeight: '28px', fontSize: '9px' }}
            onClick={handleLogout}
          >
            退出
          </button>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className="ws-main">
        <div className="valley-workspace-bar">
          <span><img src="/assets/stardew/chicken.png" alt="" />今天也有新的收获</span>
          <time dateTime={clock.toISOString()}>{new Intl.DateTimeFormat('zh-CN', {
            timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'short',
          }).format(clock)}</time>
        </div>
        <Outlet />
      </main>
    </div>
  );
}
