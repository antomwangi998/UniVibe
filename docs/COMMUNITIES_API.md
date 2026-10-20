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
| `POST /communities/:id/delete` (same as DELETE; for clients that cannot send a DELETE body) | owner |
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

## Added in migration 023
- **Discover:** `discoverAudience()` lets posts of public, active communities into For You, Trending, Photos, Videos and topic pages (never private, archived or suspended ones). Each post still passes `visible()`. A post appears once, as the same post id.
- **Mute:** `PUT/DELETE /communities/:id/mute` hides a community from the viewer's Discover and Home (works for non-members). Hiding one post reuses `POST /discover/feedback {kind:"not_interested"}`, which now also applies to Home for community posts.
- **Suggestions:** `GET /communities/suggested` ranks by interest match and real posts and posters in the last 14 days, max 2 per category, excludes joined, muted and pending. `reason` is `interests`, `active` or null, never "trending".
- **Announcements:** `POST /posts {communityId, announcement:true}` (staff only, 3 per day per community). A background job `community.announce` notifies members in pages of 200 (cap 5000), skipping muted members and the author, honoring the `communities` switch, idempotent on retry. Type `community_announcement`.
- **Invitation expiry:** hourly sweep in `server.ts`; reads also ignore expired invitations.

## Android (wired to the routes above)
- `RemoteCommunityRepository` implements the existing `CommunityRepository` against these routes; `AppContainer` uses it whenever a server is configured (the mock stays for the offline demo only).
- `CommunityMembershipStore` is the single source of truth for membership. `CommunityMembershipButton` reads and drives it, and is used on the community list, the community page, and every community post in Home, Discover, profiles and search. Join shows a spinner and changes only when the server confirms; failure restores the previous state with a Retry; repeated taps send one request. Server answers and the `community:membership` socket event update all screens at once; the store is cleared on sign-out.
- Post cards show a compact `PostCommunityHeader` (community, public/private, member count, membership control) above the unchanged author row.
- The composer's community picker now publishes with `communityId`. Notification types map to titles and open the community or post.

## Search, notification levels and offline (round 3)
- **Search:** `GET /search` now also returns `communities` (5), and `GET /search/communities?q=&cursor=&limit=` pages through them. Same visibility rules as browsing: public, discoverable private, or ones you belong to; each result carries your `membership` state. Private posts and member lists are never part of search; post hits only come from `visible()`.
- **Per-community notification level** (`PATCH /communities/:id/membership {notificationLevel}`) is now enforced in `notify()` for reactions and comments on community posts: `all` and `highlights` get everything; `mentions` (the default for members) gets comments and replies but not likes; `announcements` and `muted` get neither. Administrative notices (role change, removal) and join/invitation notices ignore the level. Campus posts are unaffected. Announcements skip muted members.
- **Android:** Discover has a Communities tab (server-ranked suggestions with an honest reason: "Matches your interests" or "Active recently", plus Browse all); search shows a Communities section; both use the shared `CommunityCard` and `CommunityMembershipButton`.
- **Offline:** `CommunityCachePolicy` keeps private community content off the phone's disk: posts of private communities and their comments, member lists, join requests and invitations are never saved, and community posts whose visibility is unknown (search hits) are dropped from saved copies. Saved copies from before this policy are wiped once (cache policy version 2). While showing saved copies the membership button is disabled with "You're offline" instead of failing on tap. Joins and leaves are never queued: the server must confirm them.

## Not built yet
- Android: avatar and cover upload in the create form, invitation inbox and invite UI, per-community notification-level picker, announcement toggle in the composer, moderator post-removal UI, Compose UI tests.
- Backend: mention notifications for community posts, digests, a separate administrator tier, post-approval queue, Redis caching, Prometheus metrics.
- Migration numbering: `022_communities.sql` and `022_google_identity.sql` share a prefix. The migrator tracks full file names, so both apply; do not rename either once deployed.
