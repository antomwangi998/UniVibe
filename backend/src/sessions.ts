import type { Pool } from 'pg';

/** True while the login has at least one refresh token that has not been revoked (logout, revoke, or theft detection). */
export async function sessionIsLive(pool: Pool, sessionId: string): Promise<boolean> {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if (!uuid.test(sessionId)) return false;
  const { rowCount } = await pool.query('select 1 from refresh_tokens where family_id = $1 and revoked_at is null limit 1', [sessionId]);
  return (rowCount ?? 0) > 0;
}
