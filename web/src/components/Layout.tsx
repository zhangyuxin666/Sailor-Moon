/**
 * 工作台布局组件
 * 对应旧 frontend 中各页面共用的侧边栏壳（ws-shell / ws-sidebar / ws-nav）。
 * 6 项导航：总览 / AI助手(管理) / 作业与待办 / 活动管理(管理) / 成员管理(管理) / 设置
 * 非管理者隐藏 AI助手、活动管理、成员管理。
 */
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth';

const navItems = [
  { to: '/portal', label: '总览', icon: '◈', managerOnly: false },
  { to: '/agent', label: 'AI助手', icon: '✦', managerOnly: true },
  { to: '/assignments', label: '作业与待办', icon: '☰', managerOnly: false },
  { to: '/activity', label: '活动管理', icon: '▣', managerOnly: true },
  { to: '/members', label: '成员管理', icon: '◉', managerOnly: true },
  { to: '/settings', label: '设置', icon: '⚙', managerOnly: false },
];

export default function Layout() {
  const { account, logout } = useAuth();
  const navigate = useNavigate();
  const isManager = account?.role === 'manager';

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <div className="ws-shell">
      {/* 侧边栏 */}
      <aside className="ws-sidebar">
        <NavLink to="/portal" className="ws-logo">
          <i>活</i>
          <span>
            <strong>活动管家</strong>
            <small>CLASS OPS</small>
          </span>
        </NavLink>

        <nav className="ws-nav">
          {navItems
            .filter((item) => !item.managerOnly || isManager)
            .map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) => (isActive ? 'active' : '')}
              >
                <span>{item.icon}</span>
                {item.label}
              </NavLink>
            ))}
        </nav>

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
        <Outlet />
      </main>
    </div>
  );
}
