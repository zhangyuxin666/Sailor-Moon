import { useState, useEffect, useRef, useCallback, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { useToast } from '../components/Toast';
import DataState from '../components/DataState';
import GenerationInfo,{type Generation} from '../components/GenerationInfo';
import { isoToLocal, localToISO } from '../lib/utils';
import { registrationQuestions, validateFormFields, visibleOperationalError } from '../lib/activity-view';
import type { DashboardData, DraftDetail, DraftItem, DraftListResponse, DraftWorkflow, PublicFormField, PublishResult } from '../types';

const INTENT_LABELS: Record<DraftDetail['intent_type'], string> = {
  assignment: '收作业', activity: '组织活动', survey: '收集信息', notice: '发 QQ 通知',
};
const EXAMPLES = [
  { label: '收作业', text: '本周五前收齐全班的软件测试实验报告，提交 PDF' },
  { label: '组织活动', text: '下周五下午两点在教学楼 A302 开班会，提前半小时站内提醒，不用分工' },
  { label: '收集信息', text: '用问卷统计大家下周空闲时间' },
  { label: '发 QQ 通知', text: '发到 QQ 群通知大家明天下午两点到教学楼集合' },
];
const FIELDSET_STYLE = { border: 0, margin: 0, padding: 0, minWidth: 0, display: 'grid', gap: 15 };

function resultTarget(result: { result_type?: string; result_id?: string }) {
  return result.result_type === 'todo' ? '/assignments'
    : result.result_type === 'activity' ? (result.result_id ? `/activity/${result.result_id}` : '/activity')
      : '/portal';
}

export default function Agent() {
  const navigate = useNavigate();
  const [searchParams]=useSearchParams();
  const templateDraft=searchParams.get('draft');
  const { showToast } = useToast();
  const { data: dashboard, loading: dashboardLoading, error: dashboardError, reload: reloadDashboard } =
    useApi<DashboardData>(() => api<DashboardData>('/portal/dashboard'), []);
  const classes = dashboard?.classes ?? [];
  const [history, setHistory] = useState<DraftItem[]>([]);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [historyError, setHistoryError] = useState('');
  const [text, setText] = useState('');
  const [classId, setClassId] = useState('');
  const [analyzing, setAnalyzing] = useState(false);
  const [openingHistory, setOpeningHistory] = useState(false);
  const [current, setCurrent] = useState<DraftItem | null>(null);
  const [showComposer, setShowComposer] = useState(true);
  const [showTutorial, setShowTutorial] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [intentType, setIntentType] = useState<DraftDetail['intent_type']>('assignment');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [deadline, setDeadline] = useState('');
  const [eventTime, setEventTime] = useState('');
  const [workflow, setWorkflow] = useState<DraftWorkflow>({});
  const [formFields, setFormFields] = useState<PublicFormField[]>([]);
  const [draftAnswers, setDraftAnswers] = useState<Record<string, string>>({});
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const mounted = useRef(false);
  const historySequence = useRef(0);
  const draftSequence = useRef(0);
  const actionInFlight = useRef(false);
  const composerInput = useRef<HTMLTextAreaElement>(null);
  const reviewHeading = useRef<HTMLHeadingElement>(null);
  const busy = analyzing || saving || publishing || openingHistory;
  const published = current?.status === 'published';
  const isActivity = intentType === 'activity';
  const isSurvey = intentType === 'survey';
  const sendsToGroup = workflow.publish_message ?? intentType === 'notice';
  const groupNotBound = classes.find((item) => item.id === current?.class_id)?.qq_group_openid === null;
  const needsForm = isSurvey || (isActivity && !!workflow.create_form);
  const reviewing = current !== null && !showComposer;

  useEffect(() => {
    if (reviewing) reviewHeading.current?.focus();
  }, [reviewing, current?.id]);

  const reloadHistory = useCallback(async () => {
    const request = ++historySequence.current;
    setHistoryLoading(true);
    try {
      const result = await api<DraftListResponse>('/agent/drafts');
      if (mounted.current && request === historySequence.current) {
        setHistory(result.drafts); setHistoryError('');
      }
    } catch (cause) {
      if (mounted.current && request === historySequence.current) {
        setHistoryError(visibleOperationalError(cause instanceof Error ? cause.message : '', '草案历史加载失败，请重试。'));
      }
    } finally {
      if (mounted.current && request === historySequence.current) setHistoryLoading(false);
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    reloadHistory();
    return () => { mounted.current = false; historySequence.current += 1; draftSequence.current += 1; };
  }, [reloadHistory]);

  useEffect(() => {
    if (!classId && classes.length) setClassId(classes[0].id);
  }, [classes, classId]);

  useEffect(()=>{
    if(!templateDraft)return;
    const request=++draftSequence.current;setOpeningHistory(true);
    api<DraftItem>('/agent/drafts/'+encodeURIComponent(templateDraft)).then(item=>{if(mounted.current&&request===draftSequence.current)loadDraft(item);}).catch(cause=>{if(mounted.current)showToast(cause instanceof Error?cause.message:'模板草案加载失败','error');}).finally(()=>{if(mounted.current)setOpeningHistory(false);});
  },[templateDraft]);

  function loadDraft(item: DraftItem) {
    setCurrent(item);
    setShowComposer(false);
    const draft = item.draft;
    setIntentType(draft.intent_type);
    setTitle(draft.title); setDescription(draft.description || draft.summary || '');
    setDeadline(isoToLocal(draft.deadline)); setEventTime(isoToLocal(draft.event_time));
    setWorkflow({ ...draft.workflow });
    const savedInput = draft.input && typeof draft.input === 'object' ? draft.input as Record<string, unknown> : {};
    const savedAnswers = savedInput.answers;
    setDraftAnswers(savedAnswers && typeof savedAnswers === 'object' && !Array.isArray(savedAnswers)
      ? Object.fromEntries(Object.entries(savedAnswers).map(([key, value]) => [key, String(value ?? '')])) : {});
    setFormFields((draft.intent_type === 'activity' || draft.intent_type === 'survey')
      ? registrationQuestions(draft.form_fields ?? []) : (draft.form_fields ?? []).map((field) => ({ ...field, options: [...(field.options ?? [])] })));
    setDirty(false);
  }

  function collectDraft(): DraftDetail {
    if (!current) throw new Error('没有正在编辑的草案');
    if (!title.trim()) throw new Error('请填写标题');
    const fields = needsForm ? validateFormFields(registrationQuestions(formFields)) : [];
    const nextTime = isActivity && eventTime ? localToISO(eventTime) : '';
    const savedInput = current.draft.input && typeof current.draft.input === 'object' ? current.draft.input as Record<string, unknown> : {};
    return {
      ...current.draft, intent_type: intentType, title: title.trim(),
      description: description.trim(), deadline: intentType === 'assignment' && deadline ? localToISO(deadline) : '',
      event_time: nextTime, workflow: {
        ...workflow,
        create_form: needsForm,
        assign_tasks: isActivity && !!workflow.assign_tasks,
        create_calendar: isActivity && !!workflow.create_calendar,
        schedule_reminder: isActivity && !!workflow.schedule_reminder,
        publish_message: workflow.publish_message ?? intentType === 'notice',
      }, form_fields: fields,
      input: { ...savedInput, answers: Object.fromEntries(Object.entries(draftAnswers).filter(([, value]) => value.trim()).map(([key, value]) => [key, value.trim()])) },
      ...(current.draft.execution_plan ? { execution_plan: {
        ...current.draft.execution_plan, title: title.trim(), description: description.trim(),
        event_time: nextTime, form_fields: fields,
      } } : {}),
    };
  }

  function confirmReplacement() {
    return !dirty || window.confirm('当前草案有未保存修改，继续会放弃这些修改。是否继续？');
  }

  async function handleAnalyze(event: FormEvent) {
    event.preventDefault();
    if (actionInFlight.current || busy) return;
    if (!text.trim() || !classId) { showToast('请输入需求并选择班级', 'error'); return; }
    if (!confirmReplacement()) return;
    actionInFlight.current = true;
    const request = ++draftSequence.current;
    setAnalyzing(true);
    try {
      const item = await api<DraftItem>('/agent/drafts', { method: 'POST', body: JSON.stringify({ class_id: classId, text: text.trim() }) });
      if (!mounted.current || request !== draftSequence.current) return;
      loadDraft(item); showToast('分析完成，请确认草案'); await reloadHistory();
    } catch (cause) {
      if (mounted.current) showToast(visibleOperationalError(cause instanceof Error ? cause.message : '', '分析失败，输入已保留'), 'error');
    } finally {
      actionInFlight.current = false;
      if (mounted.current) setAnalyzing(false);
    }
  }

  async function persistDraft(): Promise<DraftItem> {
    if (!current || current.status === 'published') throw new Error('请选择尚未发布的草案');
    const updated = await api<DraftItem>(`/agent/drafts/${current.id}`, {
      method: 'PUT', body: JSON.stringify({ draft: collectDraft() }),
    });
    if (mounted.current) loadDraft(updated);
    await reloadHistory();
    return updated;
  }

  async function handleSave() {
    if (!current || actionInFlight.current || busy || published) return;
    actionInFlight.current = true; setSaving(true);
    try { await persistDraft(); if (mounted.current) showToast('草案已保存'); }
    catch (cause) {
      if (mounted.current) showToast(visibleOperationalError(cause instanceof Error ? cause.message : '', '保存失败，修改已保留'), 'error');
    } finally { actionInFlight.current = false; if (mounted.current) setSaving(false); }
  }

  async function handlePublish() {
    if (!current || actionInFlight.current || busy || published) return;
    if (isActivity && !eventTime) { showToast('请先填写活动开始时间', 'error'); return; }
    if (intentType === 'assignment' && !deadline) { showToast('请先填写作业截止时间', 'error'); return; }
    if (intentType === 'notice' && workflow.publish_message === false) {
      showToast('QQ 通知需要勾选发送到 QQ 群。也可以改成活动或作业，在站内发布。', 'error');
      return;
    }
    if (sendsToGroup && groupNotBound) {
      showToast('所选班级还未绑定 QQ 群，请在成员管理中绑定后再发送。', 'error');
      return;
    }
    actionInFlight.current = true; setPublishing(true);
    try {
      const saved = await persistDraft();
      const result = await api<PublishResult>(`/agent/drafts/${saved.id}/publish`, { method: 'POST' });
      if (!mounted.current) return;
      setCurrent({ ...saved, status: 'published', result_type: result.result_type, result_id: result.result_id, run_id: result.run_id });
      setDirty(false);
      showToast(result.run_status === 'waiting_input' ? '已创建活动，请在详情中补充缺失信息'
        : result.run_status === 'failed' ? '活动执行失败，请在详情中查看原因并重试'
          : result.run_id && result.run_status !== 'succeeded' ? '已提交执行，可在详情中查看进度' : '已发布');
      navigate(resultTarget(result));
    } catch (cause) {
      if (mounted.current) showToast(visibleOperationalError(cause instanceof Error ? cause.message : '', '发布未完成，草案修改已保留'), 'error');
    } finally { actionInFlight.current = false; if (mounted.current) setPublishing(false); }
  }

  async function handleHistoryClick(id: string) {
    if (actionInFlight.current || busy || !confirmReplacement()) return;
    actionInFlight.current = true;
    const request = ++draftSequence.current;
    setOpeningHistory(true);
    try {
      const item = await api<DraftItem>(`/agent/drafts/${id}`);
      if (mounted.current && request === draftSequence.current) {
        loadDraft(item);
        setShowHistory(false);
      }
    } catch (cause) {
      if (mounted.current) showToast(visibleOperationalError(cause instanceof Error ? cause.message : '', '加载草案失败，请重试'), 'error');
    } finally { actionInFlight.current = false; if (mounted.current) setOpeningHistory(false); }
  }

  function handleNew() {
    if (busy || !confirmReplacement()) return;
    draftSequence.current += 1; setCurrent(null); setDirty(false); setText(''); setShowComposer(true);
    requestAnimationFrame(() => composerInput.current?.focus());
  }

  function useExample(example: string) {
    if (busy || (reviewing && !confirmReplacement())) return;
    if (reviewing) { setCurrent(null); setDirty(false); }
    setText(example); setShowComposer(true);
    requestAnimationFrame(() => composerInput.current?.focus());
  }

  function editRequest() {
    if (!current || !confirmReplacement()) return;
    setText(current.raw_input ?? current.draft.description ?? '');
    if (current.class_id) setClassId(current.class_id);
    setCurrent(null); setDirty(false); setShowComposer(true);
    requestAnimationFrame(() => composerInput.current?.focus());
  }

  function updateQuestion(name: string, update: Partial<PublicFormField>) {
    setFormFields((fields) => registrationQuestions(fields).map((field) => field.name === name ? { ...field, ...update } : field));
    setDirty(true);
  }

  function addQuestion() {
    const fields = registrationQuestions(formFields);
    let next = 1;
    while (fields.some((field) => field.name === `question_${next}`)) next += 1;
    setFormFields([...fields, { name: `question_${next}`, label: '', type: 'text', required: false, options: [] }]);
    setDirty(true);
  }

  const visibleFields = registrationQuestions(formFields);
  const plan = current?.draft.execution_plan;
  const tasks = plan?.tasks ?? [];
  const missingQuestions = isActivity || isSurvey ? [...new Set(current?.draft.missing_information ?? [])]
    .filter((question) => !/活动时间|开始时间|活动日期|event_time/.test(question)) : [];
  const options = [
    ...(isActivity ? [
      { key: 'create_form', label: '开放报名', help: '发布后在活动详情提供报名链接。' },
      { key: 'schedule_reminder', label: '安排活动提醒', help: `活动开始前 ${plan?.remind_minutes_before ?? 60} 分钟提醒。` },
      { key: 'create_calendar', label: '生成日历文件', help: '发布后可从活动详情下载日历文件。' },
      ...(tasks.length || workflow.assign_tasks ? [{ key: 'assign_tasks', label: '创建分工任务', help: tasks.length
        ? `发布时创建建议的 ${tasks.length} 项任务，可在「分工预览」核对。`
        : '这份草案没有可用的分工方案。可取消勾选，或返回修改需求后重新生成。' }] : []),
    ] : []),
    { key: 'publish_message', label: '发送到 QQ 群', help: groupNotBound
      ? '班级尚未绑定 QQ 群，可在「成员管理」中绑定。'
      : '勾选后同步到所选班级绑定的 QQ 群。' },
  ];
  const activeOptions = options.filter(({ key }) => key === 'publish_message' ? sendsToGroup : workflow[key]);
  const resultLabel = intentType === 'assignment' ? '作业与待办' : intentType === 'notice' ? '班级 QQ 群' : '活动管理';

  return (
    <>
      <header className="ws-header">
        <div><h1>AI 助手</h1><p>用一句话安排班级事务，核对草案后发布。</p></div>
        <div className="ws-actions">
          <button className="ws-button light" type="button" aria-expanded={showTutorial} aria-controls="agent-tutorial" onClick={() => setShowTutorial(!showTutorial)}>使用教程</button>
          <button className="ws-button light" type="button" aria-expanded={showHistory} aria-controls="agent-history" onClick={() => setShowHistory(!showHistory)}>草案历史</button>
          {reviewing && <button className="ws-button light" type="button" disabled={busy} onClick={handleNew}>新建需求</button>}
        </div>
      </header>
      <section id="agent-tutorial" className="ws-panel agent-tutorial" hidden={!showTutorial} aria-labelledby="agent-tutorial-title">
        <h2 id="agent-tutorial-title">跟着示例安排一次班会</h2>
        <ol>
          <li><div><strong>1. 填写需求</strong><button className="ws-button light" type="button" disabled={busy} onClick={() => useExample(EXAMPLES[1].text)}>填入班会示例</button></div><p>点按钮后，示例会填进需求框。你可以把日期、时间和地点改成自己的。需要报名或分工时，也写清楚想收集哪些信息、有哪些准备工作。</p></li>
          <li><strong>2. 选班级，点「生成草案」</strong><p>选择这次班会所属的班级。生成时系统会保存一份草案，你可以稍后从「草案历史」继续。</p></li>
          <li><strong>3. 检查内容和发布选项</strong><p>核对班会的标题和开始时间。示例只安排站内提醒。需要分工的草案会列出建议任务，你可以在发布前关闭「创建分工任务」。</p></li>
          <li><strong>4. 点「确认发布」</strong><p>班会发布后会打开活动详情。只想练习或留到以后再发，点「保存草案」即可。</p></li>
        </ol>
      </section>
      <aside id="agent-history" className="ws-panel" hidden={!showHistory} aria-label="草案历史">
        <div className="ws-panel-title"><div><h2>草案历史</h2><p>选择一条草案继续编辑或查看结果。</p></div></div>
        <DataState loading={historyLoading && !history.length} error={historyError || null} onRetry={reloadHistory} empty={!historyLoading && !historyError && !history.length} emptyText="还没有草案" loadingText="正在读取草案历史…" />
        <div className="ws-list">{history.map((item) => <button key={item.id} type="button" className="ws-list-item" disabled={busy} onClick={() => handleHistoryClick(item.id)}>
          <span className={`ws-tag${item.status === 'published' ? ' green' : ''}`}>{item.status === 'published' ? '已发布' : '待确认'}</span>
          <span><strong>{item.title}</strong><small>{item.class_name}</small></span><b>›</b>
        </button>)}</div>
      </aside>
      {openingHistory && <DataState loading loadingText="正在打开草案…" />}
      {!reviewing && !openingHistory && <section className="agent-hero agent-request">
        <h2>1. 写下你的需求</h2><p>说清要办什么、什么时间，以及需要哪些额外安排。</p>
        <DataState loading={dashboardLoading && !dashboard} error={dashboardError} onRetry={reloadDashboard} loadingText="正在读取班级…" />
        {!dashboardLoading && !dashboardError && !classes.length && <p role="status">还没有班级，请先创建班级后再分析需求。</p>}
        <form className="agent-compose" onSubmit={handleAnalyze}>
          <textarea ref={composerInput} aria-label="需求描述" placeholder="例如：下周五下午两点开班会，提前半小时站内提醒。" value={text} disabled={busy} onChange={(event) => setText(event.target.value)} />
          <div className="agent-compose-footer">
            <select className="ws-select" aria-label="选择班级" value={classId} disabled={busy || dashboardLoading} onChange={(event) => setClassId(event.target.value)}>
              {!classes.length && <option value="">选择班级</option>}
              {classes.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
            <button className="ws-button" type="submit" disabled={busy || !classId || !text.trim()}>{analyzing ? '正在生成草案…' : '生成草案 →'}</button>
          </div>
        </form>
        <div className="example-chips"><span>填入示例</span>{EXAMPLES.map((example) => <button key={example.label} type="button" disabled={busy} onClick={() => useExample(example.text)}>{example.label}</button>)}</div>
      </section>}

      {reviewing && current && <article className="ws-panel agent-review">
          <div className="ws-panel-title">
            <div><h2 ref={reviewHeading} tabIndex={-1}>{published ? '已发布草案' : '2. 核对并发布'}</h2><p>{current.class_name}{dirty ? ' · 有未保存的修改' : ''}</p></div>
            {!published && <button type="button" className="ws-button light" disabled={busy} onClick={editRequest}>返回修改需求</button>}
          </div>
          <form className="ws-form" onSubmit={(event) => event.preventDefault()}>
            <fieldset disabled={busy || published} style={FIELDSET_STYLE} onChange={() => setDirty(true)}>
              <label>事务类型<select value={intentType} onChange={(event) => setIntentType(event.target.value as DraftDetail['intent_type'])}>
                {Object.entries(INTENT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select></label>
              <label>标题<input maxLength={255} value={title} onChange={(event) => setTitle(event.target.value)} /></label>
              <label>说明<textarea rows={3} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
              {intentType === 'assignment' && <label>作业截止时间<input type="datetime-local" step="any" value={deadline} onChange={(event) => setDeadline(event.target.value)} />{!deadline && !published && <small>发布前请填写截止时间。</small>}</label>}
              {isActivity && <label>活动开始时间<input type="datetime-local" step="any" value={eventTime} onChange={(event) => setEventTime(event.target.value)} />{!eventTime && !published && <small>发布前请确认活动日期和时间。</small>}</label>}
              {missingQuestions.map((question) => <label key={question}>{question}<input maxLength={5000} value={draftAnswers[question] ?? ''} onChange={(event) => setDraftAnswers((old) => ({ ...old, [question]: event.target.value }))} /></label>)}
              <details className="agent-details" open={intentType === 'notice' || undefined}>
                <summary>发布选项{activeOptions.length > 0 && <span>{activeOptions.map(({ label }) => label).join('、')}</span>}</summary>
                <div className="workflow-list">{options.map(({ key, label, help }) => <label key={key} className="workflow-item agent-option">
                  <input type="checkbox" checked={key === 'publish_message' ? sendsToGroup : !!workflow[key]} onChange={(event) => setWorkflow((old) => ({ ...old, [key]: event.target.checked }))} />
                  <span>{label}<small>{help}</small></span>
                </label>)}</div>
              </details>
              {needsForm && <details className="agent-details" open={isSurvey || undefined}>
                <summary>{isSurvey ? '问卷题目' : '报名表题目'}<span>{visibleFields.length} 题</span></summary>
                <div className="agent-questions">
                <p>系统会收集姓名和联系方式。你可以添加其他需要回答的问题。</p>
                {visibleFields.map((field, index) => {
                  const standard = field.name === 'name' || field.name === 'contact';
                  return <div key={field.name} style={{ border: '1px solid var(--line)', borderRadius: 9, padding: 12, display: 'grid', gap: 10 }}>
                    <label>第 {index + 1} 题<input value={field.label} onChange={(event) => updateQuestion(field.name, { label: event.target.value })} /></label>
                    <div className="ws-form-row">
                      <label>题型<select aria-label={`第 ${index + 1} 题题型`} value={field.type} disabled={standard} onChange={(event) => updateQuestion(field.name, { type: event.target.value })}><option value="text">文字回答</option><option value="select">单选题</option></select></label>
                      <label className="workflow-item" style={{ display: 'flex' }}><span>必填</span><input type="checkbox" style={{ width: 17, minHeight: 17 }} aria-label={`第 ${index + 1} 题必填`} checked={standard || !!field.required} disabled={standard} onChange={(event) => updateQuestion(field.name, { required: event.target.checked })} /></label>
                    </div>
                    {field.type === 'select' && <label>选项（每行一个）<textarea aria-label={`第 ${index + 1} 题选项`} rows={3} value={(field.options ?? []).join('\n')} onChange={(event) => updateQuestion(field.name, { options: event.target.value.split('\n') })} /></label>}
                    {!standard && <button type="button" className="ws-button light" onClick={() => { setFormFields((fields) => fields.filter((item) => item.name !== field.name)); setDirty(true); }}>删除第 {index + 1} 题</button>}
                  </div>;
                })}
                <button className="ws-button light" type="button" onClick={addQuestion}>添加题目</button>
                </div>
              </details>}
            </fieldset>
            {isActivity && workflow.assign_tasks && tasks.length > 0 && <details className="agent-details">
              <summary>分工预览<span>{tasks.length} 项</span></summary>
              <ul>{tasks.map((task, index) => <li key={index}>{task.title} · {task.assignee || '待分配'}</li>)}</ul>
            </details>}
            {isActivity && !!plan?.materials?.length && <details className="agent-details"><summary>物料清单</summary><ul>{plan.materials.map((material, index) => <li key={index}>{material}</li>)}</ul></details>}
            <details className="agent-details"><summary>查看生成依据</summary><GenerationInfo value={current.draft.generation as Generation | undefined} /></details>
            {!published && <p className="agent-result-note">{intentType === 'notice' ? '确认后会尝试发送到班级绑定的 QQ 群。' : <>
              发布后可在「{resultLabel}」查看{sendsToGroup ? '，并同步到班级 QQ 群' : ''}。{needsForm && '可在详情页复制填写链接。'}
            </>}</p>}
            <div className="publish-bar">
              {published ? <button type="button" className="ws-button" onClick={() => navigate(resultTarget(current))}>查看发布结果</button> : <>
                <button type="button" className="ws-button light" disabled={busy} onClick={handleSave}>{saving ? '保存中…' : '保存草案'}</button>
                <button type="button" className="ws-button" disabled={busy} onClick={handlePublish}>{publishing ? '正在提交…' : '确认发布'}</button>
              </>}
            </div>
          </form>
        </article>}
    </>
  );
}
