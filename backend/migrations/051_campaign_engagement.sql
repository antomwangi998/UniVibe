-- Likes and comments on sponsored cards, so an ad can be reacted to like any other post.
create table campaign_likes (
  campaign_id uuid not null references campaigns(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (campaign_id, user_id)
);
create index campaign_likes_user on campaign_likes (user_id);

create table campaign_comments (
  id uuid primary key default gen_random_uuid(),
  campaign_id uuid not null references campaigns(id) on delete cascade,
  author_id uuid not null references users(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 500),
  created_at timestamptz not null default now()
);
create index campaign_comments_campaign on campaign_comments (campaign_id, created_at);
