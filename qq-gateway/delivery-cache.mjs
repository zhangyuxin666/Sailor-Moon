export class IdempotentDeliveryCache {
  constructor({ ttlMs = 30 * 60 * 1000, maxSize = 5000 } = {}) {
    this.ttlMs = ttlMs;
    this.maxSize = maxSize;
    this.entries = new Map();
  }

  async run(key, action) {
    const now = Date.now();
    for (const [cachedKey, cached] of this.entries) {
      if (now - cached.createdAt > this.ttlMs) this.entries.delete(cachedKey);
    }
    const cached = this.entries.get(key);
    if (cached) return { result: await cached.promise, repeated: true };

    const promise = Promise.resolve().then(action);
    this.entries.set(key, { createdAt: now, promise });
    if (this.entries.size > this.maxSize) {
      const oldest = this.entries.keys().next().value;
      this.entries.delete(oldest);
    }
    try {
      return { result: await promise, repeated: false };
    } catch (error) {
      this.entries.delete(key);
      throw error;
    }
  }
}
