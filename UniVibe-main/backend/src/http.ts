import { z } from 'zod';
import { HttpError } from './errors';

const uuid = z.string().uuid();

/** A malformed id is reported exactly like a missing one, so ids cannot be probed. */
export function idParam(value: unknown): string {
  const parsed = uuid.safeParse(value);
  if (!parsed.success) throw new HttpError(404, 'not_found', 'Not found.');
  return parsed.data;
}

/** Postgres timestamptz rendered at microsecond precision in UTC, so keyset cursors never skip or repeat rows. */
export const tsText = (col: string) => `to_char(${col} at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')`;

export interface Cursor { t: string; id: string }

export function encodeCursor(c: Cursor): string {
  return Buffer.from(JSON.stringify(c)).toString('base64url');
}

export function decodeCursor(raw: string | undefined): Cursor | null {
  if (!raw) return null;
  try {
    const c = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8')) as Cursor;
    if (typeof c.t === 'string' && typeof c.id === 'string' && !Number.isNaN(Date.parse(c.t)) && uuid.safeParse(c.id).success) {
      return c;
    }
  } catch { /* fall through */ }
  throw new HttpError(400, 'invalid_cursor', 'That page cursor is not valid.');
}

export const pageSchema = z.object({
  limit: z.coerce.number().int().min(1).max(50).default(20),
  cursor: z.string().max(300).optional(),
});

export interface Page<T> { items: T[]; nextCursor: string | null }

/** rows must be fetched with limit + 1; the extra row only proves there is another page. */
export function toPage<T extends { cursorT: string; id: string }>(rows: T[], limit: number): Page<Omit<T, 'cursorT'>> {
  const more = rows.length > limit;
  const slice = more ? rows.slice(0, limit) : rows;
  const last = slice[slice.length - 1];
  return {
    items: slice.map(({ cursorT: _drop, ...rest }) => rest as Omit<T, 'cursorT'>),
    nextCursor: more && last ? encodeCursor({ t: last.cursorT, id: last.id }) : null,
  };
}
