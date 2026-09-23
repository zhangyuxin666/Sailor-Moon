/**
 * API 请求层
 * 封装 fetch，统一处理 JSON / 错误 / 会话 cookie（credentials: 'include'）。
 * 对应旧 frontend 中 App.api() 和 api() 函数。
 */
import { useState, useEffect, useCallback, useRef } from 'react';

export interface ApiError extends Error {
  status?: number;
}

/**
 * 发起 API 请求。
 * - 默认带 cookie（同源会话）
 * - 自动 JSON 序列化 body（除非 body 是 FormData）
 * - 自动解析 JSON 或文本响应
 * - 非 2xx 抛出 ApiError，message 取后端 detail 字段
 */
export async function api<T = unknown>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers: Record<string, string> = {};

  // FormData 时让浏览器自动设置 Content-Type（含 boundary）
  if (!(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }
  Object.assign(headers, options.headers || {});

  const response = await fetch(path, {
    ...options,
    headers,
    credentials: 'include',
  });

  const contentType = response.headers.get('content-type') || '';
  const data: unknown = contentType.includes('application/json')
    ? await response.json()
    : await response.text();

  if (!response.ok) {
    const detail =
      (data as { detail?: string })?.detail ||
      (typeof data === 'string' ? data : '') ||
      `请求失败（${response.status}）`;
    const message = Array.isArray(detail)
      ? (detail as Array<{ msg: string }>).map((i) => i.msg).join('；')
      : String(detail);
    const error = new Error(message) as ApiError;
    error.status = response.status;
    throw error;
  }

  return data as T;
}

/**
 * React Hook：封装数据加载状态。
 * 用法：const { data, loading, error, reload } = useApi(() => api<DashboardData>('/portal/dashboard'));
 *
 * @param fetcher 返回 Promise 的函数
 * @param deps 依赖数组，变化时重新加载
 */
export function useApi<T>(
  fetcher: () => Promise<T>,
  deps: unknown[] = [],
): {
  data: T | null;
  loading: boolean;
  error: string | null;
  reload: () => void;
} {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetcher();
      if (mountedRef.current) {
        setData(result);
      }
    } catch (e) {
      if (mountedRef.current) {
        setError(e instanceof Error ? e.message : '请求失败');
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false);
      }
    }
  }, deps);

  useEffect(() => {
    mountedRef.current = true;
    load();
    return () => {
      mountedRef.current = false;
    };
  }, [load]);

  return { data, loading, error, reload: load };
}
