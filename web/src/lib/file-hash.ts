import { createSHA256 } from 'hash-wasm';

/** 增量哈希：固定2MiB缓冲，不把2GiB整文件读入ArrayBuffer。 */
export async function hashFile(blob: Blob, onProgress: (percent: number) => void = () => {}): Promise<string> {
  const hasher = await createSHA256();
  hasher.init();
  for (let offset = 0; offset < blob.size; offset += 2 * 1024 * 1024) {
    const chunk = await blob.slice(offset, offset + 2 * 1024 * 1024).arrayBuffer();
    hasher.update(new Uint8Array(chunk));
    onProgress(Math.min(100, (offset + chunk.byteLength) * 100 / blob.size));
  }
  return hasher.digest();
}
