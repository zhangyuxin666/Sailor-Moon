/** Draft confirmation keeps the questionnaire and execution plan visible before publication. */
import { useState, useEffect, useRef, useCallback, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { api, useApi } from '../lib/api';
import { useToast } from '../components/Toast';
import DataState from '../components/DataState';
import GenerationInfo,{type Generation} from '../components/GenerationInfo';
import { formatDate, isoToLocal, localToISO } from '../lib/utils';
import { registrationQuestions, validateFormFields, visibleOperationalError } from '../lib/activity-view';
import type { DashboardData, DraftDetail, DraftItem, DraftListResponse, DraftWorkflow, PublicFormField, PublishResult } from '../types';

const WORKFLOW_LABELS: Record<string, string> = {
  create_plan: '生成方案', create_form: '创建表单', assign_tasks: '任务分工',
  create_calendar: '写入日历', schedule_reminder: '定时提醒', publish_message: '发布群消息',
  collect_submission: '收集文件/完成情况',
};
const INTENT_LABELS: Record<DraftDetail['intent_type'], string> = {
  assignment: '收作业', activity: '活动', survey: '信息收集', notice: '通知',
};
const COMPLEXITY_LABELS: Record<DraftDetail['complexity'], string> = {
  simple: '轻量流程', standard: '标准流程', complex: '复杂协作',
};
const EXAMPLES = [
  { label: '收作业', text: '本周五前收齐全班的软件测试实验报告，提交 PDF，截止前一天提醒未交同学' },
  { label: '组织活动', text: '下月举办班级春游，需要报名、分工、物料、日历和活动前提醒' },
  { label: '收集信息', text: '统计大家下周空闲时间，生成问卷发到群里' },
  { label: '发通知', text: '通知大家明天下午两点到教学楼集合' },
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
  const [intentType, setIntentType] = useState<DraftDetail['intent_type']>('assignment');
  const [complexity, setComplexity] = useState<DraftDetail['complexity']>('simple');
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
  const busy = analyzing || saving || publishing || openingHistory;
  const published = current?.status === 'published';
  const needsForm = intentType === 'survey' || intentType === 'activity' || !!workflow.create_form;

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
    const draft = item.draft;
    setIntentType(draft.intent_type); setComplexity(draft.complexity);
    setTitle(draft.title); setDescription(draft.description || draft.summary || '');
    setDeadline(isoToLocal(draft.deadline)); setEventTime(isoToLocal(draft.event_time));
    setWorkflow({ ...draft.workflow });
    const savedInput = draft.input && typeof draft.input === 'object' ? draft.input as Record<string, unknown> : {};
    const savedAnswers = savedInput.answers;
    setDraftAnswers(savedAnswers && typeof savedAnswers === 'object' && !Array.isArray(savedAnswers)
      ? Object.fromEntries(Object.entries(savedAnswers).map(([key, value]) => [key, String(value ?? '')])) : {});
    setFormFields((draft.intent_type === 'activity' || draft.intent_type === 'survey' || draft.workflow.create_form)
      ? registrationQuestions(draft.form_fields ?? []) : (draft.form_fields ?? []).map((field) => ({ ...field, options: [...(field.options ?? [])] })));
    setDirty(false);
  }

  function collectDraft(): DraftDetail {
    if (!current) throw new Error('没有正在编辑的草案');
    if (!title.trim()) throw new Error('请填写标题');
    const fields = validateFormFields(needsForm ? registrationQuestions(formFields) : formFields);
    const nextTime = eventTime ? localToISO(eventTime) : '';
    const savedInput = current.draft.input && typeof current.draft.input === 'object' ? current.draft.input as Record<string, unknown> : {};
    return {
      ...current.draft, intent_type: intentType, complexity, title: title.trim(),
      description: description.trim(), deadline: deadline ? localToISO(deadline) : '',
      event_time: nextTime, workflow: { ...workflow }, form_fields: fields,
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
      if (mounted.current && request === draftSequence.current) loadDraft(item);
    } catch (cause) {
      if (mounted.current) showToast(visibleOperationalError(cause instanceof Error ? cause.message : '', '加载草案失败，请重试'), 'error');
    } finally { actionInFlight.current = false; if (mounted.current) setOpeningHistory(false); }
  }

  function handleNew() {
    if (busy || !confirmReplacement()) return;
    draftSequence.current += 1; setCurrent(null); setDirty(false); setText('');
    window.scrollTo({ top: 0, behavior: 'smooth' });
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

  const visibleFields = needsForm ? registrationQuestions(formFields) : formFields;
  const plan = current?.draft.execution_plan;
  const missingQuestions = (current?.draft.missing_information ?? []).filter((question) => !/活动时间|开始时间|活动日期|event_time/.test(question));

  return (
    <>
      <header className="ws-header">
        <div><p className="ws-kicker">AI ASSISTANT</p><h1>AI 助手</h1><p>描述需求，编辑问卷并核对任务和物料，确认后发布。</p></div>
        <div className="ws-actions"><button className="ws-button" type="button" disabled={busy} onClick={handleNew}>＋ 创建新草案</button></div>
      </header>
      <section className="agent-hero">
        <h2>让 Agent 帮你安排</h2><p>告诉我目标、对象和时间。</p>
        <DataState loading={dashboardLoading && !dashboard} error={dashboardError} onRetry={reloadDashboard} loadingText="正在读取班级…" />
        {!dashboardLoading && !dashboardError && !classes.length && <p role="status">还没有班级，请先创建班级后再分析需求。</p>}
        <form className="agent-compose" onSubmit={handleAnalyze}>
          <textarea aria-label="需求描述" placeholder="例如：本周五前收齐全班的实验报告，PDF 格式，提醒未提交的同学。" value={text} disabled={busy} onChange={(event) => setText(event.target.value)} />
          <div className="agent-compose-footer">
            <select className="ws-select" aria-label="选择班级" value={classId} disabled={busy || dashboardLoading} onChange={(event) => setClassId(event.target.value)}>
              {!classes.length && <option value="">选择班级</option>}
              {classes.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
            <button className="ws-button" type="submit" disabled={busy || !classId}>{analyzing ? 'Agent 正在分析…' : '分析需求 →'}</button>
          </div>
        </form>
        <div className="example-chips">{EXAMPLES.map((example) => <button key={example.label} type="button" disabled={busy} onClick={() => setText(example.text)}>{example.label}</button>)}</div>
      </section>

      <section className="draft-layout">
        {current ? <article className="ws-panel">
          <div className="ws-panel-title">
            <div><h2>{published ? '已发布草案' : '确认草案'}</h2><p>{published ? '查看发布时的内容' : dirty ? '有未保存的修改' : '核对内容后保存或发布'}</p></div>
            <div className="draft-badges"><span className="draft-badge">{INTENT_LABELS[intentType]}</span><span className={`draft-badge${complexity === 'complex' ? ' complex' : ''}`}>{COMPLEXITY_LABELS[complexity]}</span></div>
          </div>
          <form className="ws-form" onSubmit={(event) => event.preventDefault()}>
            <fieldset disabled={busy || published} style={FIELDSET_STYLE} onChange={() => setDirty(true)}>
              <div className="ws-form-row">
                <label>任务类型<select value={intentType} onChange={(event) => {
                  const nextIntent = event.target.value as DraftDetail['intent_type'];
                  setIntentType(nextIntent);
                  if (nextIntent === 'activity' || nextIntent === 'survey') setFormFields((fields) => registrationQuestions(fields));
                }}><option value="assignment">收作业</option><option value="activity">组织活动</option><option value="survey">收集信息</option><option value="notice">发布通知</option></select></label>
                <label>复杂度<select value={complexity} onChange={(event) => setComplexity(event.target.value as DraftDetail['complexity'])}><option value="simple">轻量</option><option value="standard">标准</option><option value="complex">复杂</option></select></label>
              </div>
              <GenerationInfo value={current?.draft.generation as Generation|undefined}/>
              <label>标题<input value={title} onChange={(event) => setTitle(event.target.value)} /></label>
              <label>说明<textarea rows={5} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
              <div className="ws-form-row">
                <label>截止时间<input type="datetime-local" step="any" value={deadline} onChange={(event) => setDeadline(event.target.value)} /></label>
                <label>活动时间<input type="datetime-local" step="any" value={eventTime} onChange={(event) => setEventTime(event.target.value)} /></label>
              </div>
              {!!current.draft.missing_information?.length && <div className="missing-list" aria-label="待确认信息">{current.draft.missing_information.map((item, index) => <span key={index}>{item}</span>)}</div>}
              {missingQuestions.map((question) => <label key={question}>{question}<input maxLength={5000} value={draftAnswers[question] ?? ''} onChange={(event) => setDraftAnswers((old) => ({ ...old, [question]: event.target.value }))} /></label>)}
              {current.draft.reasoning && <div className="reason-box">安排说明：{current.draft.reasoning}</div>}
              <div className="workflow-list">{Object.entries(WORKFLOW_LABELS).map(([key, label]) => <label key={key} className="workflow-item" style={{ display: 'flex' }}><span>{label}</span><input type="checkbox" style={{ width: 17, minHeight: 17 }} checked={!!workflow[key]} onChange={() => {
                setWorkflow((old) => ({ ...old, [key]: !old[key] }));
                if (key === 'create_form' && !workflow[key]) setFormFields((fields) => registrationQuestions(fields));
              }} /></label>)}</div>
              {(needsForm || visibleFields.length > 0) && <section aria-label="问卷题目" style={{ display: 'grid', gap: 12 }}>
                <div><h3>问卷题目</h3><p>姓名和联系方式会随问卷收集，请保留这两项。</p></div>
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
              </section>}
            </fieldset>
            {intentType === 'activity' && <section aria-label="发布前执行安排" className="reason-box">
              <h3>发布前执行安排</h3>
              <p>活动时间：{formatDate(eventTime)}</p>
              <h4>任务分工{workflow.assign_tasks ? '（已启用）' : '（未启用）'}</h4>
              {plan?.tasks?.length ? <ul>{plan.tasks.map((task, index) => <li key={index}>{task.title} · {task.assignee || '尚未指定负责人'}</li>)}</ul> : <p>暂无具体任务分工。</p>}
              <h4>物料清单</h4>
              {plan?.materials?.length ? <ul>{plan.materials.map((material, index) => <li key={index}>{material}</li>)}</ul> : <p>暂无物料。</p>}
              {typeof plan?.remind_minutes_before === 'number' && <p>提醒安排：活动前 {plan.remind_minutes_before} 分钟。</p>}
              {!plan && workflow.assign_tasks && <p role="status">这份历史草案没有完整任务安排，请重新分析需求后确认发布。</p>}
            </section>}
            <div className="publish-bar">
              {published ? <button type="button" className="ws-button" onClick={() => navigate(resultTarget(current))}>查看发布结果</button> : <>
                <button type="button" className="ws-button light" disabled={busy} onClick={handleSave}>{saving ? '保存中…' : '保存草案'}</button>
                <button type="button" className="ws-button" disabled={busy} onClick={handlePublish}>{publishing ? '正在提交…' : '确认发布'}</button>
              </>}
            </div>
          </form>
        </article> : <article className="ws-panel"><DataState loading={openingHistory} loadingText="正在打开草案…" empty={!openingHistory} emptyText="先描述需求生成草案，也可以从右侧打开历史草案。" /></article>}
        <aside className="ws-panel">
          <div className="ws-panel-title"><div><h2>草案历史</h2><p>继续编辑已保存草案，或查看已发布的结果。</p></div><button className="ws-button light" type="button" style={{ whiteSpace: 'nowrap', flexShrink: 0 }} disabled={historyLoading} onClick={reloadHistory}>刷新</button></div>
          <DataState loading={historyLoading && !history.length} error={historyError || null} onRetry={reloadHistory} empty={!historyLoading && !historyError && !history.length} emptyText="还没有草案" loadingText="正在读取草案历史…" />
          <div className="ws-list">{history.map((item) => <button key={item.id} type="button" className="ws-list-item" disabled={busy} onClick={() => handleHistoryClick(item.id)}>
            <span className={`ws-tag${item.status === 'published' ? ' green' : ''}`}>{item.status === 'published' ? '已发布' : '待确认'}</span>
            <span><strong>{item.title}</strong><small>{item.class_name} · {item.summary}</small></span><b>›</b>
          </button>)}</div>
        </aside>
      </section>
    </>
  );
}
