/**
 * A small in-memory cache with an expiry time and a size limit. When it is full the entry used longest ago leaves
 * first. Nothing here is shared between server processes, and nothing survives a restart: it only stops the same
 * address being fetched again and again within a short time.
 *
 * A miss is `undefined`, so `null` can be stored on purpose ("we looked and there is nothing").
 */
export class TtlCache<V> {
  private readonly items = new Map<string, { value: V; expires: number; weight: number }>();
  private total = 0;

  constructor(
    private readonly options: { ttlMs: number; maxEntries: number; /** Sum of weights allowed; default: no limit. */ maxWeight?: number; now?: () => number },
    /** How much one value counts against `maxWeight` (for example its size in bytes). */
    private readonly weigh: (value: V) => number = () => 0,
  ) {}

  private now(): number { return (this.options.now ?? Date.now)(); }

  get(key: string): V | undefined {
    const entry = this.items.get(key);
    if (!entry) return undefined;
    if (entry.expires <= this.now()) { this.remove(key); return undefined; }
    // Move to the end: it was just used.
    this.items.delete(key);
    this.items.set(key, entry);
    return entry.value;
  }

  /** `ttlMs` overrides the default for this one entry (a failure is remembered for less time than a success). */
  set(key: string, value: V, ttlMs = this.options.ttlMs): void {
    this.remove(key);
    const weight = this.weigh(value);
    // A value that could never fit is simply not cached.
    if (this.options.maxWeight !== undefined && weight > this.options.maxWeight) return;
    this.items.set(key, { value, expires: this.now() + ttlMs, weight });
    this.total += weight;
    while (this.items.size > this.options.maxEntries || (this.options.maxWeight !== undefined && this.total > this.options.maxWeight)) {
      const oldest = this.items.keys().next();
      if (oldest.done) break;
      this.remove(oldest.value);
    }
  }

  clear(): void { this.items.clear(); this.total = 0; }

  get size(): number { return this.items.size; }

  private remove(key: string): void {
    const entry = this.items.get(key);
    if (!entry) return;
    this.total -= entry.weight;
    this.items.delete(key);
  }
}

/** Runs `run` once for a key however many callers ask at the same time; they all get that one result. */
export function shareInFlight<V>(running: Map<string, Promise<V>>, key: string, run: () => Promise<V>): Promise<V> {
  const existing = running.get(key);
  if (existing) return existing;
  const started = run().finally(() => { running.delete(key); });
  running.set(key, started);
  return started;
}
