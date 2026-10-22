# Cleaning up unused media

Photos, videos, voice notes and documents are uploaded **before** the post, message, Story, avatar or event that uses
them exists. If the person never finishes (the app is closed, a post fails, a cover is replaced), the file stays in
storage and keeps counting against their storage allowance. The `purge-unused-media` job removes those files.

## What counts as unused

All of these must be true:

- the upload finished (unfinished uploads are removed by the older pending-media sweep after a day),
- it is older than the grace period (`MEDIA_ORPHAN_GRACE_HOURS`, default 24, allowed 1 to 720),
- it is not attached to a post or a message,
- nothing else points at it. This is read from the database schema at run time, so avatars, covers, event covers,
  community and group photos and Stories are protected, and so is any column a later feature adds that references
  `media(id)`.

Media that is still referenced is never touched, however old. An expired Story keeps its file until the Story itself
is purged (one hour after it expires).

## How it runs

- Every 15 minutes (and once at start-up), only when storage is configured.
- Each run takes at most 10 batches of 200 rows.
- The media rows are deleted and their files (and thumbnails) are queued in `storage_garbage` in one transaction.
  Files are then deleted from storage and only forgotten once storage accepted the removal, so an outage never leaves
  a file with no record: the next run retries.
- A database lock stops two server instances from cleaning at the same time.
- Repeating a run finds nothing new.

The log line `purged unused media` shows `{ media, files }` for each run.
