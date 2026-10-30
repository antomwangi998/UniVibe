import type { FastifyRequest } from 'fastify';
import type { Pool } from 'pg';
import { z } from 'zod';
import { HttpError } from './errors';
import type { ImageProcessor } from './imaging';
import type { Storage } from './storage';

/** What the social routes need from the app: the database, the signed-in student, and rate limiting. */
export interface RouteContext {
  pool: Pool;
  requireUser: (req: FastifyRequest) => Promise<SignedInUser>;
  tooMany: (key: string, max: number, windowSeconds: number) => void;
  /** Private photo and video storage. Undefined when the server has none configured. */
  storage: Storage | undefined;
  /** Re-encodes uploaded photos. Without it photos are kept exactly as uploaded (tests only). */
  images?: ImageProcessor;
  /** The signed-in student's own profile, as the API returns it. */
  userView: (id: string) => Promise<object>;
}

export type SignedInUser = { id: string; university_id: string; full_name: string; handle: string };

/** Columns describing a student, from a query that joins `users a` and `universities uni`. */
export const AUTHOR_COLS =
  'a.id as a_id, a.full_name as a_name, a.handle as a_handle, a.course as a_course, a.year_of_study as a_year, ' +
  'a.interests as a_interests, a.student_verified as a_verified, a.has_photo as a_photo, uni.name as a_university, uni.id as a_university_code, ' +
  "(select c.name from campuses c where c.id = a.campus_id) as a_campus, coalesce(uni.short_name, uni.name) as a_university_short, " +
  "(select m.object_key from media m where m.id = a.avatar_media_id and m.status = 'ready') as a_avatar_key, " +
  'profile_look_for(a.id) as a_look';

export type AuthorCols = {
  a_id: string; a_name: string; a_handle: string; a_course: string; a_year: string;
  a_interests: string[]; a_verified: boolean; a_photo: boolean; a_university: string; a_university_code: string; a_campus?: string | null; a_university_short?: string;
  a_avatar_key: string | null;
  a_look?: { theme?: string; accent?: string; frame?: string; badge?: string } | null;
  /** Filled in by [signAuthors] right before the row is sent. */
  a_avatar_url?: string | null;
};

const AVATAR_LINK_SECONDS = 900;

/** Gives every row a short-lived link to its author's profile photo (null when they have none). Call before [authorJson]. */
export async function signAuthors<T extends AuthorCols>(storage: Storage | undefined, rows: T[]): Promise<T[]> {
  await Promise.all(rows.map(async (r) => {
    r.a_avatar_url = r.a_avatar_key && storage ? await storage.signedUrl(r.a_avatar_key, AVATAR_LINK_SECONDS) : null;
  }));
  return rows;
}

export function authorJson(r: AuthorCols) {
  return {
    id: r.a_id,
    fullName: r.a_name,
    handle: r.a_handle,
    course: r.a_course,
    yearOfStudy: r.a_year,
    interests: r.a_interests,
    studentVerified: r.a_verified,
    hasPhoto: r.a_photo,
    avatarUrl: r.a_avatar_url ?? null,
    // Premium profile look, only while its owner's Premium is live (see profile_look_for in migration 059).
    look: r.a_look ?? null,
    universityName: r.a_university,
    // The university's short id (for example "ku"), shown as a small badge next to the name.
    universityCode: r.a_university_code,
    // Short label for the campus badge ("KU"), and the campus inside the university when it has several ("Main Campus").
    universityShortName: r.a_university_short ?? r.a_university,
    campusName: r.a_campus ?? null,
  };
}

const uuid = z.string().uuid();

/** A malformed id is simply "not found", never a database error. */
export function paramId(req: FastifyRequest, name = 'id'): string {
  const parsed = uuid.safeParse((req.params as Record<string, string>)[name]);
  if (!parsed.success) throw new HttpError(404, 'not_found', 'Not found.');
  return parsed.data;
}

export const isUniqueViolation = (e: unknown) => (e as { code?: string } | null)?.code === '23505';
