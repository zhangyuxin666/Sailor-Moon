/**
 * 登录页
 * 对应旧 frontend/login.html + login.js。
 * 支持两种模式：
 * - 常规登录：POST /auth/login
 * - 首次初始化：POST /auth/bootstrap（建管理者/组织/密码，需同意隐私政策）
 * 登录成功后跳 /portal。
 */
import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { api } from '../lib/api';
import { useAuth } from '../lib/auth';
import type { AuthStatus } from '../types';

export default function Login() {
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const [initialized, setInitialized] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // 表单字段
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [organizationName, setOrganizationName] = useState('我的班级组织');
  const [acceptPrivacy, setAcceptPrivacy] = useState(false);

  // 挂载时检查认证状态和系统初始化状态
  useEffect(() => {
    api<AuthStatus>('/auth/status')
      .then((status) => {
        if (status.account) {
          navigate('/portal', { replace: true });
          return;
        }
        setInitialized(status.initialized);
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [navigate]);

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

      await api(initialized ? '/auth/login' : '/auth/bootstrap', {
        method: 'POST',
        body: JSON.stringify(body),
      });

      await refresh();
      navigate('/portal', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return <div className="page-loading">加载中…</div>;
  }

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
                minLength={6}
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
