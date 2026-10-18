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
through history 50 at a time.

New messages arrive by **polling every 3 seconds while a chat is open** (this also refreshes edits, unsends, reactions and the
pinned message). The server already supports a live WebSocket; moving the app to it is the next step.

## Not available yet (the app says so instead of pretending)
Photos, voice, video and files in chat (the server accepts photos; the app side is not built) · forwarding photos ·
typing indicator and "seen" ticks (need the WebSocket) · group info screen (name, photo, members and admin rules still use
mock data, so group chats open without those controls) · a forward that fails partway for one target (for example a blocked
person) can leave earlier targets already sent.
