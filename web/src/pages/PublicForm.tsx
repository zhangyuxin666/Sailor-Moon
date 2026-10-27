import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '../lib/api';
import DataState from '../components/DataState';
import { formatDateFull } from '../lib/utils';
import type { PublicFormData } from '../types';
interface Receipt { registration_id:number; cancellation_token:string; status:string; }

export default function PublicForm() {
  const { id: formId } = useParams<{ id: string }>();
  const [data, setData] = useState<PublicFormData | null>(null);
  const [loadedFormId, setLoadedFormId] = useState<string | null>(null);
  const lastFormId = useRef<string | undefined>(undefined);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [submitError, setSubmitError] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [name, setName] = useState('');
  const [contact, setContact] = useState('');
  const [extra, setExtra] = useState<Record<string, string>>({});
  const [receipt,setReceipt]=useState<Receipt|null>(null);
  const [cancelling,setCancelling]=useState(false);
  const [receiptId,setReceiptId]=useState(''),[receiptToken,setReceiptToken]=useState('');
  const submission=useRef({ fingerprint:'', requestId:'', token:'' });

  useEffect(() => {
    let current = true;
    if (lastFormId.current !== formId) {
      lastFormId.current = formId;
      setData(null);
      setLoadedFormId(null);
      setName('');
      setContact('');
      setExtra({});
      setSubmitted(false);
      submission.current={fingerprint:'',requestId:'',token:''};
      setReceipt(null);
      try { const raw=localStorage.getItem('activity-registration:'+formId);if(raw){const saved=JSON.parse(raw) as Receipt;if(saved.registration_id&&saved.cancellation_token){setReceipt(saved);setSubmitted(saved.status==='active');}} }catch{/* The receipt also remains in memory when storage is unavailable. */}
      setSubmitting(false);
      setSubmitError('');
    }
    setLoadError('');
    if (!formId) {
      setLoadError('报名链接无效。');
      setLoading(false);
      return;
    }
    setLoading(true);
    api<PublicFormData>('/public/forms/' + encodeURIComponent(formId))
      .then(async (response) => {
        if (!current) return;
        setData(response);
        setLoadedFormId(formId);
        document.title = response.activity_title + ' · 活动报名';
        let saved:Receipt|null=null;
        try{const raw=localStorage.getItem('activity-registration:'+formId);if(raw)saved=JSON.parse(raw) as Receipt;}catch{/* No persistent receipt. */}
        if(saved?.registration_id&&saved.cancellation_token){
          try{
            const verified=await api<{registration_id:number;status:string}>(`/forms/${encodeURIComponent(formId)}/registrations/${saved.registration_id}/receipt`,{method:'POST',body:JSON.stringify({cancellation_token:saved.cancellation_token})});
            if(!current)return;
            const updated={...saved,status:verified.status};setReceipt(updated);setSubmitted(updated.status==='active');
            try{localStorage.setItem('activity-registration:'+formId,JSON.stringify(updated));}catch{/* Current receipt remains in memory. */}
          }catch{if(current)setSubmitError('保存的回执暂时无法核对，请刷新重试或输入保存的回执。');}
        }
      })
      .catch((cause) => {
        if (current) setLoadError(cause instanceof Error ? cause.message : '加载报名信息失败');
      })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [formId, revision]);

  const currentData = loadedFormId === formId ? data : null;
  const extraFields = (currentData?.fields ?? []).filter((field) => field.name !== 'name' && field.name !== 'contact');

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!formId || !currentData || loading || submitting || submitted || currentData.accepting_registrations === false) return;
    setSubmitting(true);
    setSubmitError('');
    try {
      const body={name:name.trim(),contact:contact.trim(),extra};
      const fingerprint=JSON.stringify(body);
      if(submission.current.fingerprint!==fingerprint)submission.current={fingerprint,requestId:crypto.randomUUID(),token:crypto.randomUUID()+crypto.randomUUID()};
      const saved=await api<Receipt>('/forms/' + encodeURIComponent(formId) + '/registrations', {
        method: 'POST',
        body: JSON.stringify({ ...body, request_id:submission.current.requestId, cancellation_token:submission.current.token }),
      });
      if (lastFormId.current === formId) {
        setSubmitted(saved.status==='active');setReceipt(saved);setRevision(v=>v+1);
        if(saved.status!=='active')submission.current={fingerprint:'',requestId:'',token:''};
        try{localStorage.setItem('activity-registration:'+formId,JSON.stringify(saved));}catch{/* Keep the visible receipt. */}
      }
    } catch (cause) {
      if (lastFormId.current === formId) setSubmitError(cause instanceof Error ? cause.message : '报名失败，请稍后重试');
    } finally {
      if (lastFormId.current === formId) setSubmitting(false);
    }
  }

  async function restoreReceipt(event:FormEvent){
    event.preventDefault();if(!formId||cancelling)return;setCancelling(true);setSubmitError('');
    try{
      const result=await api<{registration_id:number;status:string}>(`/forms/${encodeURIComponent(formId)}/registrations/${encodeURIComponent(receiptId)}/receipt`,{method:'POST',body:JSON.stringify({cancellation_token:receiptToken})});
      const restored={...result,cancellation_token:receiptToken};setReceipt(restored);setSubmitted(result.status==='active');
      try{localStorage.setItem('activity-registration:'+formId,JSON.stringify(restored));}catch{/* Receipt remains visible. */}
      setReceiptId('');setReceiptToken('');
    }catch(cause){setSubmitError(cause instanceof Error?cause.message:'回执校验失败');}finally{setCancelling(false);}
  }
  async function cancelRegistration(){
    if(!receipt||!formId||cancelling)return;
    if(!window.confirm('确认取消本次报名？名额将释放。'))return;
    setCancelling(true);setSubmitError('');
    try{
      await api(`/forms/${encodeURIComponent(formId)}/registrations/${receipt.registration_id}/cancel`,{method:'POST',body:JSON.stringify({cancellation_token:receipt.cancellation_token})});
      const cancelled={...receipt,status:'cancelled'};setReceipt(cancelled);setSubmitted(false);submission.current={fingerprint:'',requestId:'',token:''};
      try{localStorage.removeItem('activity-registration:'+formId);}catch{/* In-memory state remains accurate. */}
      setRevision(v=>v+1);
    }catch(cause){setSubmitError(cause instanceof Error?cause.message:'取消失败，回执已保留');}finally{setCancelling(false);}
  }
  return (
    <div className="public-form-page">
      <main className="public-form-shell">
        <div className="public-form-brand"><span className="brand-mark">活</span><strong>活动管家</strong></div>
        <section className="public-form-card">
          <DataState loading={loading && !currentData} error={loadError}
            onRetry={formId ? () => setRevision((value) => value + 1) : undefined}
            loadingText="正在载入活动信息…" empty={!loading && !loadError && !currentData} emptyText="暂无报名信息" />
          {currentData && <>
            <h1>{currentData.activity_title}</h1>
            <p className="form-description">{currentData.description}</p>
            <div className="form-time">◷ {formatDateFull(currentData.event_time)}</div>
            {loading && <p role="status">正在刷新报名信息，已填写内容会保留…</p>}
            {<button type="button" className="ws-button light" disabled={loading || submitting}
              onClick={() => setRevision((value) => value + 1)}>刷新报名信息</button>}
            <p>报名状态：{currentData.closed_reason || '开放报名'} · 有效报名 {currentData.registration_count??0} 人 · {currentData.remaining==null?'不限名额':`剩余 ${currentData.remaining} 个名额`}</p>
            {currentData.registration_deadline&&<p>截止：{formatDateFull(currentData.registration_deadline)}</p>}
            <p>同一姓名与联系方式只允许一份有效报名；新增回答须符合已发布题目。</p>
            {receipt?.status==='cancelled'&&<p role="status">本次报名已取消，名额已释放。开放时可以重新报名。</p>}
            {submitted ? <div className="form-success">
              <span>✓</span><h2>报名成功</h2><p>活动负责人已收到你的报名信息。</p><p>报名回执编号：{receipt?.registration_id}</p>{receipt&&<><p>取消凭证（请自行保存）：<code style={{overflowWrap:'anywhere'}}>{receipt.cancellation_token}</code></p><p>本设备保留回执以便取消；不要把取消凭证交给他人。</p><button className="ws-button danger" type="button" disabled={cancelling} onClick={cancelRegistration}>{cancelling?'正在取消…':'取消本次报名'}</button></>}{submitError&&<p role="alert">{submitError}</p>}
            </div> : <form className="public-registration-form" onSubmit={handleSubmit}>
              <fieldset disabled={submitting || currentData.accepting_registrations === false} style={{ border: 0, margin: 0, padding: 0, display: 'grid', gap: 16 }}>
                <label>{currentData.fields.find(field=>field.name==='name')?.label||'姓名'}<input required maxLength={120} placeholder="请输入姓名" value={name} onChange={(event) => setName(event.target.value)} /></label>
                <label>{currentData.fields.find(field=>field.name==='contact')?.label||'联系方式'}<input required maxLength={255} placeholder="手机号或 QQ" value={contact} onChange={(event) => setContact(event.target.value)} /></label>
                {extraFields.map((field) => <label key={field.name}>
                  {field.label}
                  {field.type === 'select' ? <select required={field.required} value={extra[field.name] ?? ''}
                    onChange={(event) => setExtra((previous) => ({ ...previous, [field.name]: event.target.value }))}>
                    <option value="">请选择</option>
                    {(field.options ?? []).map((option) => <option key={option} value={option}>{option}</option>)}
                  </select> : <input required={field.required} maxLength={2000} placeholder={field.label} value={extra[field.name] ?? ''}
                    onChange={(event) => setExtra((previous) => ({ ...previous, [field.name]: event.target.value }))} />}
                </label>)}
                {submitError && <p className="form-description" role="alert">{submitError}。输入已保留，可以重试。</p>}
                <button className="ws-button" type="submit" disabled={submitting || loading}>
                  {submitting ? '提交中…' : '确认报名'} <b>→</b>
                </button>
              </fieldset>
            </form>}
            <details><summary>使用保存的回执查看或取消报名</summary><form className="ws-form" onSubmit={restoreReceipt}><label>报名回执编号<input required inputMode="numeric" value={receiptId} onChange={e=>setReceiptId(e.target.value)}/></label><label>取消凭证<input required minLength={32} maxLength={100} autoComplete="off" value={receiptToken} onChange={e=>setReceiptToken(e.target.value)}/></label><button className="ws-button light" disabled={cancelling} type="submit">读取报名回执</button></form></details>
          </>}
        </section>
      </main>
    </div>
  );
}
