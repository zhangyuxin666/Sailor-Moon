import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate, useLocation, Link } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { useAuth } from '../lib/auth';
import type { Account, AuthStatus } from '../types';
import DataState from '../components/DataState';
import { passwordChangeRequired } from '../lib/auth-policy';
import StardewLoginScene from '../components/StardewLoginScene';
import ValleyCharacterEditor, { DEFAULT_APPEARANCE, readAppearance, type CharacterAppearance } from '../components/ValleyCharacterEditor';
import PhoneCodeForm from '../components/PhoneCodeForm';
import { openWechat, wechatFeedback, type LoginMethodAvailability } from '../lib/login-methods';

type Role = 'manager' | 'participant' | 'guest';
const roles: { id: Role; name: string; description: string }[] = [
  { id: 'manager', name: '管理者', description: '建立组织，安排活动与任务' },
  { id: 'participant', name: '参与者', description: '加入班级，完成自己的任务' },
  { id: 'guest', name: '游客', description: '无需账号，游览示例山谷' },
];

export default function Login() {
  const navigate = useNavigate();
  const location = useLocation();
  const { refresh } = useAuth();
  const [initialized, setInitialized] = useState(true);
  const [loading, setLoading] = useState(true);
  const [statusError, setStatusError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [creating, setCreating] = useState(false);
  const [method, setMethod] = useState<'password' | 'phone'>('password');
  const { data: methods, loading: methodsLoading, error: methodsError, reload: reloadMethods } =
    useApi(() => api<LoginMethodAvailability>('/auth/login-methods'));
  const [role, setRole] = useState<Role>('manager');
  const [guestTour, setGuestTour] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [organizationName, setOrganizationName] = useState('');
  const [acceptPrivacy, setAcceptPrivacy] = useState(false);
  const [appearance, setAppearance] = useState<CharacterAppearance>(() => {
    try { return readAppearance(JSON.parse(localStorage.getItem('valley-character:draft') || 'null')); }
    catch { return { ...DEFAULT_APPEARANCE }; }
  });
  const changeAppearance = (value: CharacterAppearance) => {
    setAppearance(value);
    try {
      localStorage.setItem('valley-character:draft', JSON.stringify(value));
      if (username.trim()) localStorage.setItem(`valley-character:${username.trim()}`, JSON.stringify(value));
    } catch { /* Browser storage is optional. */ }
  };
  const newUser = creating || !initialized;
  const showMethods = !newUser && role !== 'guest';
  const wechatMessage = wechatFeedback(location.search);
  const passwordChanged = (location.state as { passwordChanged?: boolean } | null)?.passwordChanged === true;

  useEffect(() => {
    let active = true;
    setLoading(true); setStatusError('');
    api<AuthStatus>('/auth/status').then(status => {
      if (!active) return;
      if (status.account) navigate(passwordChangeRequired(status.account) ? '/settings' : '/portal', { replace: true });
      setInitialized(status.initialized);
      if (!status.initialized) { setCreating(true); setRole('manager'); }
    }).catch(e => { if (active) setStatusError(e.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [navigate, attempt]);

  useEffect(() => {
    try {
      const saved = JSON.parse(localStorage.getItem(`valley-character:${username.trim()}`) || 'null');
      if (saved) setAppearance(readAppearance(saved));
    } catch { /* Browser storage is optional. */ }
  }, [username]);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting) return;
    setError('');
    if (role === 'guest') { setGuestTour(true); return; }
    if (newUser && password !== confirmPassword) { setError('两次输入的密码不一致'); return; }
    setSubmitting(true);
    try {
      const body: Record<string, unknown> = { username: username.trim(), password };
      if (newUser) Object.assign(body, { display_name: displayName.trim(), organization_name: organizationName.trim() || '我的山谷组织', accept_privacy: acceptPrivacy, role });
      const signedIn = await api<Account>(!initialized ? '/auth/bootstrap' : newUser ? '/auth/register' : '/auth/login', { method: 'POST', body: JSON.stringify(body) });
      // Login permissions always come from the authenticated account, never the visual mode selector.
      try { localStorage.setItem(`valley-character:${signedIn.username}`, JSON.stringify(appearance)); } catch { /* Storage may be unavailable. */ }
      await refresh();
      navigate(passwordChangeRequired(signedIn) ? '/settings' : '/portal', { replace: true });
    } catch (e) { setError(e instanceof Error ? e.message : '暂时无法进入山谷，请重试'); }
    finally { setSubmitting(false); }
  };

  async function finishPhone(signedIn: Account) {
    await refresh();
    navigate(passwordChangeRequired(signedIn) ? '/settings' : '/portal', { replace: true });
  }

  async function startWechat() {
    setSubmitting(true);
    setError('');
    try { await openWechat(); }
    catch (cause) {
      setError(cause instanceof Error ? cause.message : '暂时无法打开微信二维码，请重试');
    } finally {
      setSubmitting(false);
    }
  }

  return <StardewLoginScene loading={loading}>
    {loading ? <p role="status">正在打开山谷日记…</p>
      : statusError ? <DataState error={statusError} onRetry={() => setAttempt(v => v + 1)} />
      : guestTour ? <div className="valley-tour">
        <p className="portal-eyebrow">GUEST JOURNAL · 示例内容</p><h2 tabIndex={-1}>欢迎来到示例山谷</h2>
        <p>这里的活动与任务为演示内容。游客不访问真实班级数据，也不会创建账号。</p>
        <div className="valley-manual-grid"><article><h3>春日野餐</h3><p>周六 10:00 · 山谷草坪</p><p>准备野餐篮，和伙伴们一起出发。</p></article><article><h3>今日待办</h3><p>✓ 确定活动地点</p><p>□ 准备物料清单</p><p>□ 收集伙伴的报名信息</p></article></div>
        <button className="valley-wood-button" onClick={() => { setGuestTour(false); setRole('participant'); setCreating(true); }}>创建自己的用户</button>
        <button className="valley-text-button" onClick={() => setGuestTour(false)}>返回身份选择</button>
      </div> : <>
        <header className="valley-character-heading"><p className="portal-eyebrow">A NEW DAY IN THE VALLEY</p><h2 tabIndex={-1}>{newUser ? '建立你的山谷档案' : '欢迎回到山谷'}</h2><p>选一个身份，开始属于你的故事。</p></header>
        <div className="valley-character-layout">
          <ValleyCharacterEditor value={appearance} onChange={changeAppearance} name={displayName || username} role={roles.find(r => r.id === role)?.name || ''} />
          <div className="valley-account-fields">
            <div className="valley-role-picker" role="group" aria-label="选择身份">
              {roles.map(item => <button type="button" key={item.id} aria-pressed={role === item.id} disabled={submitting || (!initialized && item.id === 'participant')} onClick={() => { setRole(item.id); setError(''); }}><strong>{item.name}</strong><small>{item.description}</small></button>)}
            </div>
            {role !== 'guest' && initialized && <div className="valley-account-tabs" role="group" aria-label="账号操作"><button type="button" disabled={submitting} aria-pressed={!creating} onClick={() => { setCreating(false); setError(''); }}>已有账号登录</button><button type="button" disabled={submitting} aria-pressed={creating} onClick={() => { setCreating(true); setError(''); }}>创建用户</button></div>}
            {showMethods && <>
              <div className="valley-account-tabs valley-login-methods" role="group" aria-label="登录方式">
                <button type="button" aria-pressed={method === 'password'} disabled={submitting}
                  onClick={() => { setMethod('password'); setError(''); }}>用户名密码</button>
                <button type="button" aria-pressed={method === 'phone'} disabled={submitting || !methods?.phone}
                  onClick={() => { setMethod('phone'); setError(''); }}>手机号登录</button>
                <button type="button" disabled={submitting || !methods?.wechat} onClick={() => void startWechat()}>微信扫码登录</button>
              </div>
              {methodsError ? <DataState error={methodsError} onRetry={reloadMethods} /> : !methodsLoading && <p className="valley-account-note">
                {methods?.phone && methods?.wechat ? '请先在设置中绑定手机号或微信。' :
                  methods?.phone ? '请先在设置中绑定手机号。微信扫码登录暂未启用。' :
                    methods?.wechat ? '请先在设置中绑定微信。手机号登录暂未启用。' : '手机号和微信扫码登录暂未启用。'}
              </p>}
              {wechatMessage && <p className="auth-error" role="alert">{wechatMessage}</p>}
            </>}
            {showMethods && method === 'phone' && methods?.phone ? <>
              {error && <div className="auth-error" role="alert">{error}</div>}
              <PhoneCodeForm onLogin={finishPhone} onBusyChange={setSubmitting} />
            </> : <form className="auth-form" onSubmit={handleSubmit}>
              {role === 'guest' ? <div className="valley-guest-note"><h3>随时来山谷坐坐</h3><p>无需用户名和密码。先浏览示例活动与任务，准备好后再创建自己的档案。</p></div> : <>
                {passwordChanged && <p role="status">密码已更新，请重新登录。</p>}
                {!initialized && <p className="valley-account-note">首次使用，请先创建管理者和组织。</p>}
                {newUser && <label>姓名<input autoComplete="name" required maxLength={40} value={displayName} onChange={e => setDisplayName(e.target.value)} /></label>}
                {newUser && role === 'manager' && <label>组织名称<input required maxLength={80} value={organizationName} placeholder="例如：软件工程五班" onChange={e => setOrganizationName(e.target.value)} /></label>}
                <label>用户名<input autoComplete="username" required minLength={newUser ? 3 : 1} maxLength={40} pattern={newUser ? '[A-Za-z0-9_-]{3,40}' : undefined} title={newUser ? '3—40位英文字母、数字、下划线或短横线' : undefined} value={username} onChange={e => setUsername(e.target.value)} /></label>
                <label>密码<input type="password" autoComplete={newUser ? 'new-password' : 'current-password'} required minLength={newUser ? 8 : 1} maxLength={128} placeholder={newUser ? '至少8位' : undefined} value={password} onChange={e => setPassword(e.target.value)} /></label>
                {newUser && <label>确认密码<input type="password" autoComplete="new-password" required minLength={8} maxLength={128} value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)} /></label>}
                {newUser ? <label className="privacy-check"><input type="checkbox" required checked={acceptPrivacy} onChange={e => setAcceptPrivacy(e.target.checked)} />我已阅读并同意 <Link to="/privacy" target="_blank">隐私政策与用户协议</Link></label> : <p className="valley-account-note">身份以账号实际权限为准；选择身份不会更改账号权限。</p>}
                {newUser && role === 'participant' && <p className="valley-account-note">注册不会自动加入班级。已有班级账号请直接登录；班级成员由管理者统一导入。</p>}
              </>}
              {error && <div className="auth-error" role="alert">{error}</div>}
              <button type="submit" disabled={submitting}>{submitting ? '正在打开日记…' : role === 'guest' ? '以游客身份游览' : newUser ? '创建用户并进入山谷' : '登录并进入山谷'} <b aria-hidden="true">→</b></button>
            </form>}
          </div>
        </div>
      </>}
  </StardewLoginScene>;
}
