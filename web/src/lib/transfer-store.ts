/** 以账号隔离缓存；下载片段与已确认字节数在同一IndexedDB事务中提交。 */
export interface TransferRecord {
  key: string;
  kind: 'upload' | 'download';
  filename: string;
  size: number;
  sha256: string;
  uploadId?: string;
  chunkSize?: number;
  fileId?: string;
  etag?: string;
  received?: number;
}
interface Block { key: string; transfer: string; index: number; blob: Blob }

let database: Promise<IDBDatabase> | undefined;
function db(): Promise<IDBDatabase> {
  database ??= new Promise((resolve, reject) => {
    const request = indexedDB.open('sailor-moon-transfers', 1);
    request.onupgradeneeded = () => {
      request.result.createObjectStore('transfers', { keyPath: 'key' });
      request.result.createObjectStore('blocks', { keyPath: 'key' }).createIndex('transfer', 'transfer');
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => { database = undefined; reject(request.error); };
  });
  return database;
}
function result<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
}
function committed(tx: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => { tx.oncomplete = () => resolve(); tx.onabort = () => reject(tx.error ?? new Error('缓存写入失败或空间不足')); tx.onerror = () => reject(tx.error); });
}
export async function getTransfer(key: string): Promise<TransferRecord | undefined> {
  return result((await db()).transaction('transfers').objectStore('transfers').get(key));
}
export async function saveTransfer(record: TransferRecord): Promise<void> {
  const tx = (await db()).transaction('transfers', 'readwrite');
  const done = committed(tx);
  tx.objectStore('transfers').put(record);
  await done;
}
export async function blocks(key: string): Promise<Block[]> {
  const list: Block[] = await result((await db()).transaction('blocks').objectStore('blocks').index('transfer').getAll(key));
  return list.sort((a, b) => a.index - b.index);
}
export async function saveBlock(record: TransferRecord, index: number, blob: Blob): Promise<void> {
  const tx = (await db()).transaction(['transfers', 'blocks'], 'readwrite');
  const done = committed(tx);
  const key = `${record.key}:${index}`;
  const blockStore = tx.objectStore('blocks');
  const meta = tx.objectStore('transfers');
  const lookup = meta.get(record.key);
  lookup.onsuccess = () => {
    const previous = blockStore.get(key);
    previous.onsuccess = () => {
      const value = (lookup.result ?? record) as TransferRecord;
      value.received = (value.received ?? 0) + blob.size - ((previous.result as Block | undefined)?.blob.size ?? 0);
      blockStore.put({ key, transfer: record.key, index, blob } satisfies Block);
      meta.put(value);
    };
  };
  await done;
}
export async function clearTransfer(key: string): Promise<void> {
  const tx = (await db()).transaction(['transfers', 'blocks'], 'readwrite');
  const done = committed(tx);
  tx.objectStore('transfers').delete(key);
  const cursor = tx.objectStore('blocks').index('transfer').openCursor(IDBKeyRange.only(key));
  cursor.onsuccess = () => { if (cursor.result) { cursor.result.delete(); cursor.result.continue(); } };
  await done;
}
