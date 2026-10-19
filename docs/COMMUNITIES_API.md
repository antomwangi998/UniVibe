# Communities: backend contract (implemented)

Migration `022_communities.sql`. Code: `backend/src/modules/communities.ts`, `community_state.ts`; touched `posts.ts`, `notify.ts`, `delivery.ts`, `notifications.ts`, `moderation.ts`, `media.ts`, `imaging.ts`, `app.ts`. All routes under `/api/v1`, except posts (unversioned, existing).

## Model
- Visibility (`public` | `private`) = who can read posts. Membership policy (`open` | `approval` | `invite_only`) = how people join. `discoverable` = whether a private community's profile (name, description, count) is shown to non-members. Public may be open/approval; private may be approval/invite_only.
- Roles: `owner` (exactly one, DB-enforced), `moderator`, `member`. Member status `active` | `suspended`. Community status `active` | `archived` (read-only) | `suspended` (platform action, hidden) | `deleted` (soft).
- Community posts are rows in `posts` with `audience='community'` and `community_id`. `visible()` in `posts.ts` is the single gate used by Home, search, comments, reports and Discover lookups.

## Routes
| Route | Who |
|---|---|
| `POST /communities` (`clientKey` makes retries idempotent; quota 5 owned) | any student |
| `GET /communities?q&category&scope=all\|joined\|public&cursor` | any student, visibility-filtered |
| `GET /communities/:id` | profile-visible |
| `PATCH /communities/:id` (name immutable), `POST /archive`, `DELETE` (needs `confirmName`) | owner |
| `GET/PUT/DELETE/PATCH /communities/:id/membership` (state, join/request, leave/cancel, notification level) | self |
| `GET /communities/:id/requests`, `POST /requests/:rid/approve\|reject` | owner, moderator |
| `POST /communities/:id/invitations`, `DELETE /invitations/:iid` | staff (or members if `invitePermission=members`) |
| `GET /community-invitations`, `POST /community-invitations/:id/accept\|decline` | invitee |
| `GET /communities/:id/members` (members only if private) | |
| `PATCH /members/:uid/role` (owner), `PATCH /members/:uid/status`, `DELETE /members/:uid` | owner / moderator (members only) |
| `POST /communities/:id/transfer-ownership` (`userId`, `confirmName`) | owner |
| `GET /communities/:id/posts` (cursor) , `DELETE /communities/:id/posts/:postId` | members / staff |
| `POST /posts` with `communityId` | active member, honoring `postPermission` |
| `POST /moderation/communities/:id/status`, `/reassign-owner` | platform moderator / admin (recovery path) |
| `POST /reports` with `targetType:"community"` | existing queue |

Post JSON gains `community: {id,name,slug,visibility,memberCount,avatarUrl,membership}` where `membership` is one of `joined | join | request | pending | invited | unavailable`, computed by one function (`joinState`) so every surface agrees.

## Notifications
Types: `community_join_request`, `community_join_approved`, `community_join_declined`, `community_invitation` (optional, controlled by `communities` preference; push uses `push_social`), `community_role_changed`, `community_member_removed` (administrative, always delivered). They use the existing `notify()` -> `notification.deliver` job -> WebSocket + FCM path. Payloads carry ids only. Approvals and declines are sent without an actor so a decline never names a moderator. WebSocket event `community:membership {communityId, membership, memberCount}` syncs the person's other open apps.

## Recovery and lifecycle
- Owner cannot leave (409). Transfer, or delete. Account deletion passes owned communities to the longest-serving moderator, then member; with nobody left the community closes.
- Admin recovery: `POST /moderation/communities/:id/reassign-owner`, audited.
- Deleting is soft: posts vanish because deleted communities are unreadable; pending requests and invitations are closed.

## Not built yet
Discover integration (community posts in For You/Photos/Videos, community cards), community feed ranking/diversity, hide-post / show-fewer, announcements, mention/comment/reaction community notifications, per-community "highlights" semantics, invitation-expiry job (expiry is enforced lazily on read), digests, a separate administrator tier, post-approval queue, caching, metrics, Android wiring to these endpoints.
