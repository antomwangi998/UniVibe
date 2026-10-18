-- Social graph, posts (text, polls), likes, saves, comments and reports.
create table follows (
  follower_id uuid not null references users(id) on delete cascade,
  followee_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (follower_id, followee_id),
  check (follower_id <> followee_id)
);
create index follows_followee on follows (followee_id);

create table posts (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references users(id) on delete cascade,
  text text not null check (char_length(text) between 1 and 280),
  audience text not null check (audience in ('campus', 'followers')),
  poll_options text[],
  share_count int not null default 0,
  created_at timestamptz not null default now(),
  edited_at timestamptz
);
create index posts_recent on posts (created_at desc, id desc);
create index posts_author on posts (author_id, created_at desc);

create table post_likes (
  post_id uuid not null references posts(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);

create table post_saves (
  post_id uuid not null references posts(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);
create index post_saves_user on post_saves (user_id, created_at desc);

-- One vote per student per poll is enforced by the primary key.
create table poll_votes (
  post_id uuid not null references posts(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  option_index int not null check (option_index >= 0),
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);

create table post_reports (
  post_id uuid not null references posts(id) on delete cascade,
  reporter_id uuid not null references users(id) on delete cascade,
  reason text not null,
  created_at timestamptz not null default now(),
  primary key (post_id, reporter_id)
);

-- Replies hang under the top-level comment of their thread (parent_id); reply_to_handle is who they answer.
create table comments (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references posts(id) on delete cascade,
  author_id uuid not null references users(id) on delete cascade,
  parent_id uuid references comments(id) on delete cascade,
  reply_to_handle text,
  text text not null check (char_length(text) between 1 and 280),
  created_at timestamptz not null default now()
);
create index comments_post on comments (post_id, created_at);

create table comment_likes (
  comment_id uuid not null references comments(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (comment_id, user_id)
);

create table comment_reports (
  comment_id uuid not null references comments(id) on delete cascade,
  reporter_id uuid not null references users(id) on delete cascade,
  reason text not null,
  created_at timestamptz not null default now(),
  primary key (comment_id, reporter_id)
);
