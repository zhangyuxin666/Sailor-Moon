/**
 * AI 助手页 /agent（仅管理者）
 * - 描述需求 → POST /agent/drafts 生成可编辑草案
 * - 编辑草案（意图/复杂度/标题/说明/时间/流程勾选）
 * - 保存草案 PUT /agent/drafts/{id}
 * - 发布草案 POST /agent/drafts/{id}/publish
 */
import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { useToast } from '../components/Toast';
import { isoToLocal, localToISO } from '../lib/utils';
import type {
  DashboardData,
  DraftDetail,
  DraftItem,
  DraftListResponse,
  DraftWorkflow,
  PublishResult,
} from '../types';

/** workflow 各步骤的中文标签 */
const WORKFLOW_LABELS: Record<string, string> = {
  create_plan: '生成方案',
  create_form: '创建表单',
  assign_tasks: '任务分工',
  create_calendar: '写入日历',
  schedule_reminder: '定时提醒',
  publish_message: '发布群消息',
  collect_submission: '收集文件/完成情况',
};

/** 意图类型中文标签 */
const INTENT_LABELS: Record<DraftDetail['intent_type'], string> = {
  assignment: '收作业',
  activity: '活动',
  survey: '信息收集',
  notice: '通知',
};

/** 复杂度中文标签 */
const COMPLEXITY_LABELS: Record<DraftDetail['complexity'], string> = {
  simple: '简单任务',
  standard: '标准流程',
  complex: '复杂协作',
};

/** 示例 chip：点击填入 textarea */
const EXAMPLES: { label: string; text: string }[] = [
  {
    label: '收作业',
    text: '下周五前收齐全班的软件测试报告，提交 PDF，截止前一天提醒未交同学',
  },
  {
    label: '组织活动',
    text: '下月举办班级秋游，需要报名、分工、物料、日历和活动前提醒',
  },
  {
    label: '收集信息',
    text: '统计大家下周空闲时间，并把问卷发到群里',
  },
  {
    label: '发通知',
    text: '通知大家明天下午三点到教学楼开班会',
  },
];

export default function Agent() {
  const navigate = useNavigate();
  const { showToast } = useToast();

  // 班级下拉数据
  const { data: dashboard } = useApi<DashboardData>(
    () => api<DashboardData>('/portal/dashboard'),
    [],
  );
  const classes = dashboard?.classes ?? [];

  // 草案历史
  const { data: historyData, reload: reloadHistory } = useApi<DraftListResponse>(
    () => api<DraftListResponse>('/agent/drafts'),
    [],
  );
  const history = historyData?.drafts ?? [];

  // 需求输入
  const [text, setText] = useState('');
  const [classId, setClassId] = useState('');
  const [analyzing, setAnalyzing] = useState(false);

  // 当前正在编辑的草案
  const [current, setCurrent] = useState<DraftItem | null>(null);
  const [intentType, setIntentType] = useState<DraftDetail['intent_type']>('assignment');
  const [complexity, setComplexity] = useState<DraftDetail['complexity']>('simple');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [deadline, setDeadline] = useState('');
  const [eventTime, setEventTime] = useState('');
  const [workflow, setWorkflow] = useState<DraftWorkflow>({});

  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);

  // 班级加载完成后默认选中第一个
  useEffect(() => {
    if (!classId && classes.length > 0) {
      setClassId(classes[0].id);
    }
  }, [classes, classId]);

  /** 载入一个草案到编辑区 */
  const loadDraft = (item: DraftItem) => {
    setCurrent(item);
    const d = item.draft;
    setIntentType(d.intent_type);
    setComplexity(d.complexity);
    setTitle(d.title);
    setDescription(d.description || d.summary || '');
    setDeadline(isoToLocal(d.deadline));
    setEventTime(isoToLocal(d.event_time));
    setWorkflow({ ...d.workflow });
  };

  /** 从表单收集当前草案详情 */
  const collectDraft = (): DraftDetail => {
    if (!current) throw new Error('没有正在编辑的草案');
    return {
      ...current.draft,
      intent_type: intentType,
      complexity,
      title: title.trim(),
      description: description.trim(),
      deadline: deadline ? localToISO(deadline) : '',
      event_time: eventTime ? localToISO(eventTime) : '',
      workflow: { ...workflow },
    };
  };

  /** 分析需求 → 生成新草案 */
  const handleAnalyze = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = text.trim();
    if (!trimmed || !classId) {
      showToast('请描述需求并选择班级', 'error');
      return;
    }
    setAnalyzing(true);
    try {
      const draft = await api<DraftItem>('/agent/drafts', {
        method: 'POST',
        body: JSON.stringify({ class_id: classId, text: trimmed }),
      });
      loadDraft(draft);
      await reloadHistory();
      showToast('分析完成，请确认草案');
    } catch (err) {
      showToast(err instanceof Error ? err.message : '分析失败', 'error');
    } finally {
      setAnalyzing(false);
    }
  };

  /** 保存当前草案（供保存按钮和发布流程复用） */
  const persistDraft = async (): Promise<DraftItem | null> => {
    if (!current) return null;
    const updated = await api<DraftItem>(`/agent/drafts/${current.id}`, {
      method: 'PUT',
      body: JSON.stringify({ draft: collectDraft() }),
    });
    loadDraft(updated);
    await reloadHistory();
    return updated;
  };

  const handleSave = async () => {
    if (!current) return;
    setSaving(true);
    try {
      await persistDraft();
      showToast('草案已保存');
    } catch (err) {
      showToast(err instanceof Error ? err.message : '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handlePublish = async () => {
    if (!current) return;
    setPublishing(true);
    try {
      await persistDraft();
      const result = await api<PublishResult>(`/agent/drafts/${current.id}/publish`, {
        method: 'POST',
      });
      showToast('发布成功');
      setTimeout(() => {
        const target =
          result.result_type === 'todo'
            ? '/assignments'
            : result.result_type === 'activity'
              ? result.result_id
                ? `/activity/${result.result_id}`
                : '/activity'
              : '/portal';
        navigate(target);
      }, 700);
    } catch (err) {
      showToast(err instanceof Error ? err.message : '发布失败', 'error');
      setPublishing(false);
    }
  };

  /** 从历史列表载入草案 */
  const handleHistoryClick = async (id: string) => {
    try {
      const item = await api<DraftItem>(`/agent/drafts/${id}`);
      loadDraft(item);
    } catch (err) {
      showToast(err instanceof Error ? err.message : '加载草案失败', 'error');
    }
  };

  const toggleWorkflow = (key: string) => {
    setWorkflow((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  /** 发起新活动：清空当前草案，回到输入区 */
  const handleNew = () => {
    setCurrent(null);
    setText('');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  return (
    <>
      <header className="ws-header">
        <div>
          <p className="ws-kicker">AI ASSISTANT</p>
          <h1>AI 助手</h1>
          <p>用一句话描述需求，Agent 生成可编辑的草案，确认后一键发布。</p>
        </div>
        <div className="ws-actions">
          <button className="ws-button" type="button" onClick={handleNew}>
            ✦ 发起新活动
          </button>
        </div>
      </header>

      <section className="agent-hero">
        <h2>让 Agent 帮你起草</h2>
        <p>不用考虑功能入口，直接描述目标、对象和时间。</p>
        <form className="agent-compose" onSubmit={handleAnalyze}>
          <textarea
            placeholder="例如：周五前收齐全班的数据库实验报告，PDF 格式，没交的当天晚上提醒"
            value={text}
            onChange={(e) => setText(e.target.value)}
          />
          <div className="agent-compose-footer">
            <select
              className="ws-select"
              value={classId}
              onChange={(e) => setClassId(e.target.value)}
            >
              {classes.length === 0 && <option value="">选择班级</option>}
              {classes.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
            <button className="ws-button" type="submit" disabled={analyzing}>
              {analyzing ? 'Agent 正在分析…' : '分析需求 →'}
            </button>
          </div>
        </form>
        <div className="example-chips">
          {EXAMPLES.map((ex) => (
            <button key={ex.label} type="button" onClick={() => setText(ex.text)}>
              {ex.label}
            </button>
          ))}
        </div>
      </section>

      {current ? (
        <section className="draft-layout">
          {/* 左侧：草案编辑表单 */}
          <article className="ws-panel">
            <div className="ws-panel-title">
              <div>
                <h2>Agent 方案草案</h2>
                <p>修改内容与流程后再确认发布</p>
              </div>
              <div className="draft-badges">
                <span className="draft-badge">{INTENT_LABELS[intentType]}</span>
                <span
                  className={`draft-badge${complexity === 'complex' ? ' complex' : ''}`}
                >
                  {COMPLEXITY_LABELS[complexity]}
                </span>
              </div>
            </div>
            <form className="ws-form" onSubmit={(e) => e.preventDefault()}>
              <div className="ws-form-row">
                <label>
                  任务类型
                  <select
                    value={intentType}
                    onChange={(e) =>
                      setIntentType(e.target.value as DraftDetail['intent_type'])
                    }
                  >
                    <option value="assignment">收作业</option>
                    <option value="activity">组织活动</option>
                    <option value="survey">收集信息</option>
                    <option value="notice">发布通知</option>
                  </select>
                </label>
                <label>
                  复杂度
                  <select
                    value={complexity}
                    onChange={(e) =>
                      setComplexity(e.target.value as DraftDetail['complexity'])
                    }
                  >
                    <option value="simple">简单</option>
                    <option value="standard">标准</option>
                    <option value="complex">复杂</option>
                  </select>
                </label>
              </div>

              <label>
                标题
                <input value={title} onChange={(e) => setTitle(e.target.value)} />
              </label>

              <label>
                说明
                <textarea
                  rows={5}
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                />
              </label>

              <div className="ws-form-row">
                <label>
                  截止时间
                  <input
                    type="datetime-local"
                    value={deadline}
                    onChange={(e) => setDeadline(e.target.value)}
                  />
                </label>
                <label>
                  活动时间
                  <input
                    type="datetime-local"
                    value={eventTime}
                    onChange={(e) => setEventTime(e.target.value)}
                  />
                </label>
              </div>

              {current.draft.missing_information &&
              current.draft.missing_information.length > 0 ? (
                <div className="missing-list">
                  {current.draft.missing_information.map((m, i) => (
                    <span key={i}>{m}</span>
                  ))}
                </div>
              ) : null}

              {current.draft.reasoning ? (
                <div className="reason-box">Agent 判断：{current.draft.reasoning}</div>
              ) : null}

              <div className="workflow-list">
                {Object.entries(WORKFLOW_LABELS).map(([key, label]) => (
                  <label key={key} className="workflow-item">
                    <span>{label}</span>
                    <input
                      type="checkbox"
                      checked={!!workflow[key]}
                      onChange={() => toggleWorkflow(key)}
                    />
                  </label>
                ))}
              </div>

              <div className="publish-bar">
                <button
                  type="button"
                  className="ws-button light"
                  onClick={handleSave}
                  disabled={saving || publishing}
                >
                  {saving ? '保存中…' : '保存草案'}
                </button>
                <button
                  type="button"
                  className="ws-button"
                  onClick={handlePublish}
                  disabled={saving || publishing}
                >
                  {publishing ? '发布中…' : '发布'}
                </button>
              </div>
            </form>
          </article>

          {/* 右侧：草案历史列表 */}
          <aside className="ws-panel">
            <div className="ws-panel-title">
              <div>
                <h2>草案历史</h2>
                <p>点击加载已保存的草案继续编辑</p>
              </div>
            </div>
            <div className="ws-list">
              {history.length === 0 ? (
                <div className="ws-empty">还没有草案</div>
              ) : (
                history.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    className="ws-list-item"
                    onClick={() => handleHistoryClick(item.id)}
                  >
                    <span
                      className={`ws-tag${item.status === 'published' ? ' green' : ''}`}
                    >
                      {item.status === 'published' ? '已发布' : '待确认'}
                    </span>
                    <span>
                      <strong>{item.title}</strong>
                      <small>
                        {item.class_name} · {item.summary}
                      </small>
                    </span>
                    <b>›</b>
                  </button>
                ))
              )}
            </div>
          </aside>
        </section>
      ) : null}
    </>
  );
}
