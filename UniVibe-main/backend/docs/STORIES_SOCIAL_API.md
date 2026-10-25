# Stories: reactions, replies, viewers, audience (`/api/v1`)

Adds to the Stories routes (see `stories.ts`). Migration `029_story_social.sql`. The server decides every permission; hiding things in the app is never relied on.

| Route | Purpose |
|---|---|
| `GET /me/stories` | `{ serverTime, items }` my active Stories, newest first, each a Story plus `viewCount` and `reactions` (`{ heart: 2 }`). Counts are only ever returned to the author. |
| `GET /stories/:id/viewers?limit&cursor` | Author only (anyone else: `404`). `{ total, nextCursor, items:[{ viewer, viewedAt, reaction }] }`, newest first. People the author has blocked (or who blocked them) are left out. |
| `PATCH /stories/:id` `{ audience: 'campus'\|'followers' }` | Author only. Returns the Story. |
| `PUT /stories/:id/reaction` `{ reaction }` | `heart, like, laugh, fire, sad` (same set as chat). One per person: repeating is a no-op, a different one replaces it. `400` on your own Story. Needs the right to see the Story, otherwise `404`. |
| `DELETE /stories/:id/reaction` | `204`, also when there was none. |
| `POST /stories/:id/reply` `{ content, clientId? }` | Sends an ordinary chat message in the direct conversation with the author (created if needed). `201` new, `200` when `clientId` repeats. Returns `{ conversationId, message }`. |
| `POST /reports` `{ targetType: 'story', targetId, reason }` | Only for a Story the reporter may currently see, never their own. |

A Story in `StoryItem` now carries `myReaction`. Live event `story:changed` has a new `kind: 'updated'` (audience changed); like the others it carries no content, the app re-reads.

## Policies
- **Audience change:** applies immediately. Anyone who no longer qualifies gets `404` on every read and cannot react, reply or report, even with cached metadata. A media link they already hold can still work until it expires (`STORY_MEDIA_LINK_SECONDS`, 10 minutes). Views and reactions already recorded stay.
- **Replies reuse messaging:** blocks, suspension, other universities and the author's "who can message me" setting all apply (`403 cannot_message`). The message stores only the Story's id and kind. `message.story = { id, kind, available }`: `available` becomes `false` when the Story expires or is deleted, and nothing of its content is kept in the chat.
- **Reaction notifications:** the author gets one `story_reaction` notification the first time someone reacts (changing a reaction does not notify again). Controlled by `storyReactions` in `/me/notification-preferences` (feed) and `pushSocial` (push). Replies arrive as normal messages, so they follow the `messages` switches and conversation mute.

## Not done
- Moderators can suspend the author from a Story report but cannot yet remove a Story (`remove_content` supports only posts and comments). A reported Story expires by itself within 24 hours.
- Story reports have no screen in the app yet.
