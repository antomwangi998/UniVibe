# Notes API (`/api/v1`)

A Note is one short text update per student that disappears by itself. It is its own data type (table `notes`), not a Story and not a post.

## Policies (the server is the only authority)
- **One active Note per student.** Publishing again replaces it in place (same id, `version` + 1).
- **Expiry:** `expires_at = database now() + NOTE_TTL_HOURS` (default 24). **Every successful publish or edit restarts the clock.**
  Every read filters on `expires_at > now()`, so an expired Note is never returned even before the hourly purge deletes it (purge removes rows expired > 1 day).
- **Length:** at most `NOTE_MAX_CHARS` (default 60) characters, counted as people count them (an emoji is one). Over the limit is a `400`, never a silent cut. Surrounding whitespace is trimmed, control characters dropped, runs of blank lines collapsed to one line break.
- **Audience** (`campus` | `followers`) is enforced inside the query on every read. A block in either direction, a suspended or unverified author, another university, or a mute by the viewer all read as "not found".
- **Live updates carry no text.** `{"type":"note:changed","userId":"…","kind":"updated"|"deleted"}` goes to the author's other devices and to connected students who may see the Note (same university, not blocked, not muted, and a follower when the audience is `followers`). The app then re-reads `GET /notes`.

## Presence (online dot and "last active")
- `users.last_seen_at` is written when a student's first device connects and when their last one disconnects.
- `GET /notes`, `GET /users/:id/note`, `GET /conversations` (`presence` beside `with`) and `GET /conversations/:id` (`members[].lastActiveAt`) return
  `{ online, lastActiveAt }`. It is `null` for anyone who turned **Show online status** off, so a private person looks the same as one never seen.
  `lastActiveAt` is `null` until the person has connected once. The app never shows "Offline": it shows a time or nothing.
- Limitation: live `presence:update` events only reach people who share a conversation with the student, so the dot in the Notes row of a
  non-contact refreshes on the next load (resume, reconnect or Note change), not instantly.

## Routes
| Route | Purpose |
|---|---|
| `GET /notes` | `{ serverTime, limits:{maxChars,ttlHours}, mine: Note\|null, items: Note[] }`. `items` = visible Notes of others, newest first (max 60). |
| `GET /users/:userId/note` | One person's active Note, or `404`. |
| `PUT /me/note` `{ text, audience? }` | Publish / edit / replace. `200` with the Note. Repeating the same request leaves one Note. 30 per hour per student. Unknown fields are rejected. |
| `DELETE /me/note` | `204`, also when there was nothing to delete. |
| `PUT` / `DELETE /users/:userId/note-mute` | Mute / unmute one person's Notes for me only. `204`. |

`Note` = `{ id, author:{id,handle,fullName,avatarUrl,studentVerified}, text, audience, version, createdAt, expiresAt }`.

## Not done yet (be aware)
- The Android app has no screen to **unmute** (the route exists), so muting is not offered in the UI yet.
- There is no server push for Notes and no "note expired" socket event: apps remove a Note when its server-set `expiresAt` passes, and re-read on reconnect.
- Reporting a Note and replying to a Note are not implemented.
- Live events use in-process memory (see the `Hub` note in `realtime.ts`): with more than one server instance they would not reach every socket.
