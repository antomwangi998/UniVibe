/**
 * SQL building blocks for "may this viewer see that?". Every v1 query that returns another student's data goes
 * through these, so the rules live in one place. Placeholders (e.g. "$1") are passed in by the caller.
 *
 * Rules:
 *  - Students only see people from their own university (profiles, follows, discovery, messaging, events).
 *  - A block in either direction makes both sides invisible to each other everywhere, and looks like "not found".
 *  - Suspended and deactivated accounts disappear from other students' views.
 */

export const blockedBetween = (a: string, b: string) =>
  `exists (select 1 from blocks bk where (bk.blocker_id = ${a} and bk.blocked_id = ${b}) or (bk.blocker_id = ${b} and bk.blocked_id = ${a}))`;

/** `follower` follows `following`. (Upstream's column is followee_id.) */
export const acceptedFollow = (follower: string, following: string) =>
  `exists (select 1 from follows fl where fl.follower_id = ${follower} and fl.followee_id = ${following})`;

/** Not suspended by a moderator and not deactivated by the person. (The name predates deactivation.) */
export const notSuspended = (userAlias: string) =>
  `((${userAlias}.suspended_until is null or ${userAlias}.suspended_until <= now()) and ${userAlias}.deactivated_at is null)`;

/**
 * Campus bridge. A "scope" says how far a list reaches: `home` (the viewer's own university), `all` (every
 * university) or one university id. Routes turn the raw query value into a single string with [resolveScope] and
 * filter with [inScope]; `*` means "every university".
 */
export const ALL_CAMPUSES = '*';

export function resolveScope(raw: string | undefined, homeUniversityId: string, fallback: 'home' | 'all' = 'home'): string {
  const v = raw ?? fallback;
  if (v === 'home') return homeUniversityId;
  if (v === 'all') return ALL_CAMPUSES;
  return v;
}

/** The author/owner row (alias) belongs to the university the scope names. `ref` is a placeholder holding the resolved scope. */
export const inScope = (alias: string, ref: string) => `(${ref}::text = '*' or ${alias}.university_id = ${ref}::text)`;

/** A person of another university may be found only when they have not opted out (default: found). Same university always passes. */
export const crossCampusOk = (alias: string, viewerUni: string) =>
  `(${alias}.university_id = ${viewerUni} or coalesce((select xps.show_other_campuses from privacy_settings xps where xps.user_id = ${alias}.id), true))`;

/** A user row (alias `alias`) the viewer may see: verified, not blocked, not suspended, and from this university or an open campus. */
export function userVisible(alias: string, viewer: string, viewerUni: string): string {
  return `(${alias}.id = ${viewer} or (${crossCampusOk(alias, viewerUni)} and ${alias}.email_verified_at is not null and ${notSuspended(alias)} and not ${blockedBetween(viewer, `${alias}.id`)}))`;
}

/** The stricter, same-university-only rule, for places that stay inside one campus (community invites). */
export function userVisibleHome(alias: string, viewer: string, viewerUni: string): string {
  return `(${alias}.id = ${viewer} or (${alias}.university_id = ${viewerUni} and ${alias}.email_verified_at is not null and ${notSuspended(alias)} and not ${blockedBetween(viewer, `${alias}.id`)}))`;
}
