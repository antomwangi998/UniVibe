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

## Paged feed and media sizes

**Paging.** `GET /v1/posts?filter=campus|following` still returns a plain array, newest first. Two optional query
parameters page it: `limit` (1 to 50; without it the feed returns up to 100 posts as before) and `before` (the id of the
last post the app already has; the response continues with the posts just older than it). A page shorter than `limit`
means the end of the feed. An unknown `before` id returns 404 (for example the post was deleted), and the app then
stops loading more instead of showing a wrong list.

**Media size.** `POST /v1/media/uploads` accepts optional `width` and `height` (pixels, both or neither). Post
responses include `width` and `height` on each `media` item (null for older uploads) so the app can reserve the right
space before the file loads. These values only affect layout; they are never used for security checks.

## Photo processing and size limits

**Processing.** When an upload is completed (`POST /v1/media/{id}/complete`) the server decodes every photo itself
(libvips through `sharp`), so a file that only starts with the right bytes is refused. It then:
- turns the photo upright from its orientation tag and removes all metadata (location, camera, owner),
- saves it as WebP, at most 2048 px on the longest edge for posts, messages and events (1600 for covers, 512 for avatars),
- never enlarges a photo and never changes its proportions,
- for post photos, also saves a feed thumbnail (720 px longest edge).

The stored width and height are the ones the server measured; whatever the app sent at upload start is ignored once the
photo is processed. Post responses add `thumbUrl` to each photo in `media` (null when there is none). The app shows the
thumbnails in the feed and opens the full photo in the viewer. The original upload is deleted after processing, and
deleting a post or account deletes the thumbnail too.

**Limits** (the app checks the file limits before uploading, the server enforces all of them):
- Photo file: up to 8 MB as sent. Photo size: up to 30 megapixels (width x height), however small the file is.
- Video file: up to 50 MB. Videos are checked (type, size, real container) but not re-encoded or given a poster frame:
  that needs ffmpeg, which this server does not have.
- Storage per student: 300 MB across all their photos and videos, counted after processing. Over the limit, starting
  an upload answers 400 `storage_full` with a message the app shows as is.
