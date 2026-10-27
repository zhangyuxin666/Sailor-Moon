import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../lib/auth';
import { protectedRedirect } from '../lib/auth-policy';
import DataState from './DataState';
import type { ReactNode } from 'react';

function ProtectedRoute({ children, managerOnly = false }: { children: ReactNode; managerOnly?: boolean }) {
  const { account, loading, error, refresh } = useAuth();
  const location = useLocation();
  if (loading) return <div className="page-loading">加载中…</div>;
  if (error && !account) return <DataState error={error} onRetry={() => void refresh()} />;
  const redirect = protectedRedirect(account, location.pathname, managerOnly);
  if (redirect) return <Navigate to={redirect} state={{ from: location }} replace />;
  return <>{children}</>;
}

export function RequireAuth({ children }: { children: ReactNode }) {
  return <ProtectedRoute>{children}</ProtectedRoute>;
}

export function RequireManager({ children }: { children: ReactNode }) {
  return <ProtectedRoute managerOnly>{children}</ProtectedRoute>;
}
