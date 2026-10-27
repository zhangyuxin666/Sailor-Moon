/**
 * 设置页 /settings
 * 账号信息、修改密码、QQ 集成状态、隐私数据、退出登录、操作审计
 */
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { useAuth } from '../lib/auth';
import { passwordChangeRequired } from '../lib/auth-policy';
import { useToast } from '../components/Toast';
import DataState from '../components/DataState';
import { formatDate } from '../lib/utils';
import type { AuditLogResponse, QQStatus } from '../types';

export default function Settings() {
  const { account, logout } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();
  const isManager = account?.role === 'manager';
  const mustChangePassword = passwordChangeRequired(account);

  // 修改密码表单
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [changing, setChanging] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);

  // QQ 集成状态
  const { data: qqStatus, loading: qqLoading, error: qqError, reload: reloadQq } = useApi<QQStatus>(
    () => mustChangePassword
      ? Promise.resolve({ configured: false, groups: [] })
      : api<QQStatus>('/integrations/qq/status'),
    [mustChangePassword],
  );

  // 操作审计（仅管理者发起请求）
  const { data: auditData, loading: auditLoading, error: auditError, reload: reloadAudit } = useApi<AuditLogResponse>(
    () =>
      isManager && !mustChangePassword
        ? api<AuditLogResponse>('/audit-logs?limit=50')
        : Promise.resolve({ logs: [] as AuditLogResponse['logs'] }),
    [isManager, mustChangePassword],
  );

  const gatewayStatus = qqStatus?.gateway?.status;
  const gatewayDotClass =
    gatewayStatus === 'online'
      ? 'connection-dot online'
      : gatewayStatus === 'error'
        ? 'connection-dot error'
        : 'connection-dot';

  const bound = (qqStatus?.groups?.length ?? 0) > 0;

  async function handleChangePassword(e: FormEvent) {
    e.preventDefault();
    if (changing) return;
    if (newPassword.length < 8) {
      showToast('新密码至少 8 位', 'error');
      return;
    }
    if (newPassword !== confirmPassword) {
      showToast('两次输入的新密码不一致', 'error');
      return;
    }
    setChanging(true);
    try {
      await api('/auth/change-password', {
        method: 'POST',
        body: JSON.stringify({
          current_password: currentPassword,
          new_password: newPassword,
        }),
      });
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      await logout();
      navigate('/login', { replace: true, state: { passwordChanged: true } });
    } catch (err) {
      showToast(err instanceof Error ? err.message : '修改失败', 'error');
    } finally {
      setChanging(false);
    }
  }

  async function handleLogout() {
    if (loggingOut || changing) return;
    setLoggingOut(true);
    try {
      await logout();
      navigate('/login', { replace: true });
    } catch (cause) {
      showToast(cause instanceof Error ? cause.message : '退出失败，请重试', 'error');
    } finally {
      setLoggingOut(false);
    }
  }

  async function copyBindingCode(code: string) {
    try {
      await navigator.clipboard.writeText(`绑定 ${code}`);
      showToast('已复制绑定命令');
    } catch {
      showToast('复制失败，请手动复制', 'error');
    }
  }

  return (
    <>
      <header className="ws-header">
        <div>
          <p className="ws-kicker">SETTINGS</p>
          <h1>{mustChangePassword ? '请先修改密码' : '设置'}</h1>
          <p>{mustChangePassword ? '请将初始密码或管理员重置的密码更换为你自己的密码。' : '管理登录安全、QQ 机器人和个人数据。'}</p>
        </div>
      </header>

      {mustChangePassword && <p className="reason-box" role="status">修改密码前，仅可修改密码或退出账号。修改成功后请使用新密码重新登录。</p>}

      <div className="ws-grid">
        {/* 账号信息 + 修改密码 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>账号信息</h2>
              <p>
                {account?.display_name} · {account?.username} · {account?.role === 'manager' ? '管理者' : '参与者'}
              </p>
            </div>
          </div>
          <form className="ws-form" onSubmit={handleChangePassword}>
            <label>
              当前密码
              <input
                type="password"
                required
                autoComplete="current-password"
                disabled={changing}
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
              />
            </label>
            <label>
              新密码
              <input
                type="password"
                minLength={8}
                required
                autoComplete="new-password"
                maxLength={128}
                disabled={changing}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
            </label>
            <label>
              确认新密码
              <input
                type="password"
                minLength={8}
                required
                autoComplete="new-password"
                maxLength={128}
                disabled={changing}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
              />
            </label>
            <button className="ws-button" type="submit" disabled={changing}>
              {changing ? '提交中…' : '修改密码'}
            </button>
          </form>
        </section>

        {!mustChangePassword && <>
        {/* QQ 集成状态 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>QQ 机器人</h2>
              <p>群绑定与消息网关状态</p>
            </div>
            <span className={gatewayDotClass}></span>
          </div>
          <DataState loading={qqLoading} error={qqError} onRetry={reloadQq} loadingText="正在读取机器人状态…" />
          {!qqLoading && !qqError && qqStatus && <div className="integration-strip reason-box">
            <strong>{bound ? '已绑定活动群' : '尚未绑定群'}</strong>
            <br />
            {qqStatus?.gateway?.detail ?? '网关状态未知'}
            <br />
            公开地址：{qqStatus?.public_base_url ?? '未配置'}
            {qqStatus?.binding_code && (
              <div
                className="binding-command"
                onClick={() => copyBindingCode(qqStatus.binding_code!)}
                style={{ cursor: 'pointer', marginTop: 8 }}
              >
                绑定命令：绑定 {qqStatus.binding_code}（点击复制）
              </div>
            )}
          </div>}
          <p>可用绑定命令连接其他 QQ 群；完成后到成员管理，为目标班级选择并保存通知群。</p>
        </section>

        {/* 隐私与数据 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>隐私与数据</h2>
              <p>查看政策并获取个人数据副本</p>
            </div>
          </div>
          <div className="ws-actions">
            <a className="ws-button light" href="/privacy" target="_blank" rel="noreferrer">
              隐私政策
            </a>
            <a className="ws-button light" href="/account/export">
              导出我的数据
            </a>
          </div>
        </section>

        </>}

        {/* 登录会话 */}
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>登录会话</h2>
              <p>在公共设备上使用后请退出</p>
            </div>
          </div>
          <button className="ws-button dark" disabled={loggingOut || changing} onClick={handleLogout}>
            {loggingOut ? '正在退出…' : '退出当前账号'}
          </button>
        </section>
      </div>

      {/* 操作审计（仅管理者） */}
      {isManager && !mustChangePassword && (
        <section className="ws-panel" style={{ marginTop: 13 }}>
          <div className="ws-panel-title">
            <div>
              <h2>操作审计</h2>
              <p>最近 50 条关键管理操作</p>
            </div>
            <button className="ws-button light" disabled={auditLoading} onClick={reloadAudit}>
              刷新
            </button>
          </div>
          <DataState loading={auditLoading} error={auditError} onRetry={reloadAudit} loadingText="正在读取审计记录…" />
          {!auditLoading && !auditError && <div className="ws-list">
            {(auditData?.logs ?? []).length === 0 && (
              <div className="ws-empty">暂无审计记录</div>
            )}
            {(auditData?.logs ?? []).map((log, i) => (
              <div key={log.id ?? i} className="ws-list-item">
                <span className="ws-tag">{log.action}</span>
                <span>
                  <strong>{log.resource_type || '系统操作'}</strong>
                  <small>{log.resource_id || ''}</small>
                </span>
                <small>{formatDate(log.created_at)}</small>
              </div>
            ))}
          </div>}
        </section>
      )}
    </>
  );
}
