/**
 * 认证上下文
 * 对应旧 frontend 中 workspace-common.js 的 App.init() 逻辑：
 * - 挂载时 GET /auth/status
 * - 无 account 跳 /login
 * - 提供 account 信息和角色判断
 */
import { createContext, useContext, useState, useEffect, type ReactNode } from 'react';
import { api } from './api';
import type { Account, AuthStatus } from '../types';

interface AuthContextValue {
  account: Account | null;
  loading: boolean;
  /** 重新获取认证状态 */
  refresh: () => Promise<void>;
  /** 登出 */
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<Account | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = async () => {
    try {
      const status = await api<AuthStatus>('/auth/status');
      setAccount(status.account);
    } catch {
      setAccount(null);
    } finally {
      setLoading(false);
    }
  };

  const logout = async () => {
    try {
      await api('/auth/logout', { method: 'POST' });
    } catch {
      // 忽略登出错误
    }
    setAccount(null);
  };

  useEffect(() => {
    refresh();
  }, []);

  return (
    <AuthContext.Provider value={{ account, loading, refresh, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

/** 获取认证上下文，必须在 AuthProvider 内使用 */
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用');
  return ctx;
}
