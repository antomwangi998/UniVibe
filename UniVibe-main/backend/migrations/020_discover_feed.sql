-- Discover content feed: what a student told Discover they do not want to see, plus indexes for ranking.
-- Feedback is private to the student who gave it. Nothing here is shown to other students.
create table discover_feedback (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('not_interested', 'hide_author', 'mute_topic')),
  post_id uuid references posts(id) on delete cascade,
  author_id uuid references users(id) on delete cascade,
  topic text check (topic ~ '^[a-z0-9_]{2,30}$'),
  created_at timestamptz not null default now(),
  check (
    (kind = 'not_interested' and post_id is not null and author_id is null and topic is null) or
    (kind = 'hide_author' and author_id is not null and post_id is null and topic is null and author_id <> user_id) or
    (kind = 'mute_topic' and topic is not null and post_id is null and author_id is null)
  )
);
-- One row per (student, thing): repeating a request changes nothing.
create unique index discover_feedback_post on discover_feedback (user_id, post_id) where kind = 'not_interested';
create unique index discover_feedback_author on discover_feedback (user_id, author_id) where kind = 'hide_author';
create unique index discover_feedback_topic on discover_feedback (user_id, topic) where kind = 'mute_topic';

-- Engagement velocity looks at likes in the last few hours.
create index if not exists post_likes_recent on post_likes (post_id, created_at);
-- Photo and video grids.
create index if not exists media_ready_kind on media (kind, post_id) where status = 'ready' and post_id is not null;
