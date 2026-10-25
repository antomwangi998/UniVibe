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

/** A user row (alias `alias`) the viewer may see: same university, verified, not blocked, not suspended. */
export function userVisible(alias: string, viewer: string, viewerUni: string): string {
  return `(${alias}.id = ${viewer} or (${alias}.university_id = ${viewerUni} and ${alias}.email_verified_at is not null and ${notSuspended(alias)} and not ${blockedBetween(viewer, `${alias}.id`)}))`;
}
