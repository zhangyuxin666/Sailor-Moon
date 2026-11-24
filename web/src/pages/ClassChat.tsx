import { useEffect, useRef, useState, type FormEvent } from 'react';
import DataState from '../components/DataState';
import { api, useApi } from '../lib/api';
import { useAuth } from '../lib/auth';
import '../styles/class-chat.css';

type Room = { id: string; name: string };
type Message = {
  sequence: number;
  sender_id: string;
  sender_name: string;
  body: string;
  created_at: string;
};

const timeFormat = new Intl.DateTimeFormat('zh-CN', {
  month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
});

export default function ClassChat() {
  const { data, loading, error, reload } = useApi(() => api<{ rooms: Room[] }>('/api/chat/rooms'));
  const [selectedRoom, setSelectedRoom] = useState('');
  const rooms = data?.rooms ?? [];
  const room = rooms.find((item) => item.id === selectedRoom) ?? rooms[0];

  return (
    <>
      <header className="ws-header">
        <h1>班级群聊</h1>
      </header>
      <DataState loading={loading} error={error} onRetry={reload}
        empty={!!data && rooms.length === 0} emptyText="班级列表为空。请班级管理员确认你已加入班级。" />
      {room && (
        <section className="ws-panel class-chat">
          <label className="class-chat-room">
            班级
            <select value={room.id} onChange={(event) => setSelectedRoom(event.target.value)}>
              {rooms.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
            </select>
          </label>
          <Conversation key={room.id} roomId={room.id} />
        </section>
      )}
    </>
  );
}

function Conversation({ roomId }: { roomId: string }) {
  const { account } = useAuth();
  const endpoint = `/api/chat/rooms/${encodeURIComponent(roomId)}/messages`;
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(true);
  const [online, setOnline] = useState(false);
  const [historyError, setHistoryError] = useState('');
  const [draft, setDraft] = useState('');
  const [sendError, setSendError] = useState('');
  const [sending, setSending] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const retry = useRef<{ body: string; id: string } | null>(null);
  const synchronize = useRef<() => void>(() => {});
  const messageList = useRef<HTMLDivElement>(null);
  const newestSequence = messages[messages.length - 1]?.sequence;

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    let socket: WebSocket | undefined;
    let reconnect: number | undefined;
    let cursor: number | undefined;
    let pending = false;
    let syncing = false;

    async function sync() {
      pending = true;
      if (syncing || signal.aborted) return;
      syncing = true;
      try {
        while (pending && !signal.aborted) {
          pending = false;
          const incremental = cursor !== undefined;
          const url = incremental ? `${endpoint}?after=${cursor}` : endpoint;
          const { messages: batch } = await api<{ messages: Message[] }>(url, { signal });
          if (signal.aborted) return;
          cursor = batch[batch.length - 1]?.sequence ?? cursor ?? 0;
          if (batch.length) setMessages((previous) => [...previous, ...batch]);
          setHistoryError('');
          setLoading(false);
          if (incremental && batch.length === 100) pending = true;
        }
      } catch (error) {
        if (!signal.aborted) {
          setHistoryError(error instanceof Error ? error.message : '消息加载失败');
          setLoading(false);
        }
      } finally {
        syncing = false;
      }
    }

    function connect() {
      const url = new URL('/ws/chat', window.location.href);
      url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
      url.searchParams.set('classId', roomId);
      socket = new WebSocket(url);
      // WebSocket 只发更新提醒，收到后由 sync 通过 HTTP 查询消息正文。
      socket.onmessage = (event) => {
        if (event.data !== 'messages_changed' || signal.aborted) return;
        setOnline(true);
        void sync();
      };
      socket.onclose = (event) => {
        if (signal.aborted) return;
        setOnline(false);
        if (event.code !== 1008) reconnect = window.setTimeout(connect, 2500);
      };
    }

    synchronize.current = () => { void sync(); };
    void sync();
    connect();
    const refresh = window.setInterval(sync, 15000);
    return () => {
      synchronize.current = () => {};
      controller.abort();
      window.clearTimeout(reconnect);
      window.clearInterval(refresh);
      if (socket) {
        socket.onclose = null;
        if (socket.readyState === WebSocket.CONNECTING) {
          socket.onopen = () => socket?.close();
        } else {
          socket.close();
        }
      }
    };
  }, [endpoint, roomId]);

  useEffect(() => {
    const list = messageList.current;
    if (list && newestSequence !== undefined) list.scrollTop = list.scrollHeight;
  }, [newestSequence]);

  async function loadOlder() {
    if (loadingOlder || !messages.length) return;
    const list = messageList.current;
    const previousHeight = list?.scrollHeight ?? 0;
    const previousTop = list?.scrollTop ?? 0;
    setLoadingOlder(true);
    try {
      const { messages: earlier } = await api<{ messages: Message[] }>(`${endpoint}?before=${messages[0].sequence}`);
      setMessages((previous) => [...earlier, ...previous]);
      setHistoryError('');
      requestAnimationFrame(() => {
        if (list) list.scrollTop = previousTop + list.scrollHeight - previousHeight;
      });
    } catch (error) {
      setHistoryError(error instanceof Error ? error.message : '历史消息加载失败');
    } finally {
      setLoadingOlder(false);
    }
  }

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const body = draft.trim();
    if (!body || sending) return;
    const previous = retry.current;
    const id = previous?.body === body ? previous.id : Array.from(
      crypto.getRandomValues(new Uint8Array(16)),
      (byte) => byte.toString(16).padStart(2, '0'),
    ).join('');
    retry.current = { body, id };
    setSending(true);
    setSendError('');
    try {
      await api(endpoint, { method: 'POST', body: JSON.stringify({ body, client_message_id: id }) });
      retry.current = null;
      setDraft('');
      synchronize.current();
    } catch (error) {
      setSendError(error instanceof Error ? error.message : '发送失败，请重试');
    } finally {
      setSending(false);
    }
  }

  return (
    <>
      <p className="class-chat-connection" role="status">{online ? '实时连接已建立' : '正在连接，消息会自动同步'}</p>
      <DataState error={historyError || null} onRetry={() => synchronize.current()} />
      <div className="class-chat-history" ref={messageList} aria-label="聊天记录" tabIndex={0}>
        {messages[0]?.sequence > 1 && (
          <button type="button" className="ws-button light class-chat-earlier" onClick={loadOlder} disabled={loadingOlder}>
            {loadingOlder ? '正在加载…' : '查看更早消息'}
          </button>
        )}
        <DataState loading={loading} loadingText="正在读取消息…" empty={!historyError && messages.length === 0}
          emptyText="还没有消息，先发一条吧。" />
        <ul className="class-chat-messages" role="log" aria-label="班级消息">
          {messages.map((message) => (
            <li key={message.sequence} className={message.sender_id === account?.id ? 'class-chat-mine' : ''}>
              <div className="class-chat-meta">
                <strong>{message.sender_name}</strong>
                <time dateTime={message.created_at}>{timeFormat.format(new Date(message.created_at))}</time>
              </div>
              <p>{message.body}</p>
            </li>
          ))}
        </ul>
      </div>
      <form className="ws-form class-chat-compose" onSubmit={send}>
        <label>
          消息
          <textarea rows={2} maxLength={1000} value={draft} disabled={sending}
            onChange={(event) => setDraft(event.target.value)} />
        </label>
        <button type="submit" className="ws-button" disabled={sending || !draft.trim()}>
          {sending ? '发送中…' : '发送'}
        </button>
      </form>
      {sendError && <p className="class-chat-error" role="alert">{sendError}</p>}
    </>
  );
}
