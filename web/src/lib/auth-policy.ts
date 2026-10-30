import type { Account } from '../types';

/** Initial or reset passwords only grant access to the password change screen. */
export function passwordChangeRequired(account: Account | null | undefined): boolean {
  return Number(account?.force_password_change ?? 0) > 0;
}

/** Shared by both route guards; password changes take precedence over role routing. */
export function protectedRedirect(
  account: Account | null,
  pathname: string,
  managerOnly = false,
): string | null {
  if (!account) return '/login';
  if (passwordChangeRequired(account) && pathname !== '/settings') return '/settings';
  if (managerOnly && account.role !== 'manager') return '/portal';
  return null;
}
