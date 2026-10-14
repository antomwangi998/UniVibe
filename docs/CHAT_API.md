# Chat, group and comment features: backend contract

The app mocks everything below. A real backend must enforce these rules itself; the UI only mirrors them.

## Messages
- `POST /v1/chats/{id}/messages` with optional `replyToMessageId` (must belong to the same chat) and optional photo id.
- Reject posting when the chat is an admins-only group and the sender is not an admin (HTTP 403).
- `PUT /v1/chats/{id}/messages/{messageId}/reaction` with one of `heart|like|laugh|fire|sad`, or `DELETE` to remove. One reaction per user per message.
- "Delete" means unsend for everyone (your own messages only). "Clear chat" hides history for the current user only.
- Quoted reply previews are stored server-side (sender name + shortened text) so they survive the original being deleted.

## Groups
- Roles: `owner`, `admin`, `member`. One owner. The owner can promote/demote admins; admins can add/remove members but not other admins or the owner.
- Settings: `onlyAdminsCanMessage`, `onlyAdminsCanEditInfo`, `onlyAdminsCanAddMembers`. Editing these needs admin.
- Name 1 to 40 characters, description up to 200, up to 256 members. Group photos are uploaded and moderated like profile photos.
- Leaving as owner transfers ownership (longest-standing admin, else any member). If the last member leaves, delete the group.
- Never reveal a group's members or settings to non-members.
- Invite links are not built; they need server-issued, expiring, revocable tokens.

## Comments
- `POST /v1/posts/{id}/comments` with optional `replyToCommentId`. Replies are one level deep: the server attaches the reply to the thread's top-level comment and records who it answers.
- Deleting a top-level comment deletes its replies. Only the author (or a post/community moderator) may delete.
- Return `isPostAuthor` per comment. "Top" sorting is by like count; ties by time.
- Reports take a reason (`spam|harassment|hate|misinformation|other`) and go to a moderation queue.
- Hide comments and messages from users the viewer has blocked.

## Editing, forwarding, pinning
- `PATCH /v1/chats/{id}/messages/{messageId}` edits text of your own message within 15 minutes of sending; mark it `edited` and keep an audit trail for moderators.
- `POST /v1/chats/{id}/messages/{messageId}/forward` with 1 to 5 target chat ids. All-or-nothing: if you may not post in any target, send nothing. Forwarded copies are flagged `forwarded` and drop the reply quote and reactions.
- `PUT/DELETE /v1/chats/{id}/pin`: anyone in a 1:1 chat; only admins in groups. One pinned message per chat.
- Mentions are plain `@handle` text. For notifications the server must resolve handles itself (only to members of that group) rather than trust the client.
- Search in chat is client-side over loaded messages in this build. A real backend needs paged server-side search that only returns messages the user is allowed to see.

## Creating groups
- `POST /v1/groups` with name (1 to 40), description (up to 200), optional photo id and 1 to 255 member ids. The creator becomes owner.
- Validate that every member is a real, non-blocked student at the same university, and that nobody has blocked the creator. Never reveal who blocked whom: skip such members and report a generic result.
- Rate limit group creation and member adds to limit spam.
