import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { api, type ApiError } from '../lib/api';
import { useAuth } from '../lib/auth';
import { useToast } from '../components/Toast';
import { hashFile } from '../lib/file-hash';
import { pool, progress, ranges, validateRange } from '../lib/file-transfer';
import { blocks, clearTransfer, getTransfer, saveBlock, saveTransfer, type TransferRecord } from '../lib/transfer-store';

const BLOCK = 2 * 1024 * 1024;
type State = 'pending' | 'uploading' | 'done' | 'failed';
interface Asset { id: string; original_filename: string; size_bytes: number; sha256?: string; created_at: string }
interface Upload extends TransferRecord {
  file: File; states: State[]; active: Record<number, number>; confirmed: number;
  running: boolean; paused: boolean; corrupt: boolean; error?: string; complete: boolean;
}
interface Download extends TransferRecord {
  fileId: string; active: Record<number, number>; confirmed: number;
  running: boolean; paused: boolean; connections: number; error?: string; complete: boolean;
}
type TransferError = ApiError & { data?: { missing?: number[]; detail?: string } };
function message(error: unknown) { return error instanceof Error ? error.message : '传输失败'; }
function size(bytes: number) { return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(2)} MiB` : `${(bytes / 1024).toFixed(1)} KiB`; }
function save(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}
function wireError(status: number, text: string): TransferError {
  let data: { detail?: string; code?: string } = {};
  try { data = JSON.parse(text); } catch { /* A proxy may return HTML. */ }
  return Object.assign(new Error(data.detail ?? `请求失败（${status}）`), { status, code: data.code, data });
}
async function xhr(url: string, method: string, body: XMLHttpRequestBodyInit, signal: AbortSignal,
  onProgress: (bytes: number, total: number) => void, headers: Record<string, string> = {}): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open(method, url); request.withCredentials = true; request.timeout = 120000;
    Object.entries(headers).forEach(([key, value]) => request.setRequestHeader(key, value));
    const abort = () => request.abort();
    const finish = () => signal.removeEventListener('abort', abort);
    request.upload.onprogress = event => onProgress(event.loaded, event.lengthComputable ? event.total : 0);
    request.onload = () => {
      finish();
      if (request.status < 200 || request.status >= 300) { reject(wireError(request.status, request.responseText)); return; }
      try { resolve(JSON.parse(request.responseText)); } catch { reject(new Error('服务器响应不是有效JSON')); }
    };
    request.onerror = () => { finish(); reject(new Error('网络中断')); };
    request.ontimeout = () => { finish(); reject(new Error('传输超时')); };
    request.onabort = () => { finish(); reject(new DOMException('已暂停', 'AbortError')); };
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) reject(new DOMException('已暂停', 'AbortError')); else request.send(body);
  });
}
const card: CSSProperties = { border: '1px solid #e5e5e2', borderRadius: 12, padding: 18, marginBottom: 18, background: '#fff' };
function Bar({ value, label }: { value: number; label: string }) {
  return <div><div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}><span>{label}</span><span>{value.toFixed(1)}%</span></div>
    <progress aria-label={label} value={value} max={100} style={{ width: '100%', height: 14, accentColor: '#228764' }} /></div>;
}

export default function Files() {
  const { account } = useAuth();
  const { showToast } = useToast();
  const owner = account?.id ?? 'anonymous';
  const uploadKey = `${owner}:upload`;
  const upload = useRef<Upload | null>(null);
  const download = useRef<Download | null>(null);
  const uploadControllers = useRef(new Set<AbortController>());
  const downloadControllers = useRef(new Set<AbortController>());
  const mounted = useRef(true);
  const [, version] = useState(0);
  const refresh = useCallback(() => { if (mounted.current) version(x => x + 1); }, []);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [hint, setHint] = useState<TransferRecord>();
  const [hashing, setHashing] = useState<number | null>(null);
  const [corrupt, setCorrupt] = useState(false);
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchPercent, setBatchPercent] = useState(0);
  const [batchResults, setBatchResults] = useState<Array<{ filename: string; ok: boolean; error?: string }>>([]);
  const [logs, setLogs] = useState<string[]>([]);
  const log = (value: string) => { if (mounted.current) setLogs(xs => [`${new Date().toLocaleTimeString()} ${value}`, ...xs].slice(0, 24)); };
  const load = useCallback(async () => {
    try { setAssets((await api<{ files: Asset[] }>('/files/mine')).files); }
    catch (error) { showToast(message(error), 'error'); }
  }, [showToast]);
  useEffect(() => {
    mounted.current = true;
    void load();
    void getTransfer(uploadKey).then(setHint).catch(error => showToast(`无法恢复缓存：${message(error)}`, 'error'));
    return () => {
      mounted.current = false;
      if (upload.current) upload.current.paused = true;
      if (download.current) download.current.paused = true;
      uploadControllers.current.forEach(c => c.abort()); downloadControllers.current.forEach(c => c.abort());
    };
  }, [load, uploadKey, showToast]);

  async function runUpload(job: Upload) {
    if (job.running || job.complete) return;
    job.running = true; job.paused = false; job.error = undefined; refresh();
    try {
      for (let repair = 0; repair < 3 && !job.paused; repair++) {
        const pending = job.states.flatMap((state, index) => state === 'done' ? [] : [index]);
        await pool(pending.length, 3, async position => {
          if (job.paused) return;
          const index = pending[position];
          const start = index * job.chunkSize!;
          const end = Math.min(job.size, start + job.chunkSize!);
          job.states[index] = 'uploading';
          for (let attempt = 0; attempt < 3; attempt++) {
            if (job.paused) return;
            const controller = new AbortController(); uploadControllers.current.add(controller);
            try {
              const body = await job.file.slice(start, end).arrayBuffer();
              const sha = await hashFile(new Blob([body]));
              if (job.corrupt) { job.corrupt = false; new Uint8Array(body)[0] ^= 255; }
              await xhr(`/files/uploads/${job.uploadId}/chunks/${index}`, 'PUT', body, controller.signal,
                bytes => { job.active[index] = Math.min(bytes, end - start); refresh(); },
                { 'Content-Type': 'application/octet-stream', 'X-Chunk-Sha256': sha });
              job.confirmed += end - start; delete job.active[index]; job.states[index] = 'done'; refresh(); return;
            } catch (error) {
              delete job.active[index];
              if (job.paused || (error as Error).name === 'AbortError') { job.states[index] = 'pending'; return; }
              const failure = error as TransferError;
              log(`分片 ${index} 失败：${failure.code ?? message(error)}；${attempt < 2 ? '重传该片' : '达到重试上限'}`);
              if (attempt === 2 || (failure.status && failure.status < 500 && failure.status !== 429 && failure.code !== 'CHUNK_CHECKSUM_MISMATCH')) {
                job.states[index] = 'failed'; throw error;
              }
              await new Promise(resolve => setTimeout(resolve, 300 * 2 ** attempt));
            } finally { uploadControllers.current.delete(controller); }
          }
        });
        if (job.paused) break;
        try {
          const result = await api<{ file_id: string }>(`/files/uploads/${job.uploadId}/complete`, { method: 'POST' });
          job.fileId = result.file_id; job.complete = true;
          await clearTransfer(job.key); setHint(undefined); log('全部分片及整文件SHA-256验证通过，合并完成'); await load(); break;
        } catch (error) {
          const missing = (error as TransferError).data?.missing;
          if (!missing?.length || repair === 2) throw error;
          missing.forEach(index => { if (index >= 0 && index < job.states.length) job.states[index] = 'pending'; });
          job.confirmed = job.states.reduce((total, state, i) => total + (state === 'done' ? Math.min(job.chunkSize!, job.size - i * job.chunkSize!) : 0), 0);
          log(`合并前发现磁盘分片损坏/缺失：${missing.join(', ')}，只重传这些片`);
        }
      }
    } catch (error) { job.paused = true; job.error = message(error); log(job.error); }
    finally { job.running = false; refresh(); }
  }

  async function selectFile(file: File) {
    if (upload.current?.running || hashing !== null) return;
    if (!file.size || file.size > 2 * 1024 ** 3) { showToast('请选择大于0且不超过2GiB的文件', 'error'); return; }
    setHashing(0);
    try {
      const sha = await hashFile(file, setHashing);
      let meta = await getTransfer(uploadKey);
      let uploaded: number[] = [];
      if (meta) {
        if (meta.sha256 !== sha || meta.size !== file.size) throw new Error('文件内容不同，请选择原文件，或先取消旧的续传任务');
        const status = await api<{ uploaded: number[] }>(`/files/uploads/${meta.uploadId}`);
        uploaded = status.uploaded; log(`恢复服务端已确认的${uploaded.length}个分片，跳过它们`);
      } else {
        const response = await api<{ upload_id: string; chunk_size: number; total_chunks: number; instant?: boolean; file_id?: string }>('/files/uploads',
          { method: 'POST', body: JSON.stringify({ filename: file.name, content_type: file.type, size: file.size, sha256: sha }) });
        if (response.instant) { log('整文件哈希命中，秒传完成'); await load(); return; }
        meta = { key: uploadKey, kind: 'upload', filename: file.name, size: file.size, sha256: sha, uploadId: response.upload_id, chunkSize: response.chunk_size };
        await saveTransfer(meta); setHint(meta);
      }
      const states = ranges(file.size, meta.chunkSize!).map((_, index) => uploaded.includes(index) ? 'done' as const : 'pending' as const);
      const job: Upload = { ...meta, file, states, active: {}, confirmed: states.reduce((n, state, i) => n + (state === 'done' ? Math.min(meta!.chunkSize!, file.size - i * meta!.chunkSize!) : 0), 0), running: false, paused: false, corrupt, complete: false };
      upload.current = job; refresh(); void runUpload(job);
    } catch (error) { showToast(message(error), 'error'); }
    finally { setHashing(null); }
  }
  async function resumeUpload() {
    const job = upload.current; if (!job || job.running) return;
    try {
      const status = await api<{ uploaded: number[] }>(`/files/uploads/${job.uploadId}`);
      job.states = job.states.map((_, index) => status.uploaded.includes(index) ? 'done' : 'pending');
      job.confirmed = job.states.reduce((n, state, i) => n + (state === 'done' ? Math.min(job.chunkSize!, job.size - i * job.chunkSize!) : 0), 0);
      job.active = {}; await runUpload(job);
    } catch (error) { job.error = message(error); refresh(); }
  }
  async function cancelUpload() {
    if (upload.current?.running) return;
    const meta = upload.current ?? hint;
    if (!meta) return;
    try {
      await api(`/files/uploads/${meta.uploadId}`, { method: 'DELETE' });
    } catch (error) { if ((error as ApiError).status !== 404) { showToast(message(error), 'error'); return; } }
    await clearTransfer(meta.key); upload.current = null; setHint(undefined); refresh();
  }

  async function uploadBatch(list: FileList) {
    if (batchBusy) return;
    const files = Array.from(list);
    if (files.length > 24 || files.some(file => file.size > 5 * 1024 ** 2)) { showToast('批量上传最多24个文件，每个不超过5MiB；更大文件请使用分片上传', 'error'); return; }
    setBatchBusy(true); setBatchPercent(0); setBatchResults([]);
    const form = new FormData(); files.forEach(file => form.append('files', file));
    const controller = new AbortController(); uploadControllers.current.add(controller);
    try {
      const result = await xhr('/files/batch', 'POST', form, controller.signal,
        (bytes, total) => setBatchPercent(total ? Math.min(99, bytes * 100 / total) : 0)) as { results: typeof batchResults };
      setBatchResults(result.results); setBatchPercent(100); log(`批量处理完成：${result.results.filter(x => x.ok).length}/${files.length}成功`); await load();
    } catch (error) { showToast(message(error), 'error'); }
    finally { setBatchBusy(false); uploadControllers.current.delete(controller); }
  }

  async function runDownload(job: Download) {
    if (job.running) return;
    job.running = true; job.paused = false; job.complete = false; job.error = undefined; refresh();
    try {
      const head = await fetch(`/files/${job.fileId}/download`, { method: 'HEAD', credentials: 'include' });
      if (!head.ok) throw new Error(`文件信息读取失败（${head.status}）`);
      const etag = head.headers.get('etag') ?? '';
      const total = Number(head.headers.get('content-length'));
      if (total !== job.size || (job.etag && job.etag !== etag)) {
        await clearTransfer(job.key); job.confirmed = 0; job.size = total; log('文件版本变化，已清除旧缓存并从头下载');
      }
      job.etag = etag;
      const cached = await blocks(job.key);
      const done = new Set(cached.map(part => part.index));
      job.confirmed = cached.reduce((n, part) => n + part.blob.size, 0);
      await saveTransfer({ key: job.key, kind: 'download', filename: job.filename, size: job.size, sha256: job.sha256, fileId: job.fileId, etag, received: job.confirmed });
      const slices = ranges(job.size, BLOCK);
      const pending = slices.flatMap((_, i) => done.has(i) ? [] : [i]);
      log(`下载使用${job.connections}个连接，已有${done.size}个分块缓存，剩余${pending.length}个`);
      await pool(pending.length, job.connections, async position => {
        if (job.paused) return;
        const index = pending[position]; const part = slices[index];
        for (let attempt = 0; attempt < 3; attempt++) {
          if (job.paused) return;
          const controller = new AbortController(); downloadControllers.current.add(controller);
          try {
            const response = await fetch(`/files/${job.fileId}/download`, { credentials: 'include', signal: controller.signal,
              headers: { Range: `bytes=${part.start}-${part.end}`, 'If-Range': etag } });
            if ([429, 502, 503, 504].includes(response.status)) throw new Error(`服务繁忙（${response.status}），稍后重试`);
            validateRange(response.status, response.headers.get('content-range'), part.start, part.end, job.size);
            if (response.headers.get('etag') !== etag) throw new Error('文件版本已变化，请重新开始下载');
            if (!response.body) throw new Error('浏览器不支持流式下载');
            const reader = response.body.getReader(); const pieces: Uint8Array<ArrayBuffer>[] = [];
            let received = 0;
            for (;;) {
              const { done: ended, value } = await reader.read(); if (ended) break;
              pieces.push(new Uint8Array(value)); received += value.byteLength; job.active[index] = received; refresh();
            }
            if (received !== part.end - part.start + 1) throw new Error('下载分块长度不完整');
            await saveBlock(job, index, new Blob(pieces));
            job.confirmed += received; delete job.active[index]; refresh(); return;
          } catch (error) {
            delete job.active[index];
            if (job.paused || (error as Error).name === 'AbortError') return;
            if (attempt === 2) throw error;
            await new Promise(resolve => setTimeout(resolve, 500 * 2 ** attempt));
          } finally { downloadControllers.current.delete(controller); }
        }
      });
      if (!job.paused) {
        const parts = await blocks(job.key);
        if (parts.length !== slices.length || parts.some((part, i) => part.index !== i || part.blob.size !== slices[i].end - slices[i].start + 1)) throw new Error('本地下载缓存不完整');
        const blob = new Blob(parts.map(part => part.blob));
        if (job.sha256 && await hashFile(blob) !== job.sha256) { await clearTransfer(job.key); job.confirmed = 0; throw new Error('整文件SHA-256不一致，已清除错误缓存，请重新下载'); }
        save(blob, job.filename); job.complete = true; log(`下载完成并校验通过：${job.filename}`);
      }
    } catch (error) { job.error = message(error); job.paused = true; log(job.error); }
    finally { job.running = false; refresh(); }
  }
  async function startDownload(file: Asset, connections: number) {
    if (download.current?.running) { showToast('请先暂停当前下载', 'error'); return; }
    const key = `${owner}:download:${file.id}`;
    const cached = await getTransfer(key);
    const job: Download = { key, kind: 'download', fileId: file.id, filename: file.original_filename, size: file.size_bytes, sha256: file.sha256 ?? '', etag: cached?.etag,
      active: {}, confirmed: cached?.received ?? 0, running: false, paused: false, connections, complete: false };
    download.current = job; refresh(); await runDownload(job);
  }
  function pause(kind: 'upload' | 'download') {
    const job = kind === 'upload' ? upload.current : download.current;
    if (job) job.paused = true;
    (kind === 'upload' ? uploadControllers : downloadControllers).current.forEach(c => c.abort()); refresh();
  }
  const up = upload.current; const down = download.current;
  const upPercent = up ? progress(up.confirmed, Object.values(up.active), up.size) : 0;
  const downPercent = down ? progress(down.confirmed, Object.values(down.active), down.size) : 0;
  return <>
    <header className="ws-header"><div><p className="ws-kicker">FILES</p><h1>文件中心</h1><p>分片校验与重传、批量上传、刷新后续传、实时进度和并发下载。</p></div></header>
    <section style={card}><h2>1. 大文件分片上传</h2><p>默认2MiB/片，3片并行；先增量计算整文件SHA-256。刷新后重新选择原文件，即可恢复服务端已确认的分片。</p>
      <label className="ws-button light">选择文件 / 重新选择原文件<input aria-label="分片上传文件" type="file" disabled={!!up?.running || hashing !== null} onChange={e => { const file = e.target.files?.[0]; if (file) void selectFile(file); e.target.value = ''; }} /></label>
      <label style={{ marginLeft: 12 }}><input type="checkbox" checked={corrupt} onChange={e => setCorrupt(e.target.checked)} />演示：故意损坏一个分片并观察自动重传</label>
      {hashing !== null && <Bar value={hashing} label="计算文件指纹" />}
      {hint && !up && <p>待续传：{hint.filename}（{size(hint.size)}），请选择同一原文件。<button onClick={() => void cancelUpload()}>取消旧任务</button></p>}
      {up && <div><Bar value={up.complete ? 100 : Math.min(99.9, upPercent)} label={`${up.filename} · ${size(up.confirmed)} / ${size(up.size)}`} />
        <p>{up.complete ? '服务器合并与整文件校验已完成' : `${up.states.filter(s => s === 'done').length}/${up.states.length}片已确认（100%发送不等于合并完成）`}</p>
        <div style={{ display: 'flex', gap: 3, flexWrap: 'wrap' }}>{up.states.map((state, i) => <span key={i} title={`分片${i}：${state}`} style={{ width: 10, height: 10, background: ({ pending: '#ddd', uploading: '#2463eb', done: '#228764', failed: '#ba3f4c' })[state] }} />)}</div>
        {!up.complete && <p><button disabled={!up.running || up.paused} onClick={() => pause('upload')}>暂停上传</button> <button disabled={up.running} onClick={() => void resumeUpload()}>继续上传</button> <button disabled={up.running} onClick={() => void cancelUpload()}>取消任务</button></p>}
        {up.error && <p role="alert">{up.error}</p>}</div>}
    </section>
    <section style={card}><h2>2. 小文件批量上传</h2><p>一次multipart请求包含多个files字段，逐个返回成功/失败结果。最多24个，每个5MiB。</p>
      <input aria-label="批量上传文件" type="file" multiple disabled={batchBusy} onChange={e => { if (e.target.files?.length) void uploadBatch(e.target.files); e.target.value = ''; }} />
      {(batchBusy || batchResults.length > 0) && <Bar value={batchPercent} label={batchBusy && batchPercent >= 99 ? '服务器处理中' : '批量上传'} />}
      <ul>{batchResults.map((item, i) => <li key={i}>{item.filename}：{item.ok ? '成功' : item.error}</li>)}</ul>
    </section>
    <section style={card}><h2>3. 文件列表与下载</h2><p>Range下载每个2MiB分块写入浏览器IndexedDB，暂停/刷新后仍保留；重新点击同一文件即可续传。并发下载使用4个Range连接。下载完成后本地核对整文件SHA-256。</p>
      <p>完成的下载缓存会保留以便再次保存；可点击“清除下载缓存”释放本浏览器空间。</p>
      <table style={{ width: '100%', textAlign: 'left' }}><thead><tr><th>文件名</th><th>大小</th><th>操作</th></tr></thead><tbody>{assets.map(file => <tr key={file.id}><td>{file.original_filename}</td><td>{size(file.size_bytes)}</td><td>
        <button onClick={() => void startDownload(file, 1).catch(e => showToast(message(e), 'error'))}>下载 / 续传</button> <button onClick={() => void startDownload(file, 4).catch(e => showToast(message(e), 'error'))}>4连接并发下载</button> <button disabled={!!down?.running} onClick={() => void clearTransfer(`${owner}:download:${file.id}`).then(() => { if (down?.fileId === file.id) download.current = null; refresh(); showToast('下载缓存已清除'); }).catch(e => showToast(message(e), 'error'))}>清除下载缓存</button> <button onClick={() => { if (window.confirm(`删除${file.original_filename}？`)) void api(`/files/${file.id}`, { method: 'DELETE' }).then(load).catch(e => showToast(message(e), 'error')); }}>删除</button>
      </td></tr>)}</tbody></table>
      {down && <div><Bar value={down.complete ? 100 : Math.min(99.9, downPercent)} label={`${down.filename} · 已缓存${size(down.confirmed)} / ${size(down.size)}`} />
        <p>{down.complete ? '下载完成，SHA-256校验通过' : down.paused ? '已暂停，缓存可跨刷新恢复' : '下载中 / 本地完整性校验中'}</p>
        <button disabled={!down.running || down.paused} onClick={() => pause('download')}>暂停下载</button> <button disabled={down.running} onClick={() => void runDownload(down)}>继续下载 / 再次保存</button>
        {down.error && <p role="alert">{down.error}</p>}</div>}
    </section>
    <section style={card}><h2>传输日志</h2><ol aria-label="传输日志">{logs.map((entry, i) => <li key={i}>{entry}</li>)}</ol></section>
  </>;
}
