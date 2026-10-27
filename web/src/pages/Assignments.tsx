import Notifications from '../components/Notifications';
import ExportActions from '../components/ExportActions';
/**
 * 作业与待办页 /assignments
 * 管理者：查看完成进度、提醒未完成人员、查看提交详情
 * 参与者：查看作业、提交完成说明和文件
 */
import { useRef, useState, type FormEvent } from 'react';
import { api, useApi } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useToast } from '../components/Toast';
import DataState from '../components/DataState';
import ParticipantTasks from '../components/ParticipantTasks';
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

  const { data, loading, error, reload } = useApi<DashboardData>(() =>
    api<DashboardData>('/portal/dashboard'),
  );

  const [filter, setFilter] = useState<Filter>('all');
  const [detail, setDetail] = useState<TodoDetail | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const detailSequence = useRef(0);
  const draftSubmissions = useRef<Record<string, { note: string; file: File | null }>>({});
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [selectedTodoId, setSelectedTodoId] = useState<string | null>(null);
  const [reminding, setReminding] = useState(false);

  // 提交表单状态
  const [note, setNote] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const submissionFileRef = useRef<HTMLInputElement>(null);
  const [submitting, setSubmitting] = useState(false);

  const todos = data?.todos ?? [];
  const activityTasks = data?.activity_tasks ?? [];

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
    if (submitting) return;
    const sequence = ++detailSequence.current;
    setSelectedTodoId(id);
    setDetail(null);
    setDetailError('');
    setDetailLoading(true);
    if (!dialogRef.current?.open) dialogRef.current?.showModal();
    try {
      const d = await api<TodoDetail>('/todos/' + encodeURIComponent(id));
      if (sequence !== detailSequence.current) return;
      setDetail(d);
      const draft = draftSubmissions.current[id];
      setNote(draft?.note ?? d.assignment?.note ?? '');
      setFile(draft?.file ?? null);
    } catch (e) {
      if (sequence === detailSequence.current) {
        setDetailError(e instanceof Error ? e.message : '加载详情失败');
      }
    } finally {
      if (sequence === detailSequence.current) setDetailLoading(false);
    }
  }

  function closeDialog() {
    if (!submitting) dialogRef.current?.close();
  }

  function finishClose() {
    detailSequence.current += 1;
    setDetail(null);
    setSelectedTodoId(null);
    setDetailError('');
    setDetailLoading(false);
  }

  function changeNote(value: string) {
    setNote(value);
    if (selectedTodoId) draftSubmissions.current[selectedTodoId] = { note: value, file };
  }

  function changeFile(value: File | null) {
    setFile(value);
    if (!value && submissionFileRef.current) submissionFileRef.current.value = '';
    if (selectedTodoId) draftSubmissions.current[selectedTodoId] = { note, file: value };
  }

  async function handleRemind(id: string) {
    if (reminding) return;
    setReminding(true);
    try {
      const r = await api<RemindResult>(`/todos/${id}/remind`, { method: 'POST' });
      showToast(
        r.missing?.length ? `已提醒 ${r.missing.length} 人` : (r.message ?? '已提醒'),
      );
    } catch (e) {
      showToast(e instanceof Error ? e.message : '提醒失败', 'error');
    } finally {
      setReminding(false);
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!detail || submitting) return;
    setSubmitting(true);
    try {
      const formData = new FormData();
      formData.append('note', note);
      if (file) formData.append('file', file);
      await api(`/todos/${detail.todo.id}/submit`, { method: 'POST', body: formData });
      delete draftSubmissions.current[detail.todo.id];
      dialogRef.current?.close();
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
      <Notifications />
      <header className="ws-header">
        <div>
          <p className="ws-kicker">ASSIGNMENTS</p>
          <h1>{isManager ? '作业与待办' : '我的作业与活动任务'}</h1>
          <p>发布统一走 AI 助手，这里查看、提交与催办。</p>
        </div>
      </header>

      <DataState loading={loading} error={error} onRetry={reload} loadingText="正在加载作业和活动任务…" />
      {!loading && !error && data && !isManager && <section className="ws-panel">
        <div className="ws-panel-title"><div><h2>我的活动任务</h2><p>更新分配给自己的任务完成情况</p></div></div>
        <ParticipantTasks tasks={activityTasks} onChanged={reload} filter={filter} />
      </section>}
      {!loading && !error && data && <section className="ws-panel" style={{ marginTop: 13 }}>
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
                disabled={detailLoading}
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
      </section>}

      <dialog
        ref={dialogRef}
        className="account-dialog"
        style={{ width: 'min(850px, 92vw)' }}
        onClose={finishClose}
        onCancel={(event) => { if (submitting) event.preventDefault(); }}
      >
        <button className="dialog-close" aria-label="关闭作业详情" disabled={submitting} onClick={closeDialog}>
          ×
        </button>
        <DataState loading={detailLoading} error={detailError} loadingText="正在读取作业详情…"
          onRetry={() => { if (selectedTodoId) void openDetail(selectedTodoId); }} />
        {detail && !detailLoading && !detailError && (
          <>
            {isManager ? (
              <>
                <p className="ws-kicker">PROGRESS</p>
                <h2>{detail.todo.title}</h2>
                {detail.todo.description && <p>{detail.todo.description}</p>}
                <div className="publish-bar">
                  <button className="ws-button" disabled={reminding} onClick={() => handleRemind(detail.todo.id)}>
                    {reminding ? '正在发送…' : '提醒未完成人员'}
                  </button>
                </div>
                <ExportActions todoId={detail.todo.id}/>
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
                      disabled={submitting}
                      onChange={(e) => changeNote(e.target.value)}
                    />
                  </label>
                  <label>
                    上传文件
                    <input
                      type="file"
                      ref={submissionFileRef}
                      disabled={submitting}
                      onChange={(e) => changeFile(e.target.files?.[0] ?? null)}
                    />
                  </label>
                  {file && <p>已选择文件：{file.name} <button type="button" className="ws-button light"
                    disabled={submitting} onClick={() => changeFile(null)}>移除文件</button></p>}
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
