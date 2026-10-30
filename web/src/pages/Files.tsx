/**
 * 文件中心 /files
 * 演示：大文件分片上传（校验 / 重传 / 断点续传）、小文件批量上传、
 * 带进度条与断点续传的下载、多线程并发下载。
 */
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { api } from '../lib/api';
import { useToast } from '../components/Toast';

const CHUNK_CONCURRENCY = 3;
const PARALLEL_PARTS = 4;

type ChunkState = 'pending' | 'uploading' | 'done' | 'failed';

interface UploadInit {
  upload_id: string | null;
  chunk_size?: number;
  total_chunks?: number;
  uploaded?: number[];
  instant?: boolean;
  file_id?: string;
}

interface UploadStatus {
  upload_id: string;
  chunk_size: number;
  total_chunks: number;
  uploaded: number[];
  status: string;
}

interface FileItem {
  id: string;
  original_filename: string;
  size_bytes: number;
  content_type?: string;
  sha256?: string;
  created_at: string;
}

interface UploadJob {
  file: File;
  uploadId: string;
  chunkSize: number;
  totalChunks: number;
  states: ChunkState[];
  uploadedBytes: number;
  paused: boolean;
  running: boolean;
  corruptOnce: boolean;
  notice: string;
  error: string | null;
  fileId: string | null;
}

interface DownloadJob {
  id: string;
  filename: string;
  total: number;
  received: number;
  paused: boolean;
  running: boolean;
  error: string | null;
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

async function sha256Hex(buffer: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('');
}

async function putChunk(uploadId: string, index: number, body: ArrayBuffer, sha: string): Promise<void> {
  const response = await fetch(`/files/uploads/${uploadId}/chunks/${index}`, {
    method: 'PUT',
    credentials: 'include',
    headers: { 'Content-Type': 'application/octet-stream', 'X-Chunk-Sha256': sha },
    body,
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => ({}))) as { detail?: string; code?: string };
    const error = new Error(payload.detail || `分片上传失败（${response.status}）`);
    (error as Error & { code?: string }).code = payload.code;
    throw error;
  }
}

function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

const cardStyle: CSSProperties = {
  border: '1px solid #e5e5e2',
  borderRadius: 12,
  padding: 18,
  marginBottom: 18,
  background: '#fff',
};

const barStyle: CSSProperties = {
  height: 8,
  background: '#f0f0ed',
  borderRadius: 999,
  overflow: 'hidden',
  marginTop: 8,
};

export default function Files() {
  const { showToast } = useToast();
  const [files, setFiles] = useState<FileItem[]>([]);
  const [loading, setLoading] = useState(true);

  const jobRef = useRef<UploadJob | null>(null);
  const [, setJobVersion] = useState(0);
  const job = jobRef.current;
  const renderJob = useCallback(() => setJobVersion((value) => value + 1), []);

  const [batchProgress, setBatchProgress] = useState(0);
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchResults, setBatchResults] = useState<Array<{ filename: string; ok: boolean; error?: string }>>([]);

  const downloadRef = useRef<DownloadJob | null>(null);
  const [, setDownloadVersion] = useState(0);
  const download = downloadRef.current;
  const renderDownload = useCallback(() => setDownloadVersion((value) => value + 1), []);
  const [parallelInfo, setParallelInfo] = useState('');

  const loadFiles = useCallback(async () => {
    try {
      const data = await api<{ files: FileItem[] }>('/files/mine');
      setFiles(data.files ?? []);
    } catch (error) {
      showToast(error instanceof Error ? error.message : '加载文件失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    void loadFiles();
  }, [loadFiles]);

  // ----------------------------------------------------------- 大文件分片

  const uploadChunk = useCallback(async (current: UploadJob, index: number) => {
    const start = index * current.chunkSize;
    const end = Math.min(current.file.size, start + current.chunkSize);
    const buffer = await current.file.slice(start, end).arrayBuffer();
    const digest = await sha256Hex(buffer);

    if (current.corruptOnce) {
      // 演示“分片损坏”：内容被篡改，与声明的 SHA-256 不一致，服务端拒绝并要求重传
      current.corruptOnce = false;
      const view = new Uint8Array(buffer);
      view[0] = view[0] ^ 0xff;
    }

    try {
      await putChunk(current.uploadId, index, buffer, digest);
    } catch (error) {
      const code = (error as Error & { code?: string }).code;
      if (code === 'CHUNK_CHECKSUM_MISMATCH') {
        const retry = await current.file.slice(start, end).arrayBuffer();
        const retryDigest = await sha256Hex(retry);
        await putChunk(current.uploadId, index, retry, retryDigest);
        current.notice = `第 ${index + 1} 个分片校验失败，已自动重传成功`;
      } else {
        throw error;
      }
    }
    current.states[index] = 'done';
    current.uploadedBytes += end - start;
  }, []);

  const finishUpload = useCallback(
    async (current: UploadJob) => {
      try {
        const result = await api<{ file_id: string }>(`/files/uploads/${current.uploadId}/complete`, {
          method: 'POST',
        });
        current.fileId = result.file_id;
        current.notice = `合并完成，文件 ID：${result.file_id}`;
        showToast('分片上传完成');
        await loadFiles();
      } catch (error) {
        current.error = error instanceof Error ? error.message : '合并失败';
        showToast(current.error, 'error');
      } finally {
        renderJob();
      }
    },
    [loadFiles, renderJob, showToast],
  );

  const runUpload = useCallback(
    async (current: UploadJob) => {
      if (current.running || current.fileId) return;
      current.running = true;
      current.error = null;
      renderJob();
      const inFlight = new Set<Promise<void>>();
      try {
        while (!current.paused) {
          while (inFlight.size < CHUNK_CONCURRENCY) {
            const index = current.states.findIndex((state) => state === 'pending' || state === 'failed');
            if (index < 0) break;
            current.states[index] = 'uploading';
            const task: Promise<void> = uploadChunk(current, index)
              .catch((error: unknown) => {
                current.states[index] = 'failed';
                current.error = error instanceof Error ? error.message : '分片上传失败';
              })
              .finally(() => {
                inFlight.delete(task);
                renderJob();
              });
            inFlight.add(task);
          }
          if (inFlight.size === 0) break;
          await Promise.race(inFlight);
        }
        await Promise.all([...inFlight]);
      } finally {
        current.running = false;
        renderJob();
      }
      if (!current.paused && current.states.every((state) => state === 'done') && !current.fileId) {
        await finishUpload(current);
      }
    },
    [finishUpload, renderJob, uploadChunk],
  );

  const startUpload = useCallback(
    async (file: File) => {
      try {
        const init = await api<UploadInit>('/files/uploads', {
          method: 'POST',
          body: JSON.stringify({
            filename: file.name,
            content_type: file.type || 'application/octet-stream',
            size: file.size,
          }),
        });
        if (init.instant && init.file_id) {
          showToast('秒传成功：服务端已有相同文件');
          await loadFiles();
          return;
        }
        const totalChunks = init.total_chunks ?? Math.ceil(file.size / (init.chunk_size ?? 1));
        const next: UploadJob = {
          file,
          uploadId: init.upload_id as string,
          chunkSize: init.chunk_size as number,
          totalChunks,
          states: Array.from({ length: totalChunks }, () => 'pending' as ChunkState),
          uploadedBytes: 0,
          paused: false,
          running: false,
          corruptOnce: false,
          notice: '',
          error: null,
          fileId: null,
        };
        jobRef.current = next;
        renderJob();
        void runUpload(next);
      } catch (error) {
        showToast(error instanceof Error ? error.message : '初始化上传失败', 'error');
      }
    },
    [loadFiles, renderJob, runUpload, showToast],
  );

  const togglePause = useCallback(async () => {
    const current = jobRef.current;
    if (!current) return;
    if (!current.paused) {
      current.paused = true;
      current.notice = '已暂停，可以刷新页面后再点“继续上传”验证断点续传';
      renderJob();
      return;
    }
    try {
      const status = await api<UploadStatus>(`/files/uploads/${current.uploadId}`);
      const uploaded = new Set(status.uploaded);
      current.states = current.states.map((state, index) =>
        uploaded.has(index) ? 'done' : state === 'uploading' ? 'pending' : state,
      );
      current.uploadedBytes = Array.from(uploaded).reduce((sum, index) => {
        const start = index * current.chunkSize;
        return sum + Math.min(current.chunkSize, current.file.size - start);
      }, 0);
      current.paused = false;
      current.notice = `服务端已有 ${uploaded.size}/${current.totalChunks} 个分片，继续上传剩余部分`;
      renderJob();
      for (let wait = 0; wait < 40 && current.running; wait++) {
        await new Promise((resolve) => setTimeout(resolve, 50));
      }
      void runUpload(current);
    } catch (error) {
      showToast(error instanceof Error ? error.message : '查询续传状态失败', 'error');
    }
  }, [renderJob, runUpload, showToast]);

  const cancelUpload = useCallback(async () => {
    const current = jobRef.current;
    if (!current) return;
    current.paused = true;
    try {
      await api(`/files/uploads/${current.uploadId}`, { method: 'DELETE' });
    } catch {
      // 已完成或已取消时忽略
    }
    jobRef.current = null;
    renderJob();
    showToast('已取消上传');
  }, [renderJob, showToast]);

  // ----------------------------------------------------------- 批量上传

  const uploadBatch = useCallback(
    async (list: FileList) => {
      const form = new FormData();
      Array.from(list).forEach((file) => form.append('files', file));
      setBatchBusy(true);
      setBatchProgress(0);
      setBatchResults([]);
      await new Promise<void>((resolve) => {
        const request = new XMLHttpRequest();
        request.open('POST', '/files/batch');
        request.withCredentials = true;
        request.upload.onprogress = (event) => {
          if (event.lengthComputable) setBatchProgress(Math.round((event.loaded / event.total) * 100));
        };
        request.onload = () => {
          try {
            const payload = JSON.parse(request.responseText) as {
              results?: Array<{ filename: string; ok: boolean; error?: string }>;
            };
            setBatchResults(payload.results ?? []);
            showToast(`批量上传完成：${payload.results?.length ?? 0} 个文件`);
          } catch {
            showToast('批量上传返回异常', 'error');
          }
          resolve();
        };
        request.onerror = () => {
          showToast('批量上传失败', 'error');
          resolve();
        };
        request.send(form);
      });
      setBatchBusy(false);
      setBatchProgress(100);
      await loadFiles();
    },
    [loadFiles, showToast],
  );

  // ----------------------------------------------------------- 下载

  const runDownload = useCallback(
    async (current: DownloadJob) => {
      if (current.running) return;
      if (current.total > 0 && current.received >= current.total) return;
      current.running = true;
      current.paused = false;
      current.error = null;
      renderDownload();

      const chunks: ArrayBuffer[] = [];
      const offset = current.received;
      try {
        const response = await fetch(`/files/${current.id}/download`, {
          credentials: 'include',
          headers: offset > 0 ? { Range: `bytes=${offset}-` } : {},
        });
        if (!response.ok) throw new Error(`下载失败（${response.status}）`);
        const range = response.headers.get('content-range');
        if (range) {
          const match = /\/(\d+)$/.exec(range);
          if (match) current.total = Number(match[1]);
        } else if (!current.total) {
          current.total = Number(response.headers.get('content-length') || 0);
        }
        if (!response.body) throw new Error('当前浏览器不支持流式下载');

        const reader = response.body.getReader();
        for (;;) {
          if (current.paused) {
            await reader.cancel();
            break;
          }
          const { done, value } = await reader.read();
          if (done) break;
          if (value) {
            chunks.push(new Uint8Array(value).buffer as ArrayBuffer);
            current.received += value.length;
            renderDownload();
          }
        }
        if (!current.paused) {
          saveBlob(new Blob(chunks), current.filename);
          current.received = current.total;
          showToast('下载完成');
        }
      } catch (error) {
        if ((error as Error).name !== 'AbortError') {
          current.error = error instanceof Error ? error.message : '下载失败';
          showToast(current.error, 'error');
        }
      } finally {
        current.running = false;
        renderDownload();
      }
    },
    [renderDownload, showToast],
  );

  const startDownload = useCallback(
    (file: FileItem) => {
      const next: DownloadJob = {
        id: file.id,
        filename: file.original_filename,
        total: file.size_bytes,
        received: 0,
        paused: false,
        running: false,
        error: null,
      };
      downloadRef.current = next;
      renderDownload();
      void runDownload(next);
    },
    [renderDownload, runDownload],
  );

  const pauseDownload = useCallback(() => {
    const current = downloadRef.current;
    if (!current) return;
    current.paused = true;
    renderDownload();
  }, [renderDownload]);

  const resumeDownload = useCallback(() => {
    const current = downloadRef.current;
    if (!current) return;
    current.paused = false;
    void (async () => {
      for (let wait = 0; wait < 40 && current.running; wait++) {
        await new Promise((resolve) => setTimeout(resolve, 50));
      }
      await runDownload(current);
    })();
  }, [runDownload]);

  const parallelDownload = useCallback(
    async (file: FileItem) => {
      setParallelInfo('正在以 4 个并发连接下载…');
      try {
        const head = await fetch(`/files/${file.id}/download`, { method: 'HEAD', credentials: 'include' });
        const size = Number(head.headers.get('content-length') || file.size_bytes);
        const partSize = Math.ceil(size / PARALLEL_PARTS);
        const started = performance.now();
        const parts = await Promise.all(
          Array.from({ length: PARALLEL_PARTS }, async (_, index) => {
            const start = index * partSize;
            const end = Math.min(size - 1, start + partSize - 1);
            const response = await fetch(`/files/${file.id}/download`, {
              credentials: 'include',
              headers: { Range: `bytes=${start}-${end}` },
            });
            if (response.status !== 206) throw new Error('服务端未返回 206，Range 未生效');
            return response.arrayBuffer();
          }),
        );
        const elapsed = (performance.now() - started) / 1000;
        saveBlob(new Blob(parts), file.original_filename);
        const speed = elapsed > 0 ? size / 1024 / 1024 / elapsed : 0;
        setParallelInfo(
          `4 线程并发下载完成：${formatSize(size)}，用时 ${elapsed.toFixed(2)}s，约 ${speed.toFixed(1)} MB/s`,
        );
      } catch (error) {
        setParallelInfo('');
        showToast(error instanceof Error ? error.message : '并发下载失败', 'error');
      }
    },
    [showToast],
  );

  const removeFile = useCallback(
    async (file: FileItem) => {
      if (!window.confirm(`确定删除 ${file.original_filename}？`)) return;
      try {
        await api(`/files/${file.id}`, { method: 'DELETE' });
        await loadFiles();
        showToast('已删除');
      } catch (error) {
        showToast(error instanceof Error ? error.message : '删除失败', 'error');
      }
    },
    [loadFiles, showToast],
  );

  const doneChunks = job ? job.states.filter((state) => state === 'done').length : 0;
  const chunkPercent = job && job.totalChunks > 0 ? Math.round((doneChunks / job.totalChunks) * 100) : 0;
  const downloadPercent =
    download && download.total > 0 ? Math.min(100, Math.round((download.received / download.total) * 100)) : 0;

  return (
    <>
      <header className="ws-header">
        <div>
          <p className="ws-kicker">FILES</p>
          <h1>文件中心</h1>
          <p>大文件分片上传、断点续传、批量上传与多线程下载的完整演示。</p>
        </div>
      </header>

      <section style={cardStyle}>
        <h2 style={{ margin: 0, fontSize: 16 }}>1. 大文件分片上传</h2>
        <p style={{ color: '#666', fontSize: 13, margin: '6px 0 12px' }}>
          默认 2 MB 分片、并发 3 片；每片带 SHA-256 校验，损坏自动重传；暂停后可继续，服务端按已收分片续传。
        </p>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
          <label className="ws-button light" style={{ cursor: 'pointer' }}>
            选择大文件
            <input
              type="file"
              style={{ display: 'none' }}
              onChange={(event) => {
                const picked = event.target.files?.[0];
                if (picked) void startUpload(picked);
                event.target.value = '';
              }}
            />
          </label>
          {job && !job.fileId && (
            <>
              <button className="ws-button" onClick={() => void togglePause()}>
                {job.paused ? '继续上传（断点续传）' : '暂停'}
              </button>
              <button className="ws-button light" onClick={() => void cancelUpload()}>
                取消
              </button>
              <button
                className="ws-button danger"
                onClick={() => {
                  if (jobRef.current) {
                    jobRef.current.corruptOnce = true;
                    jobRef.current.notice = '下一个分片会被故意改坏，用来演示服务端拒绝并触发重传';
                    renderJob();
                  }
                }}
              >
                模拟分片损坏
              </button>
            </>
          )}
        </div>

        {job && (
          <div style={{ marginTop: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}>
              <span>
                {job.file.name} · {formatSize(job.file.size)} · {job.totalChunks} 片
              </span>
              <span>{chunkPercent}%</span>
            </div>
            <div style={barStyle}>
              <div style={{ width: `${chunkPercent}%`, height: '100%', background: '#171717', transition: 'width .2s' }} />
            </div>
            <div style={{ marginTop: 8, display: 'flex', gap: 3, flexWrap: 'wrap' }}>
              {job.states.map((state, index) => (
                <span
                  key={index}
                  title={`分片 ${index + 1}: ${state}`}
                  style={{
                    width: 10,
                    height: 10,
                    borderRadius: 2,
                    background:
                      state === 'done'
                        ? '#228764'
                        : state === 'uploading'
                          ? '#2463eb'
                          : state === 'failed'
                            ? '#ba3f4c'
                            : '#e5e5e2',
                  }}
                />
              ))}
            </div>
            {job.notice && <p style={{ fontSize: 12, color: '#228764', margin: '8px 0 0' }}>{job.notice}</p>}
            {job.error && <p style={{ fontSize: 12, color: '#ba3f4c', margin: '8px 0 0' }}>{job.error}</p>}
            {job.fileId && <p style={{ fontSize: 12, color: '#666', margin: '8px 0 0' }}>文件 ID：{job.fileId}</p>}
          </div>
        )}
      </section>

      <section style={cardStyle}>
        <h2 style={{ margin: 0, fontSize: 16 }}>2. 小文件批量上传</h2>
        <p style={{ color: '#666', fontSize: 13, margin: '6px 0 12px' }}>
          一次选择多个小文件，通过一个 multipart 请求上传，服务端逐个处理并返回每个文件的结果。
        </p>
        <label className="ws-button light" style={{ cursor: 'pointer' }}>
          选择多个文件
          <input
            type="file"
            multiple
            style={{ display: 'none' }}
            onChange={(event) => {
              if (event.target.files?.length) void uploadBatch(event.target.files);
              event.target.value = '';
            }}
          />
        </label>
        {batchBusy && (
          <div style={{ marginTop: 12 }}>
            <div style={{ fontSize: 13 }}>上传中 {batchProgress}%</div>
            <div style={barStyle}>
              <div style={{ width: `${batchProgress}%`, height: '100%', background: '#2463eb', transition: 'width .2s' }} />
            </div>
          </div>
        )}
        {batchResults.length > 0 && (
          <ul style={{ margin: '12px 0 0', paddingLeft: 18, fontSize: 13 }}>
            {batchResults.map((item, index) => (
              <li key={index} style={{ color: item.ok ? '#228764' : '#ba3f4c' }}>
                {item.filename}：{item.ok ? '成功' : item.error}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section style={cardStyle}>
        <h2 style={{ margin: 0, fontSize: 16 }}>3. 文件列表与下载</h2>
        <p style={{ color: '#666', fontSize: 13, margin: '6px 0 12px' }}>
          下载支持 Range 断点续传；“并发下载”会用 4 个 Range 连接同时拉取同一个文件。
        </p>
        {loading ? (
          <p style={{ fontSize: 13 }}>加载中…</p>
        ) : files.length === 0 ? (
          <p style={{ fontSize: 13, color: '#666' }}>还没有文件，先上传一个试试。</p>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ textAlign: 'left', color: '#666' }}>
                <th style={{ padding: '6px 4px' }}>文件名</th>
                <th style={{ padding: '6px 4px' }}>大小</th>
                <th style={{ padding: '6px 4px' }}>上传时间</th>
                <th style={{ padding: '6px 4px' }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {files.map((file) => (
                <tr key={file.id} style={{ borderTop: '1px solid #f0f0ed' }}>
                  <td style={{ padding: '8px 4px' }}>{file.original_filename}</td>
                  <td style={{ padding: '8px 4px' }}>{formatSize(file.size_bytes)}</td>
                  <td style={{ padding: '8px 4px', color: '#666' }}>
                    {file.created_at.slice(0, 19).replace('T', ' ')}
                  </td>
                  <td style={{ padding: '8px 4px' }}>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      <button className="ws-button light" onClick={() => startDownload(file)}>
                        下载
                      </button>
                      <button className="ws-button light" onClick={() => void parallelDownload(file)}>
                        并发下载
                      </button>
                      <button className="ws-button danger" onClick={() => void removeFile(file)}>
                        删除
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {download && (
          <div style={{ marginTop: 16, borderTop: '1px solid #f0f0ed', paddingTop: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13 }}>
              <span>
                正在下载 {download.filename}（{formatSize(download.received)} / {formatSize(download.total)}）
              </span>
              <span>{downloadPercent}%</span>
            </div>
            <div style={barStyle}>
              <div style={{ width: `${downloadPercent}%`, height: '100%', background: '#2463eb', transition: 'width .2s' }} />
            </div>
            <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
              {download.running && !download.paused && (
                <button className="ws-button light" onClick={pauseDownload}>
                  暂停
                </button>
              )}
              {(download.paused || download.error) && download.received < download.total && (
                <button className="ws-button" onClick={resumeDownload}>
                  继续下载（从 {formatSize(download.received)} 处续传）
                </button>
              )}
            </div>
            {download.error && <p style={{ fontSize: 12, color: '#ba3f4c', margin: '8px 0 0' }}>{download.error}</p>}
          </div>
        )}
        {parallelInfo && <p style={{ fontSize: 12, color: '#228764', margin: '10px 0 0' }}>{parallelInfo}</p>}
      </section>
    </>
  );
}
