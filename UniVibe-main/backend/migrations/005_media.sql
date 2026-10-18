-- Photos and videos live in object storage (Cloudflare R2). The database keeps only where each file is.
create table media (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('image', 'video')),
  purpose text not null check (purpose in ('post', 'avatar', 'cover')),
  content_type text not null,
  size_bytes bigint not null check (size_bytes > 0),
  object_key text not null unique,
  status text not null default 'pending' check (status in ('pending', 'ready')),
  post_id uuid references posts(id) on delete cascade,
  position int not null default 0,
  created_at timestamptz not null default now()
);
create index media_post on media (post_id, position);
create index media_pending on media (created_at) where status = 'pending';

alter table users add column avatar_media_id uuid references media(id) on delete set null;
alter table users add column cover_media_id uuid references media(id) on delete set null;

-- A post may now be only photos or a video, so its text may be empty.
alter table posts drop constraint posts_text_check;
alter table posts add constraint posts_text_length check (char_length(text) <= 280);
