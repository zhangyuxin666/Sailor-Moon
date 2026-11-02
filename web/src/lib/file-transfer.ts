/** Range 和进度的纯函数，前后端协议测试共同覆盖边界。 */
export function ranges(size: number, blockSize: number): Array<{ start: number; end: number }> {
  if (!Number.isSafeInteger(size) || size < 1 || !Number.isSafeInteger(blockSize) || blockSize < 1) throw new Error('无效文件大小');
  return Array.from({ length: Math.ceil(size / blockSize) }, (_, i) => ({ start: i * blockSize, end: Math.min(size - 1, (i + 1) * blockSize - 1) }));
}

export function progress(confirmed: number, active: number[], total: number): number {
  return total > 0 ? Math.min(100, Math.max(0, (confirmed + active.reduce((a, b) => a + b, 0)) * 100 / total)) : 0;
}

export function validateRange(status: number, contentRange: string | null, start: number, end: number, total: number): void {
  if (status !== 206 || contentRange !== `bytes ${start}-${end}/${total}`) {
    throw new Error('续传响应范围不一致，请重新下载，避免拼接错误文件');
  }
}

export async function pool(count: number, concurrency: number, work: (index: number) => Promise<void>): Promise<void> {
  let next = 0;
  let failed: unknown;
  await Promise.all(Array.from({ length: Math.min(count, concurrency) }, async () => {
    while (!failed && next < count) {
      const index = next++;
      try { await work(index); } catch (error) { failed = error; }
    }
  }));
  if (failed) throw failed;
}
