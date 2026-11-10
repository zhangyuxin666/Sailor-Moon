import { api } from './api';

export interface LoginMethodAvailability {
  phone: boolean;
  wechat: boolean;
}

export async function openWechat(password?: string) {
  const result = await api<{ url: string }>(password === undefined ? '/auth/wechat/start' : '/account/login-methods/wechat', {
    method: 'POST',
    body: JSON.stringify(password === undefined ? {} : { password }),
  });
  window.location.assign(result.url);
}

export function wechatFeedback(search: string): string {
  switch (new URLSearchParams(search).get('wechat')) {
    case 'unbound': return '此微信尚未绑定，请先使用用户名和密码登录，再在设置中绑定微信。';
    case 'cancelled': return '微信授权已取消，请重新扫码。';
    case 'expired': return '微信二维码已过期或失效，请重新扫码。';
    case 'conflict': return '此微信已绑定其他账号。';
    case 'failed': return '微信授权失败，请重新扫码。';
    case 'bound': return '微信绑定成功。';
    default: return '';
  }
}
