import { useState, type FormEvent } from 'react';
import { useLocation } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { openWechat, wechatFeedback, type LoginMethodAvailability } from '../lib/login-methods';
import DataState from './DataState';
import PhoneCodeForm from './PhoneCodeForm';
import { useToast } from './Toast';

interface Bindings extends LoginMethodAvailability {
  bound_phone: string | null;
  wechat_bound: boolean;
}

export default function LoginMethodSettings() {
  const { search } = useLocation();
  const { showToast } = useToast();
  const { data, loading, error, reload } = useApi(() => api<Bindings>('/account/login-methods'));
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const feedback = wechatFeedback(search);

  async function changeBinding(event: FormEvent, remove?: 'phone' | 'wechat') {
    event.preventDefault();
    if (!password || busy) return;
    setBusy(true);
    try {
      if (remove) {
        await api(`/account/login-methods?provider=${remove}`, {
          method: 'DELETE', body: JSON.stringify({ password }),
        });
        reload();
        showToast('已解除绑定');
      } else {
        await openWechat(password);
      }
      setPassword('');
    } catch (cause) {
      showToast(cause instanceof Error ? cause.message : '操作失败，请重试', 'error');
    } finally {
      setBusy(false);
    }
  }

  return <section className="ws-panel">
    <div className="ws-panel-title"><div>
      <h2>登录方式</h2>
      <p>绑定后可使用短信验证码或微信扫码登录此账号。</p>
    </div></div>
    <DataState loading={loading} error={error} onRetry={reload} />
    {feedback && <p role="status">{feedback}</p>}
    {data && <>
      <p>手机号 · {data.bound_phone ?? (data.phone ? '未绑定' : '服务暂未启用')}</p>
      <p>微信 · {data.wechat_bound ? '已绑定' : data.wechat ? '未绑定' : '服务暂未启用'}</p>
      {data.phone && <details>
        <summary>{data.bound_phone ? '更换手机号' : '绑定手机号'}</summary>
        <PhoneCodeForm onBound={() => { showToast('手机号绑定成功'); reload(); }} />
      </details>}
      {(data.wechat || data.wechat_bound || data.bound_phone) && <form className="ws-form" onSubmit={event => {
        if (data.wechat) void changeBinding(event);
        else event.preventDefault();
      }}>
        <label>当前密码（绑定微信或解绑时验证）
          <input type="password" autoComplete="current-password" maxLength={128} required disabled={busy}
            value={password} onChange={event => setPassword(event.target.value)} />
        </label>
        <div className="ws-actions">
          {data.wechat && <button className="ws-button" type="submit" disabled={busy || !password}>
            {data.wechat_bound ? '扫码更换微信' : '扫码绑定微信'}
          </button>}
          {data.wechat_bound && <button className="ws-button light" type="button" disabled={busy || !password}
            onClick={event => void changeBinding(event, 'wechat')}>解绑微信</button>}
          {data.bound_phone && <button className="ws-button light" type="button" disabled={busy || !password}
            onClick={event => void changeBinding(event, 'phone')}>解绑手机号</button>}
        </div>
      </form>}
    </>}
  </section>;
}
