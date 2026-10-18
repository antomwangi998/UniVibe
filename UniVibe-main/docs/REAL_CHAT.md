# Real chat on Android

When `API_BASE_URL` is set, the chat list and conversations come from the server (`RemoteChatRepository`,
`RemoteMessageRepository`). Without it the app still uses the offline mock data.

Status: **written but not yet compiled or run** (see PUSH_NOTIFICATIONS.md).

## Works now (1:1 and group chats, text)
Chat list with unread counts, last message, pinned and muted flags · open a chat, latest 50 messages · send (idempotent,
a failed send gives your text back) · reply · edit (15 minutes) · unsend · reactions · mute · archive · mark read ·
start a chat from a profile · tap a push or notification to open the chat.

New messages arrive by **polling every 3 seconds while a chat is open** (this also refreshes edits, unsends and reactions).
The server already supports a live WebSocket; moving the app to it is the next step.

## Not available yet (the app says so instead of pretending)
Photos, voice, video and files in chat · forwarding · pinned messages · "clear chat" · typing indicator and "seen" ticks
(need the WebSocket) · scrolling back past the latest 50 messages · group info screen (name, photo, members, admin rules
still use mock data, so group chats open but without those controls) · server-side message search UI.
