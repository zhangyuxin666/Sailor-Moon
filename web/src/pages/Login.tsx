/**
 * 登录页
 * 对应旧 frontend/login.html + login.js。
 * 支持两种模式：
 * - 常规登录：POST /auth/login
 * - 首次初始化：POST /auth/bootstrap（建管理者/组织/密码，需同意隐私政策）
 * 登录成功后跳 /portal。
 */
import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate, useLocation, Link } from 'react-router-dom';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import type { Account, AuthStatus } from '../types';
import DataState from '../components/DataState';
import { passwordChangeRequired } from '../lib/auth-policy';

export default function Login() {
  const navigate = useNavigate();
  const location = useLocation();
  const passwordChanged = (location.state as { passwordChanged?: boolean } | null)?.passwordChanged === true;
  const { refresh } = useAuth();
  const [initialized, setInitialized] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [statusError, setStatusError] = useState('');
  const [statusAttempt, setStatusAttempt] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  // 表单字段
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [organizationName, setOrganizationName] = useState('我的班级组织');
  const [acceptPrivacy, setAcceptPrivacy] = useState(false);

  // 挂载时检查认证状态和系统初始化状态
  useEffect(() => {
    setLoading(true);
    setStatusError('');
    let active = true;
    api<AuthStatus>('/auth/status')
      .then((status) => {
        if (!active) return;
        if (status.account) {
          navigate(passwordChangeRequired(status.account) ? '/settings' : '/portal', { replace: true });
          return;
        }
        setInitialized(status.initialized);
      })
      .catch((e) => { if (active) setStatusError(e.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [navigate, statusAttempt]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    setSubmitting(true);

    try {
      const body: Record<string, unknown> = { username: username.trim(), password };
      if (!initialized) {
        body.display_name = displayName.trim();
        body.organization_name = organizationName.trim();
        body.accept_privacy = acceptPrivacy;
      }

      const signedIn = await api<Account>(initialized ? '/auth/login' : '/auth/bootstrap', {
        method: 'POST',
        body: JSON.stringify(body),
      });

      await refresh();
      navigate(passwordChangeRequired(signedIn) ? '/settings' : '/portal', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return <div className="page-loading">加载中…</div>;
  }

  if (statusError) return <DataState error={statusError} onRetry={() => setStatusAttempt((value) => value + 1)} />;

  return (
    <div className="auth-page">
      <main className="auth-shell">
        {/* 左侧品牌面板 */}
        <section className="auth-brand-panel">
          <div className="portal-brand">
            <span>活</span>
            <strong>活动管家</strong>
          </div>
          <div className="auth-hero">
            <p>CLASS OPERATIONS</p>
            <h1>
              班级里的每件事，
              <br />
              都有清楚的进度。
            </h1>
            <ul>
              <li>收作业与资料</li>
              <li>待办分配和完成追踪</li>
              <li>QQ 自动提醒未完成人员</li>
            </ul>
          </div>
        </section>

        {/* 右侧表单面板 */}
        <section className="auth-form-panel">
          <form className="auth-form" onSubmit={handleSubmit}>
            <p className="portal-eyebrow">
              {initialized ? 'WELCOME BACK' : 'INITIAL SETUP'}
            </p>
            <h2>{initialized ? '登录工作台' : '创建管理者账号'}</h2>
            <p>
              {initialized
                ? '管理者和参与者使用各自账号登录。'
                : '首次使用，请先建立班级管理者账号。'}
            </p>

            {passwordChanged && <p className="reason-box" role="status">密码已更新，所有旧会话已退出。请使用新密码重新登录。</p>}

            {/* 初始化模式：姓名 */}
            {!initialized && (
              <label>
                姓名
                <input
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  autoComplete="name"
                  required
                />
              </label>
            )}

            {/* 初始化模式：组织名称 */}
            {!initialized && (
              <label>
                组织名称
                <input
                  value={organizationName}
                  onChange={(e) => setOrganizationName(e.target.value)}
                />
              </label>
            )}

            <label>
              用户名
              <input
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
                required
              />
            </label>

            <label>
              密码
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                required
                minLength={initialized ? 1 : 8}
                placeholder={initialized ? undefined : '至少 8 位'}
              />
            </label>

            {/* 初始化模式：隐私政策同意 */}
            {!initialized && (
              <label className="privacy-check">
                <input
                  type="checkbox"
                  checked={acceptPrivacy}
                  onChange={(e) => setAcceptPrivacy(e.target.checked)}
                  required={!initialized}
                />
                我已阅读并同意{' '}
                <Link to="/privacy" target="_blank">
                  隐私政策与用户协议
                </Link>
              </label>
            )}

            <button type="submit" disabled={submitting}>
              {initialized ? '登录' : '完成初始化'} <b>→</b>
            </button>

            {error && <div className="auth-error">{error}</div>}
          </form>
        </section>
      </main>
    </div>
  );
}
