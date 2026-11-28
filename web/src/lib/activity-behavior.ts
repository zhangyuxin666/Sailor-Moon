import { useEffect } from 'react';

export function trackActivity(activityId: string, eventType: 'view' | 'dwell' | 'share_clicked', durationMs?: number) {
  void fetch('/behavior/events', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    keepalive: true,
    body: JSON.stringify({ eventId: crypto.randomUUID(), activityId, eventType, durationMs }),
  }).catch(() => {});
}

export function useActivityBehavior(activityId?: string) {
  useEffect(() => {
    if (!activityId) return;
    const viewTimer = window.setTimeout(() => trackActivity(activityId, 'view'), 0);
    let started = document.visibilityState === 'visible' ? performance.now() : null;

    function flush() {
      if (started === null || !activityId) return;
      const duration = Math.min(300_000, Math.round(performance.now() - started));
      started = null;
      if (duration >= 1_000) trackActivity(activityId, 'dwell', duration);
    }

    function visibilityChanged() {
      if (document.visibilityState === 'hidden') flush();
      else started ??= performance.now();
    }

    document.addEventListener('visibilitychange', visibilityChanged);
    window.addEventListener('pagehide', flush);
    return () => {
      window.clearTimeout(viewTimer);
      flush();
      document.removeEventListener('visibilitychange', visibilityChanged);
      window.removeEventListener('pagehide', flush);
    };
  }, [activityId]);
}
