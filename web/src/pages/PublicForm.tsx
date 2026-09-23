/**
 * 公开报名页 /forms/:id（无需登录，独立页面，不用 Layout）
 * 对应旧 frontend/form.html。
 * - GET /public/forms/{formId} 返回 PublicFormData
 * - 固定姓名 + 联系方式，其余字段从 fields 过滤掉 name/contact 后渲染（text/select）
 * - POST /forms/{formId}/registrations，body { name, contact, extra }
 */
import { useEffect, useState, type FormEvent } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '../lib/api';
import { formatDateFull } from '../lib/utils';
import type { PublicFormData } from '../types';

export default function PublicForm() {
  const { id: formId } = useParams<{ id: string }>();

  const [data, setData] = useState<PublicFormData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const [name, setName] = useState('');
  const [contact, setContact] = useState('');
  const [extra, setExtra] = useState<Record<string, string>>({});

  // 加载公开表单数据
  useEffect(() => {
    if (!formId) {
      setError('报名链接无效。');
      setLoading(false);
      return;
    }
    api<PublicFormData>(`/public/forms/${formId}`)
      .then((d) => {
        setData(d);
        document.title = `${d.activity_title} · 活动报名`;
      })
      .catch((e) => setError(e instanceof Error ? e.message : '加载报名信息失败'))
      .finally(() => setLoading(false));
  }, [formId]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!formId) return;
    setSubmitting(true);
    try {
      await api(`/forms/${formId}/registrations`, {
        method: 'POST',
        body: JSON.stringify({ name: name.trim(), contact: contact.trim(), extra }),
      });
      setSubmitted(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : '报名失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const extraFields = (data?.fields ?? []).filter(
    (field) => field.name !== 'name' && field.name !== 'contact',
  );

  return (
    <div className="public-form-page">
      <main className="public-form-shell">
        <div className="public-form-brand">
          <span className="brand-mark">活</span>
          <strong>活动管家</strong>
        </div>

        <section className="public-form-card">
          {loading ? (
            <p className="form-description">正在载入活动信息…</p>
          ) : error && !data ? (
            <p className="form-description">{error}</p>
          ) : data ? (
            <>
              <h1>{data.activity_title}</h1>
              <p className="form-description">{data.description}</p>
              <div className="form-time">◷ {formatDateFull(data.event_time)}</div>

              {submitted ? (
                <div className="form-success">
                  <span>✓</span>
                  <h2>报名成功</h2>
                  <p>活动负责人已收到你的报名信息。</p>
                </div>
              ) : (
                <form className="public-registration-form" onSubmit={handleSubmit}>
                  <label>
                    姓名
                    <input
                      required
                      placeholder="请输入姓名"
                      value={name}
                      onChange={(e) => setName(e.target.value)}
                    />
                  </label>
                  <label>
                    联系方式
                    <input
                      required
                      placeholder="手机号或 QQ"
                      value={contact}
                      onChange={(e) => setContact(e.target.value)}
                    />
                  </label>

                  {extraFields.map((field) =>
                    field.type === 'select' ? (
                      <label key={field.name}>
                        {field.label}
                        <select
                          required={field.required}
                          value={extra[field.name] ?? ''}
                          onChange={(e) =>
                            setExtra((prev) => ({ ...prev, [field.name]: e.target.value }))
                          }
                        >
                          <option value="">请选择</option>
                          {(field.options ?? []).map((opt) => (
                            <option key={opt} value={opt}>
                              {opt}
                            </option>
                          ))}
                        </select>
                      </label>
                    ) : (
                      <label key={field.name}>
                        {field.label}
                        <input
                          required={field.required}
                          placeholder={field.label}
                          value={extra[field.name] ?? ''}
                          onChange={(e) =>
                            setExtra((prev) => ({ ...prev, [field.name]: e.target.value }))
                          }
                        />
                      </label>
                    ),
                  )}

                  {error && <p className="form-description">{error}</p>}

                  <button className="ws-button" type="submit" disabled={submitting}>
                    {submitting ? '提交中…' : '确认报名'} <b>→</b>
                  </button>
                </form>
              )}
            </>
          ) : null}
        </section>
      </main>
    </div>
  );
}
