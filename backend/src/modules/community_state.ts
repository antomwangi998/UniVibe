import type { PoolClient } from 'pg';
import { HttpError } from '../errors';

type Q = Pick<PoolClient, 'query'>;

/** What the viewer's membership button should show. One definition, used by every surface that renders a community. */
export type JoinState = 'joined' | 'join' | 'request' | 'pending' | 'invited' | 'unavailable';

export interface JoinInput {
  status: string;
  visibility: string;
  policy: string;
  /** The viewer's active role, or null when they are not an active member. */
  role: string | null;
  suspended: boolean;
  pending: boolean;
  invited: boolean;
}

export function joinState(i: JoinInput): JoinState {
  if (i.role) return 'joined';
  // A suspended member, or a community that is archived, suspended or deleted, offers no membership action.
  if (i.suspended || i.status !== 'active') return 'unavailable';
  if (i.invited) return 'invited';
  if (i.pending) return 'pending';
  if (i.policy === 'open') return 'join';
  if (i.policy === 'approval') return 'request';
  return 'unavailable'; // invite-only and no invitation
}

/**
 * Posts that may appear in Discover (trending, photos, videos, topics, for-you): campus posts, plus posts of PUBLIC,
 * ACTIVE communities. Private, archived and suspended community posts never qualify. Callers still apply `visible()`.
 */
export const discoverAudience = (p = 'p') =>
  `(${p}.audience in ('campus', 'all') or (${p}.audience = 'community' and exists ` +
  `(select 1 from communities dc where dc.id = ${p}.community_id and dc.visibility = 'public' and dc.status = 'active')))`;

/** Hides posts from communities this viewer muted. $1 = viewer. */
export const communityNotMuted = (p = 'p') =>
  `not exists (select 1 from community_mutes cmu where cmu.user_id = $1 and cmu.community_id = ${p}.community_id)`;

export const isStaff = (role: string | null | undefined) => role === 'owner' || role === 'moderator';

/**
 * Gate for creating a post inside a community. Runs inside the post's transaction, with the community row locked
 * `for share` so it cannot be archived or deleted between the check and the insert.
 * Outsiders get "not found" for private communities, so their existence is not confirmed.
 */
export async function assertMayPostInCommunity(client: Q, userId: string, universityId: string, communityId: string): Promise<{ role: string | null }> {
  const { rows } = await client.query<{
    status: string; visibility: string; post_permission: string; role: string | null; member_status: string | null;
  }>(
    `select c.status, c.visibility, c.post_permission, m.role, m.status as member_status
       from communities c left join community_members m on m.community_id = c.id and m.user_id = $1
      where c.id = $2 and c.university_id = $3 and c.status <> 'deleted'
      for share of c`,
    [userId, communityId, universityId],
  );
  const c = rows[0];
  if (!c || (c.visibility === 'private' && !c.member_status)) throw new HttpError(404, 'not_found', 'That community is not available.');
  if (c.status !== 'active') throw new HttpError(403, 'community_unavailable', 'This community is not accepting new posts.');
  if (c.member_status !== 'active') throw new HttpError(403, 'not_a_member', 'Join this community to post in it.');
  if (c.post_permission === 'staff' && !isStaff(c.role)) throw new HttpError(403, 'staff_only', 'Only moderators can post in this community.');
  return { role: c.role };
}
