# Real chat on Android

When `API_BASE_URL` is set, the chat list and conversations come from the server (`RemoteChatRepository`,
`RemoteMessageRepository`). Without it the app still uses the offline mock data.

Status: **written but not yet compiled or run** (see PUSH_NOTIFICATIONS.md).

## Works now (1:1 and group chats, text)
Chat list with unread counts, last message, pinned and muted flags · open a chat · send (idempotent, a failed send gives your
text back) · reply · edit (15 minutes) · unsend · reactions · mute · archive · mark read · start a chat from a profile ·
tap a push or notification to open the chat · **forward text** to up to 5 of your chats (all targets must be yours, or nothing
is sent; the copy has no reply quote or reactions) · **pin one message** (anyone in a 1:1 chat, owner/admins in groups) ·
**clear chat** (hides history for you only, including search and the list preview) · **"Load earlier messages"** pages back
through history 50 at a time · **photos**: pick one, it uploads (resized to 1600 px, 8 MB server limit) and sends; tap any photo in
the chat to open it full screen with pinch-zoom. Your own photos open from the copy on your phone (instant, works offline);
other people's open from a temporary server link. Photos from a chat also appear in the group info photo grid.

**Live connection.** While signed in, the app keeps one WebSocket (`wss://<server>/api/v1/ws`, OkHttp) open. It signs in with
the first frame (the token is never in the URL), reconnects with exponential backoff and jitter, asks for a fresh token if the
server rejects one, and re-syncs everything on screen after each reconnect. With it: new messages, edits, unsends, reactions and
pins appear at once · **typing indicator** (sent at most every 3 s, expires by itself) · **seen ticks** (a message is Seen when the
other person has read up to it; in a group, when everyone has) · the chat list and the notification bell update by themselves.
The server stays the source of truth: an event only tells the app to look again, so a missed event cannot leave the screen wrong.
If the connection is down, chats fall back to polling every 3 seconds (20 seconds as a safety net while it is up).

## Not available yet (the app says so instead of pretending)
Voice, video and files in chat (the server accepts photos only) · several photos in one message · forwarding photos ·  a failed photo upload is not kept for retry: the notice says so and you pick the photo again ·
group info screen (name, photo, members and admin rules still use
mock data, so group chats open without those controls) · a forward that fails partway for one target (for example a blocked
person) can leave earlier targets already sent.

## Known limits of the live connection
* The server keeps live connections in one process's memory, so run a single web instance (see V1_API.md).
* Seen ticks are not a per-person privacy setting on the server: the server tells people when you have read a chat. The app's
  "read receipts" switch only hides ticks on your own screen for now.
* Delivered (double tick) is not shown; only Sent and Seen.
* Presence ("online") events arrive but the app does not show them yet.
