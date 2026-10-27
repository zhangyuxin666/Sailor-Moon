import { createContext, useContext, useState, useEffect, useRef, type FormEvent, type ReactNode } from 'react';
import { api } from './api';
import type { Account, AuthStatus } from '../types';

interface AuthContextValue {
  account: Account | null;
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}
const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<Account | null>(null);
  const authSequence = useRef(0);
  const accountRef = useRef(account);
  accountRef.current = account;
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expired, setExpired] = useState(false);
  const [password, setPassword] = useState('');
  const [signInError, setSignInError] = useState('');
  const [signingIn, setSigningIn] = useState(false);
  const dialog = useRef<HTMLDialogElement>(null);

  const refresh = async () => {
    const sequence = ++authSequence.current;
    setError(null);
    if (!accountRef.current) setLoading(true);
    try {
      const status = await api<AuthStatus>('/auth/status');
      if (sequence !== authSequence.current) return;
      if (!status.account && accountRef.current) setExpired(true);
      else setAccount(status.account);
    } catch (e) {
      if (sequence === authSequence.current) setError(e instanceof Error ? e.message : '认证状态加载失败');
    } finally {
      if (sequence === authSequence.current) setLoading(false);
    }
  };
  const logout = async () => {
    authSequence.current += 1;
    setLoading(false);
    try { await api('/auth/logout', { method: 'POST' }); } catch { /* Local exit remains available. */ }
    setAccount(null);
    setExpired(false);
    setPassword('');
    setSignInError('');
    setError(null);
  };
  const signIn = async (event: FormEvent) => {
    event.preventDefault();
    if (!account || signingIn) return;
    const sequence = ++authSequence.current;
    setSigningIn(true);
    setSignInError('');
    try {
      const signedIn = await api<Account>('/auth/login', {
        method: 'POST', body: JSON.stringify({ username: account.username, password }),
      });
      if (sequence !== authSequence.current) return;
      if (signedIn.username !== account.username) throw new Error('请使用当前账号重新登录');
      setAccount(signedIn);
      setExpired(false);
      setPassword('');
      setError(null);
    } catch (e) {
      if (sequence === authSequence.current) setSignInError(e instanceof Error ? e.message : '登录失败');
    } finally { setSigningIn(false); }
  };

  useEffect(() => {
    const requirePasswordChange = () => setAccount((current) => current ? { ...current, force_password_change: 1 } : current);
    const requireAuthentication = () => { if (accountRef.current) setExpired(true); };
    window.addEventListener('activity:password-change-required', requirePasswordChange);
    window.addEventListener('activity:authentication-required', requireAuthentication);
    void refresh();
    return () => {
      authSequence.current += 1;
      window.removeEventListener('activity:password-change-required', requirePasswordChange);
      window.removeEventListener('activity:authentication-required', requireAuthentication);
    };
  }, []);
  useEffect(() => {
    if (expired && account && dialog.current && !dialog.current.open) dialog.current.showModal();
    else if (dialog.current?.open) dialog.current.close();
  }, [expired, account]);

  return (
    <AuthContext.Provider value={{ account, loading, error, refresh, logout }}>
      {children}
      {account && <dialog ref={dialog} className="account-dialog" onCancel={(event) => event.preventDefault()} aria-labelledby="session-title">
        <form className="ws-form" onSubmit={signIn}>
          <h2 id="session-title">会话已过期</h2>
          <p>当前页面的未提交内容已保留。重新登录后，可继续操作并重试刚才的请求。</p>
          <label>用户名<input value={account.username} readOnly autoComplete="username" /></label>
          <label>密码<input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required /></label>
          {signInError && <p className="auth-error" role="alert">{signInError}</p>}
          <div className="ws-actions">
            <button type="submit" disabled={signingIn}>{signingIn ? '登录中…' : '重新登录'}</button>
            <button type="button" className="secondary" disabled={signingIn} onClick={() => void logout()}>退出当前账号</button>
          </div>
        </form>
      </dialog>}
    </AuthContext.Provider>
  );
}
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用');
  return ctx;
}
