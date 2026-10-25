-- Timed polls, reposts and post views.

-- A poll ends at poll_ends_at (at most 3 days after it was posted; the API enforces the limit).
-- Polls that already exist get the longest allowed run, so older ones end straight away.
alter table posts add column if not exists poll_ends_at timestamptz;
update posts set poll_ends_at = created_at + interval '3 days' where poll_options is not null and poll_ends_at is null;

-- One repost per student per post, like likes and saves.
create table if not exists post_reposts (
  post_id uuid not null references posts(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);
create index if not exists post_reposts_user on post_reposts (user_id, created_at desc);

-- A view is counted once per student per post (their own posts do not count).
create table if not exists post_views (
  post_id uuid not null references posts(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);
