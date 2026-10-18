/**
 * Sliding-window rate limiter kept in memory. Fine for one server instance (Render free plan);
 * move it to Redis or Postgres before running several instances.
 */
export class RateLimiter {
  private hits = new Map<string, number[]>();

  constructor(private readonly now: () => number = Date.now) {}

  /** Records an attempt. Returns 0 if allowed, otherwise the seconds to wait. */
  hit(key: string, max: number, windowSeconds: number): number {
    const t = this.now();
    const windowMs = windowSeconds * 1000;
    const recent = (this.hits.get(key) ?? []).filter((time) => t - time < windowMs);
    if (recent.length >= max) {
      this.hits.set(key, recent);
      return Math.max(1, Math.ceil((recent[0] + windowMs - t) / 1000));
    }
    recent.push(t);
    this.hits.set(key, recent);
    if (this.hits.size > 20000) this.sweep(windowMs);
    return 0;
  }

  private sweep(windowMs: number): void {
    const t = this.now();
    for (const [key, times] of this.hits) {
      if (times.length === 0 || t - times[times.length - 1] > windowMs) this.hits.delete(key);
    }
  }
}
