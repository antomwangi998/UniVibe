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
| Privacy / blocking | `GET/PUT /me/privacy` (discoverable, showOnline, messagePermission) · `POST/DELETE /users/:id/block` · `GET /me/blocked-users` |
| Notifications | `GET /notifications[?unread=true]` · `GET /notifications/unread-count` · `POST /notifications/:id/read` · `POST /notifications/read-all` · `DELETE /notifications/:id` · `GET/PUT /me/notification-preferences` |
| Discover | `GET /discover[?course&faculty&yearOfStudy&interest]` · `POST /discover/:id/pass` · `POST /discover/:id/like` (a follow) · `GET/PUT /me/discover/preferences` |
| Conversations | `POST/GET /conversations` · `GET /conversations/:id` · `GET/POST /conversations/:id/messages` · `POST /conversations/:id/read` · `POST /conversations/:id/members` · `DELETE /conversations/:id/members/:userId` · `PATCH/DELETE /messages/:id` |
| Events | `POST/GET /events` · `GET/PATCH/DELETE /events/:id` · `POST /events/:id/cancel` · `POST/DELETE /events/:id/register` · `GET /events/:id/attendees` · `POST /events/:id/check-in` · `GET /me/events` |
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
