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

New messages arrive by **polling every 3 seconds while a chat is open** (this also refreshes edits, unsends, reactions and the
pinned message). The server already supports a live WebSocket; moving the app to it is the next step.

## Not available yet (the app says so instead of pretending)
Voice, video and files in chat (the server accepts photos only) · several photos in one message · forwarding photos ·  a failed photo upload is not kept for retry: the notice says so and you pick the photo again ·
typing indicator and "seen" ticks (need the WebSocket) · group info screen (name, photo, members and admin rules still use
mock data, so group chats open without those controls) · a forward that fails partway for one target (for example a blocked
person) can leave earlier targets already sent.
