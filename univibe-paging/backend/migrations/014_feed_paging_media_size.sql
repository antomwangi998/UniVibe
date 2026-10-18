-- Stage 14: paged feeds and known media sizes.

-- Keyset paging for the home feed reads posts newest first by (created_at, id).
create index if not exists posts_feed_order on posts (created_at desc, id desc);

-- Pixel size of photos and videos, sent by the app when it starts an upload. Lets the feed reserve the right
-- amount of space before a photo loads, so posts do not jump. Unknown (null) for older uploads.
alter table media add column if not exists width int check (width is null or (width > 0 and width <= 20000));
alter table media add column if not exists height int check (height is null or (height > 0 and height <= 20000));
