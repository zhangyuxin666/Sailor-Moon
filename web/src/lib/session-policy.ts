/** Only authentication failures should interrupt editing with a sign-in dialog. */
export function sessionRequired(path: string, status: number, code?: string, detail?: string): boolean {
  if (['/auth/login', '/auth/bootstrap', '/auth/logout', '/auth/status'].includes(path.split('?')[0])) return false;
  if (code === 'password_change_required') return false;
  return code === 'authentication_required' || status === 401 || (status === 403 && detail === '请先登录');
}
