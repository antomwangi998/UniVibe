import type { Pool } from 'pg';
import { HttpError } from './errors';

/**
 * The campus a student picked, checked against their university. A university with campuses requires one;
 * a university without campuses accepts none. Returns null when there is nothing to store.
 */
export async function resolveCampus(pool: Pool, universityId: string, campusId: string | undefined): Promise<string | null> {
  const count = (await pool.query<{ n: number }>('select count(*)::int as n from campuses where university_id = $1', [universityId])).rows[0].n;
  if (count === 0) return null;
  if (!campusId) throw new HttpError(400, 'validation', 'Choose your campus.');
  const ok = await pool.query('select 1 from campuses where id = $1 and university_id = $2', [campusId, universityId]);
  if (!ok.rowCount) throw new HttpError(400, 'validation', 'Choose one of the campuses of your university.');
  return campusId;
}
