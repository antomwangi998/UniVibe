# UniVibe API v1 (new modules)

Posts, comments, follows, profiles and media keep their existing **unversioned** routes (`/posts`, `/students/:id`,
`/me`, `/media/uploads`, ...) because the Android app already uses them. They were extended in place (see "Changes to
existing routes"). Everything below is new and lives under `/api/v1`.

Conventions: bearer access token on every route; JSON; timestamps are ISO-8601 UTC; errors are
`{ "error": "<code>", "message": "..." }`; ids that are malformed or not visible to you answer `404`;
lists use `?limit=1..50&cursor=<opaque>` and return `{ items, nextCursor }` (`nextCursor` is `null` on the last page).
Messages page with `?before=<seq>` and return `{ items, nextBefore }`.

## Endpoints

| Area | Routes |
|---|---|
| Privacy / blocking | `GET/PUT /me/privacy` (discoverable, showOnline, readReceipts, typingIndicators, messagePermission; readReceipts off = others are not told how far you have read; typingIndicators off = others are never told you are typing) · `POST/DELETE /users/:id/block` · `GET /me/blocked-users` |
| Sessions | `GET /me/sessions` → `{currentSessionId, items:[{id, deviceLabel, createdAt, lastActiveAt, current}]}` · `DELETE /me/sessions/:id` · `POST /me/sessions/revoke-others` → `{revoked}`. A session is one login (refresh-token family). Login and verify-email accept an optional `deviceName` label. No IP or location is stored. Ending a session also stops its access token immediately. |
| Account status | `GET /me/account-status` → `{standing: good\|suspended, restrictions, removedContent, campusVerification, notices, appeals:{supported:false}}`. Readable while suspended (nothing else is). Moderator `note` is never returned; notices carry only the `userMessage` a moderator wrote for the person. |
| Notifications | `GET /notifications[?unread=true]` · `GET /notifications/unread-count` · `POST /notifications/:id/read` · `POST /notifications/read-all` · `DELETE /notifications/:id` · `GET/PUT /me/notification-preferences` |
| Discover | `GET /discover[?course&faculty&yearOfStudy&interest]` · `POST /discover/:id/pass` · `POST /discover/:id/like` (a follow) · `GET/PUT /me/discover/preferences` |
| Conversations | `POST/GET /conversations` · `GET /conversations/:id` · `GET/POST /conversations/:id/messages` · `POST /conversations/:id/read` · `POST /conversations/:id/members` · `DELETE /conversations/:id/members/:userId` · `PATCH/DELETE /messages/:id` |
| Events | `POST/GET /events` · `GET/PATCH/DELETE /events/:id` · `POST /events/:id/cancel` · `POST/DELETE /events/:id/register` · `GET /events/:id/attendees` · `POST /events/:id/check-in` · `GET /me/events` |
| Chat extras | `PATCH /conversations/:id/settings` `{mutedUntil?, archived?, pinned?}` · `POST /conversations/:id/mark-unread` · `POST /conversations/:id/delivered {upToSeq}` · `PUT/DELETE /messages/:id/reaction` · `GET /messages/search?q&conversationId?&before?` · `GET /conversations?archived=true` · `GET /conversations/:id/messages?after=<seq>` |
| Push devices | `PUT /me/devices {installationId, fcmToken}` · `DELETE /me/devices/:installationId` (call before logging out) |
| Ops (admin) | `GET /admin/jobs` (queue depth, failures by kind, oldest waiting job) |
| Search | `GET /search` · `/search/users` · `/search/posts` · `/search/events` (`q` 2-80 chars) |
| Reports / moderation | `POST /reports` · `GET /moderation/reports` · `POST /moderation/reports/:id/review` · `POST /moderation/reports/:id/action` · `GET /moderation/audit-logs` (admin) |
| Ops | `GET /health` · `GET /ready` (database check) |

Status codes used: 200, 201, 204, 400 (validation, unknown fields), 401, 403 (`forbidden`, `cannot_message`,
`account_suspended`, `attendees_hidden`), 404, 409 (`event_full`, `event_closed`, ...), 410, 413, 422, 429, 500.

## Rules that apply everywhere
* Same university only (profiles, Discover, messaging, events unless an event is public, moderation).
* A block works in both directions, looks like "not found", removes follows, and clears notifications between the pair.
  It is enforced in posts, comments, likes, saves, follower lists, profiles, Discover, conversations, events, search, reports.
* Suspended accounts (`users.suspended_until` in the future) are rejected on every route, including open WebSockets,
  and are invisible to other students.
* Notifications never contain post, comment or message text; recipients are chosen by the server only.

## Messaging
* Direct conversations are unique per pair (`direct_key`). Groups have owner / admin / member roles, max 256 people.
* A send is stored before anyone is told. `clientId` makes retries idempotent (same message returned, nothing duplicated).
* Edit window 15 minutes (sender only). Delete = unsend for everyone: text and attachments removed, a tombstone stays.
* `messagePermission`: `everyone`, `followers` (only people who follow you), `nobody`. The refusal is always the same
  `403 cannot_message`, so a sender cannot tell a block from a setting.
* Not end-to-end encrypted. Message text is stored in the database in readable form.

### WebSocket: `GET /api/v1/ws`
First frame must be `{"type":"auth","token":"<access token>"}` within 5 s (tokens are not accepted in the URL).
The token is re-checked on every frame; an expired or suspended session is closed with code 4401 and the client
reconnects with a fresh token. Frames are limited to 16 KB and 200 per minute.

Client -> server: `message:send {conversationId, content?, clientId?, replyToId?, mediaIds?}` (answered by
`message:ack`), `message:read {conversationId, upToSeq?}`, `typing:start|typing:stop {conversationId}`, `ping`.
Server -> client: `auth:ok`, `message:new`, `message:updated`, `message:deleted`, `message:delivered`, `message:read`,
`typing:update`, `presence:update`, `conversation:updated`, `error {code}`.
Typing is memory-only, rate limited to once per 2 s, expires after 6 s and is cleared on disconnect. Presence supports
several devices per user (offline only after the last one closes), respects `showOnline`, and is sent only to people
you share a conversation with.

## Background jobs (started by `server.ts`)
Event reminders (every 5 min, one per person per event) and event retention (events are deleted 7 days after they end).
Both are idempotent. Upstream's pending-upload sweep is unchanged.

## Roles
`users.role` is `user`, `moderator` or `admin`. There is no endpoint that grants roles; set it in the database.
Moderators only see and act on their own university's reports. Admins cannot be suspended through moderation;
moderators only by an admin. Every report review and moderation action is written to `audit_logs`.

## Changes to existing routes (additive; response shapes unchanged)
`posts.ts`: removed posts/comments, suspended authors and blocked pairs are hidden (including comment counts); likes,
comments and replies create notifications in the same transaction; deleting a post or comment deletes its notifications.
`people.ts`: blocked/suspended students are "not found", blocked pairs cannot follow, follows notify, follower and
following lists hide blocked people. `media.ts`: purposes `message` and `event` are accepted. `app.ts`: suspension check,
`/ready`, `X-Request-Id`.

## Known limitations / not implemented
* Realtime is in-process memory: run **one instance**, or add a shared bus before scaling out.
* Not implemented: private accounts and follow requests, groups/communities, push notifications (device tokens, FCM,
  outbox), a student-verification review workflow (the status columns and a sync trigger exist, no review endpoints),
  account data export, moderator escalation and warnings, image thumbnails/variants, OpenAPI document, metrics.
* Existing list routes (`/posts`, comments, followers) are not cursor-paginated: changing them would break the Android client.
* Reports of private messages show the reported message text to moderators of that university (that is the purpose of a report).


## Push, jobs and sync (stage 11)

**Queue.** The project has no Redis or Bull, so jobs live in Postgres (`jobs` table, migration 011) instead of adding a second system.
A job is written in the same transaction as the thing it describes (a stored message, a stored notification), which is the
transactional outbox: a crash after commit cannot lose the announcement. Workers claim rows with `FOR UPDATE SKIP LOCKED`;
a job whose worker died is claimed again when its 60 s lock expires. Retries use exponential backoff with jitter
(about 5 s, 10 s, 20 s ... capped at 15 min), at most 6 attempts, 30 s timeout per attempt; permanent errors fail at once.
Payloads hold ids only. Kinds: `notification.deliver` (live event + push for a stored notification) and `push.message`
(one per message and recipient, deduped by `messageId:recipientId`). `server.ts` starts one in-process worker and a
6-hourly purge (finished jobs, stale and revoked device tokens, read notifications older than 180 days).

**Push rules** (checked again when the job runs, so a mute, block or read after queueing is honoured): the recipient must still
be in the conversation, not have muted it, not have read the message, not have blocked or been blocked by the sender, and have
`pushEnabled` and the matching switch (`pushMessages`, `pushSocial`, `pushEvents`) on. `verification` notices are always allowed.
Storing in the feed (`follows`, `likes`, ... ), pushing (`push*`) and the on-device alert (Android channels) are three separate decisions.
A muted conversation produces no feed entry and no push; the messages themselves are unaffected.
FCM payloads are **data-only** and carry ids and generic labels (`kind`, `conversationId`, `messageId`, `notificationId`, `group`),
never message text or tokens; the app builds the notification and fetches content from the API when it is opened.
`push_status` on a notification is `accepted` only when FCM accepted the request; that is not proof a phone showed it.
A token FCM reports as unregistered is revoked immediately; a token that moves to another account is revoked for the old one.

**New WebSocket events:** `message:reaction {conversationId, messageId, userId, reaction|null, reactions}`,
`notification:created {notification:{id,type,actorId,entityType,entityId,createdAt}, unreadCount}`,
`notification:unread {unreadCount}` (sent to a person's other devices when they read or delete). On connect, everything
stored for the person is marked delivered and senders get `message:delivered`.

**Reconnect recovery.** The database is the source of truth. After reconnecting a client calls
`GET /conversations/:id/messages?after=<last seq it holds>` (oldest first, `nextAfter` while more remain),
`GET /notifications/unread-count`, and `GET /conversations`. Events carry `seq` / ids so replays can be ignored.

**Configuration.** `FCM_SERVICE_ACCOUNT_JSON` (server secret, optional). Without it push is skipped and everything else works.
Run **one** web instance: live events are in process memory, and the worker publishes through the same process.

**Still not implemented (be aware):** forwarding, group description/avatar and system messages, audio/video/file attachments
(only images are accepted as message media), per-user message deletion ("delete for me"), scheduled notifications,
aggregated/grouped notification rows, Redis/Socket bus for multiple instances, metrics export (only `/admin/jobs`),
message search is `ILIKE` (fine at campus scale; add `pg_trgm` if it gets slow).

## Chat list additions (messaging UI upgrade)

All additive; older clients ignore them.

`GET /conversations` items now also carry:

- `memberCount`: number of members.
- `members`: up to six *other* members of a group as `{ id, name }` (empty for direct chats). Used for typing labels ("Jane is typing…"), collage avatars and naming untitled groups.
- `lastMessage.mediaCount`: ready photos on the last message (0 for deleted messages).
- `lastMessage.senderName`: the sender's name, for "Jane: …" previews.
- `lastMessage.delivery`: only when the last message is the caller's own: `"sent" | "delivered" | "read"`, from the other members' delivery and read cursors (a group counts once every member has it). `null` otherwise.

`GET /conversations/:id` members now include `lastDeliveredSeq` next to `lastReadSeq`.

Already supported and now used by the app: `GET /conversations?archived=true`, `PUT /conversations/:id/settings { archived: false }` (unarchive), `{ pinned }`, `POST /conversations/:id/mark-unread` (sets a flag only; the next `/read` clears it).

Message `media` items now also carry `width` and `height` (pixels, or `null` if unknown) so clients can reserve the photo's shape before it loads.

## Groups and voice messages (migration 017)

- `GET /conversations/:id` and the list: groups carry `description`, `photoUrl`, `createdBy` and `permissions { onlyAdminsCanMessage, onlyAdminsCanEditInfo, onlyAdminsCanAddMembers }`.
- `PUT|PATCH /conversations/:id/info { title?, description?, photoMediaId? }`: admins only unless the owner opened editing. The photo is an image uploaded with `purpose: "group"`; `null` removes it. The old picture is deleted.
- `PUT|PATCH /conversations/:id/permissions`: owner or admin. `PUT|PATCH /conversations/:id/members/:userId/role { role: "admin" | "member" }`: owner only.
- `POST /conversations/:id/report { reason, details? }`: once per person; stored in `conversation_reports`.
- Sending is refused (403) for plain members while `onlyAdminsCanMessage` is on. Adding people follows `onlyAdminsCanAddMembers`.
- Voice messages: `POST /media/uploads { kind: "audio", purpose: "message", contentType: "audio/mp4" | "audio/aac" | "audio/webm" | "audio/ogg", size <= 5 MB, durationMs <= 300000 }`, then attach the media id to a message. A voice message has no other attachments. Message `media` items carry `durationMs`; the list's `lastMessage.audioCount` says the last message was a voice message.

Upload progress is client-side only (bytes sent to the storage link); the API did not change.
