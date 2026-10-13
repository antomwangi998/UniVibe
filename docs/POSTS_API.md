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
