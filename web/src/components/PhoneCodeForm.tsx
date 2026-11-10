import { useEffect, useState, type FormEvent } from 'react';
import { api } from '../lib/api';
import type { Account } from '../types';

type Props =
  | { onLogin: (account: Account) => Promise<void>; onBusyChange: (busy: boolean) => void }
  | { onBound: () => void };

export default function PhoneCodeForm(props: Props) {
  const binding = 'onBound' in props;
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [challenge, setChallenge] = useState('');
  const [resendAt, setResendAt] = useState(0);
  const [seconds, setSeconds] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const phoneValid = /^1[3-9][0-9]{9}$/.test(phone);

  useEffect(() => {
    if (!resendAt) return;
    const update = () => {
      const remaining = Math.max(0, Math.ceil((resendAt - Date.now()) / 1000));
      setSeconds(remaining);
      if (!remaining) setResendAt(0);
    };
    update();
    const timer = window.setInterval(update, 1000);
    return () => window.clearInterval(timer);
  }, [resendAt]);

  function setPending(pending: boolean) {
    setBusy(pending);
    if ('onBusyChange' in props) props.onBusyChange(pending);
  }

  async function sendCode() {
    if (busy || seconds || !phoneValid || (binding && !password)) return;
    setPending(true);
    setError('');
    try {
      const result = await api<{ challenge_id: string }>(binding ? '/account/login-methods/phone/code' : '/auth/phone/code', {
        method: 'POST', body: JSON.stringify(binding ? { phone, password } : { phone }),
      });
      setPassword('');
      setCode('');
      setChallenge(result.challenge_id);
      setResendAt(Date.now() + 60_000);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '短信发送失败，请重试');
    } finally {
      setPending(false);
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || !challenge || !/^[0-9]{6}$/.test(code)) return;
    setPending(true);
    setError('');
    const options = { method: 'POST', body: JSON.stringify({ challenge_id: challenge, code }) };
    try {
      if ('onBound' in props) {
        await api('/account/login-methods/phone', options);
        setPhone('');
        setCode('');
        setChallenge('');
        props.onBound();
      } else {
        await props.onLogin(await api<Account>('/auth/phone/login', options));
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '验证失败，请重试');
    } finally {
      setPending(false);
    }
  }

  return <form className={binding ? 'ws-form' : 'auth-form'} onSubmit={submit}>
    <label>中国大陆手机号
      <input type="tel" autoComplete="tel-national" inputMode="numeric" maxLength={11} disabled={busy}
        value={phone} onChange={event => { setPhone(event.target.value.trim()); setChallenge(''); setCode(''); setError(''); }} />
    </label>
    {binding && <label>当前密码（获取验证码时验证）
      <input type="password" autoComplete="current-password" maxLength={128} disabled={busy}
        value={password} onChange={event => setPassword(event.target.value)} />
    </label>}
    <button type="button" className={binding ? 'ws-button light' : 'valley-wood-button'}
      disabled={busy || !!seconds || !phoneValid || (binding && !password)} onClick={() => void sendCode()}>
      {seconds ? `${seconds} 秒后可重新发送` : '获取短信验证码'}
    </button>
    {challenge && <p role="status">{binding ? '验证码已发送，5 分钟内有效。' : '若手机号已绑定，将收到 5 分钟内有效的验证码。'}</p>}
    <label>短信验证码
      <input autoComplete="one-time-code" inputMode="numeric" maxLength={6} pattern="[0-9]{6}" required
        disabled={busy || !challenge} value={code} onChange={event => setCode(event.target.value)} />
    </label>
    {error && <p className="auth-error" role="alert">{error}</p>}
    <button type="submit" className={binding ? 'ws-button' : undefined}
      disabled={busy || !challenge || !/^[0-9]{6}$/.test(code)}>
      {binding ? '确认绑定手机号' : '登录并进入山谷'}
    </button>
  </form>;
}
