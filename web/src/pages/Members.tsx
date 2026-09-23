/**
 * 成员管理页 /members（仅管理者）
 * 按班级查看成员、Excel 批量导入、重置密码
 */
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, useApi } from '../lib/api';
import { useToast } from '../components/Toast';
import { formatDate } from '../lib/utils';
import type { DashboardData, ImportResult, MemberListResponse } from '../types';

export default function Members() {
  const { showToast } = useToast();

  const { data: dashboard } = useApi<DashboardData>(() =>
    api<DashboardData>('/portal/dashboard'),
  );
  const classes = dashboard?.classes ?? [];

  const [classId, setClassId] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const fileFormRef = useRef<HTMLFormElement>(null);

  // 班级加载完成后自动选中第一个
  useEffect(() => {
    if (!classId && classes.length > 0) {
      setClassId(classes[0].id);
    }
  }, [classes, classId]);

  const { data: memberData, reload: reloadMembers } = useApi<MemberListResponse>(
    () => (classId ? api<MemberListResponse>(`/classes/${classId}/members`) : Promise.resolve({ members: [] })),
    [classId],
  );

  const members = memberData?.members ?? [];

  async function handleImport(e: FormEvent) {
    e.preventDefault();
    if (!classId) return;
    const file = fileInputRef.current?.files?.[0];
    if (!file) return;
    const body = new FormData();
    body.append('file', file);
    try {
      const result = await api<ImportResult>(`/classes/${classId}/members/import-excel`, {
        method: 'POST',
        body,
      });
      fileFormRef.current?.reset();
      reloadMembers();
      showToast(`成功导入 ${result.count} 名成员`);
    } catch (err) {
      showToast(err instanceof Error ? err.message : '导入失败', 'error');
    }
  }

  async function handleResetPassword(accountId: string) {
    const password = prompt('输入至少 8 位的新临时密码');
    if (!password) return;
    if (password.length < 8) {
      showToast('密码至少 8 位', 'error');
      return;
    }
    try {
      await api(`/accounts/${accountId}/reset-password`, {
        method: 'POST',
        body: JSON.stringify({ new_password: password }),
      });
      showToast('密码已重置，成员下次登录必须修改');
    } catch (err) {
      showToast(err instanceof Error ? err.message : '重置失败', 'error');
    }
  }

  return (
    <>
      <header className="ws-header">
        <div>
          <p className="ws-kicker">MEMBERS</p>
          <h1>成员管理</h1>
          <p>按班级查看和导入成员账号。</p>
        </div>
        <select
          className="ws-select"
          value={classId}
          onChange={(e) => setClassId(e.target.value)}
        >
          {classes.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </header>

      <div className="ws-grid">
        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>Excel 导入</h2>
              <p>按模板一次创建成员账号</p>
            </div>
            <a href="/classes/member-template.xlsx" className="ws-button light">
              下载模板
            </a>
          </div>
          <form className="ws-form" ref={fileFormRef} onSubmit={handleImport}>
            <label>
              选择填写好的 .xlsx 文件
              <input ref={fileInputRef} type="file" accept=".xlsx" required />
            </label>
            <button className="ws-button" type="submit">
              导入成员
            </button>
          </form>
        </section>

        <section className="ws-panel">
          <div className="ws-panel-title">
            <div>
              <h2>班级概况</h2>
              <p>导入后成员可直接登录</p>
            </div>
          </div>
          <div className="ws-metric">
            <span>当前成员</span>
            <strong>{members.length}</strong>
            <em>账号状态正常</em>
          </div>
        </section>
      </div>

      <section className="ws-panel" style={{ marginTop: 13 }}>
        <div className="ws-panel-title">
          <div>
            <h2>成员列表</h2>
            <p>初始密码不会在平台保存明文</p>
          </div>
        </div>
        <div style={{ overflow: 'auto' }}>
          <table className="data-table">
            <thead>
              <tr>
                <th>学号</th>
                <th>姓名</th>
                <th>登录名</th>
                <th>状态</th>
                <th>加入时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {members.length === 0 && (
                <tr>
                  <td colSpan={6} className="ws-empty">
                    尚未导入成员
                  </td>
                </tr>
              )}
              {members.map((m) => (
                <tr key={m.id}>
                  <td>{m.student_no || '-'}</td>
                  <td>{m.display_name}</td>
                  <td>{m.username}</td>
                  <td>
                    <span className="ws-tag green">{m.status}</span>
                  </td>
                  <td>{formatDate(m.created_at)}</td>
                  <td>
                    <button
                      className="ws-button light"
                      onClick={() => handleResetPassword(m.id)}
                    >
                      重置密码
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
