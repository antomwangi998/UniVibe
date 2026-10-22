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

## Discussion channels (migration 037)
Code: `backend/src/modules/community_channels.ts` (routes), `channel_access.ts` (pure permission and message-shape rules, unit-tested in `test/channel-access.test.ts`). Integration tests: `test/community-channels.integration.test.ts`.

### Model
- Channel types: `text`, `announcement`, `qa`, `resources`, `media`. Each has real behaviour: announcements are staff-only and need a title; Q&A questions need a title and answers can be accepted; resources need a link or file; media posts need an attachment. The type cannot be changed after creation.
- Channel settings (kept few on purpose): `viewRole` members|staff, `postRole` members|staff, `locked` (read-only for members), `repliesEnabled`, `allowAttachments`, archived. Staff = owner and moderators; there is no separate administrator tier yet.
- Channel messages are their own rows, not Home Feed posts. They reuse: the media pipeline (upload with purpose `message`, photos and documents), the chat reaction vocabulary (heart, like, laugh, fire, sad; several different reactions per person, each kind once), `/reports`, the audit log, the job queue and `notify()`.
- Every community gets `announcements` and `general` (backfilled by the migration, created on community create).
- Channel content is **members only**, whatever the community's visibility. Non-members get 403 `not_a_member` on the channel list (404 for private communities) and 404 on every channel-level route, so a channel's existence is never confirmed.

### Routes (under `/api/v1`)
| Route | Who |
|---|---|
| `GET /communities/:id/channels` groups, visible channels, unread, pins, my permissions | active member |
| `POST /communities/:id/channels`, `PATCH .../channels/:cid` (rename, settings, group, position, `archived`) | staff |
| `DELETE .../channels/:cid` | owner only (moderators archive) |
| `PUT /communities/:id/channel-order {groups, channels:[{id,groupId}]}` | staff |
| `POST/PATCH/DELETE /communities/:id/channel-groups[/:gid]` | staff |
| `GET .../channels/:cid/messages?limit&cursor` newest first; `?afterSeq=N` oldest-first catch-up (max 100, `hasMore`) | may view |
| `POST .../messages {title?, content, clientId?, replyToId?, mediaIds?}` (idempotent per `clientId`) | `send` or `reply` |
| `PATCH/DELETE .../messages/:mid` | author edits; author or staff deletes |
| `GET .../messages/:mid/thread` root plus paged replies | may view |
| `PUT/DELETE .../messages/:mid/reactions/:reaction` | `react` |
| `PUT/DELETE .../messages/:mid/pin`, `GET .../pins` (max 20) | staff / may view |
| `PUT/DELETE .../messages/:mid/accepted {answerId}` | Q&A: question author or staff |
| `POST .../read {upToSeq?}` | may view |
| `GET .../search?q&authorId&from&to&hasAttachments=true&pinned=true&cursor` | may view |

Every request re-reads the caller's membership and role from the database. Nothing is trusted from the client. Permission flags in responses come from the same function that enforces them.

### Behaviour worth knowing
- Message numbers (`seq`) are per channel, assigned under the channel row lock, so `afterSeq` can never skip a late commit. `createdAt` is taken under the same lock.
- Deleting keeps the row (tombstone): content, title, pin and files are erased, replies keep pointing at it and show "deleted". Files are queued in `storage_garbage`. Platform removal (`remove_content` on a `channel_message` report) sets status `removed` and is reversible.
- Timelines of `qa` and `announcement` channels list top-level items only; answers and comments are fetched with the thread route. Answers belong to the question (no answer-to-answer).
- Blocked pairs do not see each other's channel messages (history, thread, pins, search, reply previews, live events).
- Announcement-channel posts enqueue `channel.announce`, which notifies active, unmuted members (type `community_channel_announcement`, entity `channel_message`, metadata `communityId, channelId, messageId`, honours the `communities` preference).

### Real time
Same WebSocket and `Hub` as chat. The hub is per user, not room based, so there is no "join room" step to bypass: on every event the server computes who may read the channel *at that moment* from the database and sends only to those connected people. Events: `channel:message`, `channel:message:updated` (edit, pin, accepted answer, reply count), `channel:message:deleted`, `channel:reaction {messageId,userId,reaction,active,reactions}`, `channel:changed {communityId,channelId|null,kind}` (ids only; re-read the list). Message payloads are viewer-neutral: the app derives "mine" from `userId` and its own state. Sockets are fire-and-forget; after a reconnect call `afterSeq` for new messages and refetch the visible page to pick up edits, pins and deletions.

### Not built yet (deliberately)
- Android: no channel settings screen beyond lock/archive and "New channel" (no rename, group editing, reordering or per-channel permission editor yet, although the server supports them), no document attachments in the composer (photos only; documents display fine), no accepted-answer control in the timeline (it is in the thread). The app reads `community_channel_announcement` notifications and opens the community.
- Polls in channels (today's polls are single-choice and tied to `posts`), link previews in channel messages, typing and presence in channels, mentions, slow mode, per-member channel mutes, an administrator tier, per-channel role overrides.
- Surfacing channel announcements in Home Feed or the Posts tab. Posts already have `is_announcement`; surfacing a channel announcement there needs a canonical reference (not a copy) and a product decision on which mechanism wins.
- Multi-instance realtime: `Hub` is process memory (existing limitation).

### Android (this change)
- `data/model/ChannelModels.kt` (models and the pure list logic), `data/repository/ChannelRepository.kt` (REST and parsing; `UnavailableChannelRepository` for offline demo mode), `ChatSocket.kt` (new `ChatEvent.Channel*` events), `features/communities/ChannelViewModels.kt` and `ChannelScreens.kt`.
- The community page has a **Discussions** tab (members only): channels by group with unread counts; staff get **New channel**. Tapping opens the conversation (`communities/{id}/channels/{cid}`), and a thread opens at `.../threads/{messageId}`.
- Conversation: newest at the bottom, older pages load as you scroll up (list anchored at the bottom, so the position holds), messages show author, role badge, time and "edited", reply previews, reactions, attachments and pins. Send is optimistic with visible Sending / Failed / Retry states; retries reuse the same `clientId`, so a retry can never double-post. A read-only composer says why. Realtime events are merged by message id; on reconnect the app fetches everything after its last message number and refreshes the newest page. If the server stops listing the channel, refuses with 404/not_a_member/suspended, or the membership event says the student is out, the screen stops accepting input and shows "You can't open this channel".
