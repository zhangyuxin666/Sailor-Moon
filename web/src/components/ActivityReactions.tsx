import { useRef, useState } from 'react';
import { api, useApi } from '../lib/api';

interface ReactionStatus {
  liked: boolean;
  favorited: boolean;
  like_count: number;
}

export default function ActivityReactions({ activityId }: { activityId: string }) {
  const endpoint = `/activities/${encodeURIComponent(activityId)}/reactions`;
  const { data, error, reload } = useApi<ReactionStatus>(() => api(endpoint), [endpoint]);
  const [updated, setUpdated] = useState<ReactionStatus>();
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const status = updated ?? data;

  async function toggle(kind: 'like' | 'favorite', enabled: boolean) {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setFailure('');
    try {
      setUpdated(await api<ReactionStatus>(`${endpoint}/${kind}`, { method: enabled ? 'PUT' : 'DELETE' }));
    } catch {
      setFailure('操作失败，请重试');
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }

  if (!status) return error ? <button className="ws-button light" onClick={reload}>重试加载互动状态</button> : null;

  return <div className="ws-actions">
    <button type="button" className="ws-button light" aria-pressed={status.liked} disabled={busy}
      onClick={() => void toggle('like', !status.liked)}>
      {status.liked ? '取消点赞' : '点赞'} · {status.like_count}
    </button>
    <button type="button" className="ws-button light" aria-pressed={status.favorited} disabled={busy}
      onClick={() => void toggle('favorite', !status.favorited)}>
      {status.favorited ? '取消收藏' : '收藏'}
    </button>
    {failure && <span role="alert">{failure}</span>}
  </div>;
}
