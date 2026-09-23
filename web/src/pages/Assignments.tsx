/**
 * 作业与待办页 /assignments
 * 管理者：查看完成进度、提醒未完成人员、查看提交详情
 * 参与者：查看作业、提交完成说明和文件
 */
import { useRef, useState, type FormEvent } from 'react';
import { api, useApi } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useToast } from '../components/Toast';
import { formatDate } from '../lib/utils';
import type { DashboardData, TodoDetail } from '../types';

type Filter = 'all' | 'done' | 'pending';

interface RemindResult {
  message?: string;
  missing?: unknown[];
}

export default function Assignments() {
  const { account } = useAuth();
  const { showToast } = useToast();
  const isManager = account?.role === 'manager';

  const { data, reload } = useApi<DashboardData>(() =>
    api<DashboardData>('/portal/dashboard'),
  );

  const [filter, setFilter] = useState<Filter>('all');
  const [detail, setDetail] = useState<TodoDetail | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);

  // 提交表单状态
  const [note, setNote] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const todos = data?.todos ?? [];

  const filtered = todos.filter((t) => {
    if (filter === 'all') return true;
    if (isManager) {
      if (filter === 'done') return t.done_count === t.total_count;
      return (t.done_count ?? 0) < (t.total_count ?? 0);
    }
    if (filter === 'done') return t.my_status === 'done';
    return t.my_status === 'pending';
  });

  async function openDetail(id: string) {
    try {
      const d = await api<TodoDetail>(`/todos/${id}`);
      setDetail(d);
      setNote(d.assignment?.note ?? '');
      setFile(null);
      dialogRef.current?.showModal();
    } catch (e) {
      showToast(e instanceof Error ? e.message : '加载详情失败', 'error');
    }
  }

  function closeDialog() {
    dialogRef.current?.close();
    setDetail(null);
  }

  async function handleRemind(id: string) {
    try {
      const r = await api<RemindResult>(`/todos/${id}/remind`, { method: 'POST' });
      showToast(
        r.missing?.length ? `已提醒 ${r.missing.length} 人` : (r.message ?? '已提醒'),
      );
    } catch (e) {
      showToast(e instanceof Error ? e.message : '提醒失败', 'error');
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!detail) return;
    setSubmitting(true);
    try {
      const formData = new FormData();
      formData.append('note', note);
      if (file) formData.append('file', file);
      await api(`/todos/${detail.todo.id}/submit`, { method: 'POST', body: formData });
      closeDialog();
      reload();
      showToast('提交成功');
    } catch (err) {
      showToast(err instanceof Error ? err.message : '提交失败', 'error');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <>
      <header className="ws-header">
        <div>
          <p className="ws-kicker">ASSIGNMENTS</p>
          <h1>{isManager ? '作业与待办' : '我的作业与待办'}</h1>
          <p>发布统一走 AI 助手，这里查看、提交与催办。</p>
        </div>
      </header>

      <section className="ws-panel">
        <div className="ws-panel-title">
          <div>
            <h2>全部作业</h2>
            <p>点击项目查看每个人的完成情况</p>
          </div>
          <select
            className="ws-select"
            value={filter}
            onChange={(e) => setFilter(e.target.value as Filter)}
          >
            <option value="all">全部</option>
            <option value="pending">未完成</option>
            <option value="done">已完成</option>
          </select>
        </div>
        <div className="ws-list">
          {filtered.length === 0 && <div className="ws-empty">暂无符合条件的项目</div>}
          {filtered.map((t) => {
            const done = isManager
              ? (t.total_count ?? 0) > 0 && t.done_count === t.total_count
              : t.my_status === 'done';
            return (
              <button
                key={t.id}
                className="ws-list-item"
                onClick={() => openDetail(t.id)}
              >
                <span className={`ws-tag ${done ? 'green' : 'amber'}`}>
                  {isManager
                    ? `${t.done_count ?? 0}/${t.total_count ?? 0}`
                    : done
                      ? '已提交'
                      : '待完成'}
                </span>
                <span>
                  <strong>{t.title}</strong>
                  <small>
                    {t.class_name} · {formatDate(t.deadline)} 截止
                  </small>
                </span>
                <b>›</b>
              </button>
            );
          })}
        </div>
      </section>

      <dialog
        ref={dialogRef}
        className="account-dialog"
        style={{ width: 'min(850px, 92vw)' }}
        onClose={() => setDetail(null)}
      >
        <button className="dialog-close" onClick={closeDialog}>
          ×
        </button>
        {detail && (
          <>
            {isManager ? (
              <>
                <p className="ws-kicker">PROGRESS</p>
                <h2>{detail.todo.title}</h2>
                {detail.todo.description && <p>{detail.todo.description}</p>}
                <div className="publish-bar">
                  <button className="ws-button" onClick={() => handleRemind(detail.todo.id)}>
                    提醒未完成人员
                  </button>
                </div>
                <div style={{ overflow: 'auto' }}>
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>学号</th>
                        <th>姓名</th>
                        <th>状态</th>
                        <th>提交时间</th>
                        <th>文件</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.participants.map((p) => (
                        <tr key={p.account_id}>
                          <td>{p.student_no || '-'}</td>
                          <td>{p.display_name}</td>
                          <td>
                            <span className={`ws-tag ${p.status === 'done' ? 'green' : 'amber'}`}>
                              {p.status === 'done' ? '完成' : '未完成'}
                            </span>
                          </td>
                          <td>{p.submitted_at ? formatDate(p.submitted_at) : '-'}</td>
                          <td>
                            {p.original_filename ? (
                              <a href={`/todos/${detail.todo.id}/submissions/${p.account_id}/file`}>
                                下载
                              </a>
                            ) : (
                              '-'
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            ) : (
              <>
                <p className="ws-kicker">SUBMIT</p>
                <h2>{detail.todo.title}</h2>
                {detail.todo.description && <p>{detail.todo.description}</p>}
                <form className="ws-form" onSubmit={handleSubmit}>
                  <label>
                    完成说明
                    <textarea
                      rows={4}
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                    />
                  </label>
                  <label>
                    上传文件
                    <input
                      type="file"
                      onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                    />
                  </label>
                  <button className="ws-button" type="submit" disabled={submitting}>
                    {submitting ? '提交中…' : '确认提交'}
                  </button>
                </form>
              </>
            )}
          </>
        )}
      </dialog>
    </>
  );
}
