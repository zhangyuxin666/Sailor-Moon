import { useState } from 'react';
import { api } from '../lib/api';
import { formatDate } from '../lib/utils';
import type { ActivityTask } from '../types';

interface ParticipantTasksProps {
  tasks: ActivityTask[];
  onChanged: () => void;
  filter?: 'all' | 'pending' | 'done';
  limit?: number;
}

/** Own-task controls never expose the manager-only activity detail route. */
export default function ParticipantTasks({ tasks, onChanged, filter = 'all', limit }: ParticipantTasksProps) {
  const [saving, setSaving] = useState<string | null>(null);
  const [error, setError] = useState<{ id: string; message: string } | null>(null);
  const visible = tasks.filter((task) => filter === 'all' || task.status === filter);
  const shown = limit ? visible.slice(0, limit) : visible;

  async function toggle(task: ActivityTask) {
    if (saving) return;
    setSaving(task.id);
    setError(null);
    try {
      await api('/tasks/' + encodeURIComponent(task.id), {
        method: 'PATCH',
        body: JSON.stringify({ status: task.status === 'done' ? 'pending' : 'done' }),
      });
      onChanged();
    } catch (cause) {
      setError({ id: task.id, message: cause instanceof Error ? cause.message : '更新失败，请重试' });
    } finally {
      setSaving(null);
    }
  }

  return (
    <div className="ws-list">
      {shown.length === 0 && <div className="ws-empty">暂无符合条件的活动任务</div>}
      {shown.map((task) => (
        <div className="ws-list-item" key={task.id}>
          <span className={'ws-tag ' + (task.status === 'done' ? 'green' : 'amber')}>
            {task.status === 'done' ? '已完成' : '待完成'}
          </span>
          <span style={{ flex: 1 }}>
            <strong>{task.title}</strong>
            <small>{task.activity_title || '活动任务'}{task.activity_status === 'cancelled' ? ' · 活动已取消' : ''}</small>
            {task.description && <small>{task.description}</small>}
            <small>优先级：{({low:'低',normal:'普通',high:'高',urgent:'紧急'} as Record<string,string>)[task.priority??'normal']} · {task.deadline ? formatDate(task.deadline) : '无截止时间'}</small>
            {error?.id === task.id && <small role="alert">{error.message}</small>}
          </span>
          <button type="button" className="ws-button light" disabled={saving !== null || task.activity_status === 'cancelled'}
            aria-label={(task.status === 'done' ? '标记未完成：' : '标记完成：') + task.title}
            onClick={() => toggle(task)}>
            {saving === task.id ? '正在保存…' : task.status === 'done' ? '标记未完成' : '标记完成'}
          </button>
        </div>
      ))}
    </div>
  );
}
