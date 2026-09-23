/**
 * 路由守卫组件
 * - RequireAuth：未登录跳 /login
 * - RequireManager：非管理者跳 /portal
 */
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import type { ReactNode } from 'react';

/** 需要登录才能访问的路由包装器 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { account, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return <div className="page-loading">加载中…</div>;
  }

  if (!account) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  return <>{children}</>;
}

/** 需要管理者角色才能访问的路由包装器 */
export function RequireManager({ children }: { children: ReactNode }) {
  const { account, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return <div className="page-loading">加载中…</div>;
  }

  if (!account) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (account.role !== 'manager') {
    return <Navigate to="/portal" replace />;
  }

  return <>{children}</>;
}
