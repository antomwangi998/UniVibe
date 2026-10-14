# Create post: backend contract

`POST /v1/posts` (authenticated, rate limited), body: `text`, `audience` (`campus` | `followers` | `community:{id}`),
and up to 4 photos uploaded separately (pre-signed upload or multipart), referenced by id.

The server must, regardless of what the app checked:
- Trim and validate text (1 to 280 characters, or at least one photo).
- Validate every upload: real image type by content (not file name), size limit, re-encode and strip metadata, scan if possible.
- Enforce audience rules: only members may post to a community; `followers` posts are never returned in campus feeds.
- Check the author's account state (verified email, not suspended) and apply posting rate limits.
- Return the stored post (id, author, timestamp, audience, photo URLs).

The app already re-encodes picked photos (max 1600px JPEG, no EXIF) before upload, but that is not a security control.
Photos of an abandoned draft are deleted from app storage when the screen closes.

## Polls, video, edit, delete, share

The app now supports these; the mock repository mirrors the rules below, and a real server must enforce them itself.

**Polls.** `POST /v1/posts` accepts `poll: { options: [string] }` instead of media.
- 2 to 4 options, each 1 to 40 characters after trimming, no case-insensitive duplicates. The post text (the question) is required.
- A post has a poll or media, never both.
- `POST /v1/posts/{id}/vote` with `{ optionIndex }`: one vote per student per poll (reject a second vote with 409), reject out-of-range indexes, and never expose who voted for what.
- Post responses include `poll: { options: [{ text, votes }], myVote }`.

**Video.** Up to 50 MB per video, uploaded separately like photos (pre-signed upload) and referenced by id.
- Validate the real container/codec by content, transcode, strip metadata, and scan before serving. Return a streaming URL.
- The app currently copies the picked video into private app storage and plays it locally (mock only).

**Edit.** `PATCH /v1/posts/{id}` with `{ text }`: author only, same text rules as create (1 to 280 characters, or empty when the post has media or a poll). Set `edited: true`; the app shows "edited".
Polls, media and audience can't be changed by an edit.

**Delete.** `DELETE /v1/posts/{id}`: author only (moderators have a separate endpoint). Remove the post's media, comments, likes, saves and notifications.

**Share.** `POST /v1/posts/{id}/share` increments `shareCount`. The app opens the system share sheet with text only (author and text, no media).
Only `campus` audience posts may be shared outside the app; the app blocks `followers` and `community` posts, but the server should not hand out public links for them either.
