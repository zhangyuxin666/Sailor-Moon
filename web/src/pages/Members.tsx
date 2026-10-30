/**
 * 成员管理页 /members（仅管理者）
 * 按班级查看成员、Excel 批量导入、重置密码
 */
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, useApi } from '../lib/api';
import { useToast } from '../components/Toast';
import DataState from '../components/DataState';
import { formatDate } from '../lib/utils';
import type { DashboardData, ImportResult, MemberListResponse } from '../types';

interface ClassQqBindings {
  groups: Array<{ group_openid: string; group_label?: string }>;
  group_openid: string | null;
  qq_group_bound: boolean;
  binding_needs_update?: boolean;
}

export default function Members() {
  const { showToast } = useToast();

  const { data: dashboard, loading: dashboardLoading, error: dashboardError, reload: reloadDashboard } = useApi<DashboardData>(() =>
    api<DashboardData>('/portal/dashboard'),
  );
  const classes = dashboard?.classes ?? [];

  const [classId, setClassId] = useState('');
  const [className, setClassName] = useState('');
  const [creatingClass, setCreatingClass] = useState(false);
  const [importing, setImporting] = useState(false);
  const [resettingAccount, setResettingAccount] = useState<string | null>(null);
  const [bindings, setBindings] = useState<ClassQqBindings | null>(null);
  const [groupId, setGroupId] = useState('');
  const [groupLoading, setGroupLoading] = useState(false);
  const [groupError, setGroupError] = useState('');
  const [groupSaving, setGroupSaving] = useState(false);
  const [bindingRevision, setBindingRevision] = useState(0);

  useEffect(() => {
    let current = true;
    setBindings(null);
    setGroupId('');
    setGroupError('');
    if (!classId) { setGroupLoading(false); return; }
    setGroupLoading(true);
    api<ClassQqBindings>(`/classes/${classId}/qq-bindings`)
      .then((result) => {
        if (!current) return;
        setBindings(result);
        setGroupId(result.group_openid ?? '');
      })
      .catch((error) => {
        if (current) setGroupError(error instanceof Error ? error.message : '群绑定信息加载失败');
      })
      .finally(() => { if (current) setGroupLoading(false); });
    return () => { current = false; };
  }, [classId, bindingRevision]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const fileFormRef = useRef<HTMLFormElement>(null);

  // 班级加载完成后自动选中第一个
  useEffect(() => {
    if (!classId && classes.length > 0) {
      setClassId(classes[0].id);
    }
  }, [classes, classId]);

  const { data: memberData, loading: membersLoading, error: membersError, reload: reloadMembers } = useApi<MemberListResponse>(
    () => (classId ? api<MemberListResponse>(`/classes/${classId}/members`) : Promise.resolve({ members: [] })),
    [classId],
  );

  const members = memberData?.members ?? [];

  async function handleCreateClass(e: FormEvent) {
    e.preventDefault();
    if (creatingClass) return;
    const name = className.trim();
    if (!name) {
      showToast('请填写班级名称', 'error');
      return;
    }
    setCreatingClass(true);
    try {
      const created = await api<{ id: string; name: string }>('/classes', {
        method: 'POST',
        body: JSON.stringify({ name }),
      });
      setClassName('');
      setClassId(created.id);
      await reloadDashboard();
      showToast(`班级“${created.name}”创建成功`);
    } catch (err) {
      showToast(err instanceof Error ? err.message : '创建班级失败', 'error');
    } finally {
      setCreatingClass(false);
    }
  }

  async function handleImport(e: FormEvent) {
    e.preventDefault();
    if (importing) return;
    if (!classId) {
      showToast('请先创建班级，再导入成员', 'error');
      return;
    }
    const file = fileInputRef.current?.files?.[0];
    if (!file) {
      showToast('请先选择填写好的 .xlsx 文件', 'error');
      return;
    }
    const body = new FormData();
    body.append('file', file);
    setImporting(true);
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
    } finally {
      setImporting(false);
    }
  }

  async function handleGroupBinding(e: FormEvent) {
    e.preventDefault();
    if (!classId || !bindings || groupLoading || groupSaving) return;
    setGroupSaving(true);
    try {
      await api(`/classes/${classId}/qq-binding`, {
        method: 'PUT',
        body: JSON.stringify({ group_openid: groupId || null }),
      });
      setBindingRevision((value) => value + 1);
      showToast(groupId ? '班级 QQ 群已更新' : '班级 QQ 群已解绑');
    } catch (error) {
      showToast(error instanceof Error ? error.message : '保存群绑定失败', 'error');
    } finally {
      setGroupSaving(false);
    }
  }

  async function handleResetPassword(accountId: string) {
    if (resettingAccount) return;
    const password = prompt('输入至少 8 位的新临时密码');
    if (!password) return;
    if (password.length < 8) {
      showToast('密码至少 8 位', 'error');
      return;
    }
    setResettingAccount(accountId);
    try {
      await api(`/accounts/${accountId}/reset-password`, {
        method: 'POST',
        body: JSON.stringify({ new_password: password }),
      });
      showToast('密码已重置，成员下次登录必须修改');
    } catch (err) {
      showToast(err instanceof Error ? err.message : '重置失败', 'error');
    } finally {
      setResettingAccount(null);
    }
  }

  if (dashboardLoading || dashboardError || !dashboard) return (
    <>
      <header className="ws-header"><div><p className="ws-kicker">MEMBERS</p><h1>成员管理</h1></div></header>
      <DataState loading={dashboardLoading} error={dashboardError || (!dashboardLoading && !dashboard ? '班级信息未能加载' : null)}
        onRetry={reloadDashboard} loadingText="正在读取班级…" />
    </>
  );

  return (
    <>
      <header className="ws-header">
        <div>
          <p className="ws-kicker">MEMBERS</p>
          <h1>成员管理</h1>
          <p>按班级查看和导入成员账号。</p>
        </div>
        {classes.length > 0 ? (
          <select
            className="ws-select"
            value={classId}
            disabled={groupSaving || importing || resettingAccount !== null}
            onChange={(e) => setClassId(e.target.value)}
          >
            {classes.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        ) : null}
      </header>

      <div className="ws-grid">
        {classId && (
          <section className="ws-panel">
            <div className="ws-panel-title">
              <div>
                <h2>班级 QQ 群</h2>
                <p>从本组织已授权的 QQ 群中明确选择通知渠道。</p>
              </div>
              <button className="ws-button light" type="button"
                disabled={groupLoading || groupSaving}
                onClick={() => setBindingRevision((value) => value + 1)}>刷新群列表</button>
            </div>
            {groupLoading && <p role="status">正在读取群绑定…</p>}
            {groupError && <p role="alert">{groupError}，请点击刷新重试。</p>}
            {bindings && !groupLoading && (
              <form className="ws-form" onSubmit={handleGroupBinding}>
                {bindings.binding_needs_update && (
                  <p role="alert">原群绑定已失效，请重新选择授权群后保存。</p>
                )}
                <label>
                  通知群
                  <select className="ws-select" value={groupId}
                    onChange={(e) => setGroupId(e.target.value)} disabled={groupSaving}>
                    <option value="">不绑定 QQ 群</option>
                    {bindings.groups.map((group) => (
                      <option key={group.group_openid} value={group.group_openid}>
                        {group.group_label || group.group_openid}
                      </option>
                    ))}
                  </select>
                </label>
                <p>新班级默认不绑定群。请先到<a href="/settings">设置</a>获取绑定码，
                  在 QQ 群完成机器人绑定后，返回这里刷新并选择。</p>
                <button className="ws-button" type="submit" disabled={groupSaving}>
                  {groupSaving ? '正在保存…' : '保存群绑定'}
                </button>
              </form>
            )}
          </section>
        )}
        {classes.length === 0 && (
          <section className="ws-panel">
            <div className="ws-panel-title">
              <div>
                <h2>先创建班级</h2>
                <p>成员必须导入到一个具体班级中</p>
              </div>
            </div>
            <form className="ws-form" onSubmit={handleCreateClass}>
              <label>
                班级名称
                <input
                  value={className}
                  disabled={creatingClass}
                  onChange={(e) => setClassName(e.target.value)}
                  placeholder="例如：软件工程 1 班"
                  maxLength={80}
                  required
                />
              </label>
              <button className="ws-button" type="submit" disabled={creatingClass}>
                {creatingClass ? '正在创建…' : '创建班级'}
              </button>
            </form>
          </section>
        )}

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
              <input ref={fileInputRef} type="file" accept=".xlsx" required disabled={importing || !classId} />
            </label>
            {classes.length === 0 && <p className="ws-empty">创建班级后即可导入成员。</p>}
            <button className="ws-button" type="submit" disabled={importing || !classId}>
              {importing ? '正在导入…' : '导入成员'}
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
            <strong>{membersLoading || membersError ? '—' : members.length}</strong>
            <em>{membersLoading ? '正在读取成员' : membersError ? '成员信息暂不可用' : '当前班级成员'}</em>
          </div>
        </section>
      </div>

      <section className="ws-panel" style={{ marginTop: 13 }}>
        <div className="ws-panel-title">
          <div>
            <h2>成员列表</h2>
            <p>登录名可直接查看；初始密码请查看导入时使用的 Excel 文件</p>
          </div>
        </div>
        <div className="member-password-notice">
          为保护账号安全，平台只保存密码哈希，管理员和系统都无法查看原密码。
          学生忘记密码时，请点击右侧按钮设置新的临时密码，并将新密码单独告知本人。
        </div>
        <DataState loading={membersLoading} error={membersError} onRetry={reloadMembers}
          loadingText="正在读取成员…" empty={!classId} emptyText="请选择班级" />
        {!membersLoading && !membersError && classId && <div style={{ overflow: 'auto' }}>
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
                      disabled={resettingAccount !== null}
                      onClick={() => handleResetPassword(m.id)}
                    >
                      {resettingAccount === m.id ? '正在重置…' : '设置新临时密码'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>}
      </section>
    </>
  );
}
