-- Stories: photo, video or text updates that disappear after STORY_TTL_HOURS (default 24). Separate from Notes and posts.
alter table media drop constraint media_purpose_check;
alter table media add constraint media_purpose_check check (purpose in ('post', 'avatar', 'cover', 'message', 'event', 'group', 'community', 'story'));

create table stories (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references users(id) on delete cascade,
  kind text not null check (kind in ('photo', 'video', 'text')),
  -- A story owns its media exclusively (unique). If the media row goes, so does the story.
  media_id uuid unique references media(id) on delete cascade,
  text text check (text is null or char_length(text) between 1 and 280),
  background text check (background is null or background in ('teal', 'indigo', 'rose', 'amber', 'slate')),
  caption text check (caption is null or char_length(caption) between 1 and 200),
  audience text not null default 'campus' check (audience in ('campus', 'followers')),
  -- Chosen by the app per publish attempt, so a retry after a dropped connection cannot publish twice.
  client_id text check (client_id is null or char_length(client_id) between 1 and 64),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  check ((kind = 'text' and media_id is null and text is not null) or (kind <> 'text' and media_id is not null))
);
create unique index stories_client on stories (author_id, client_id) where client_id is not null;
create index stories_author_recent on stories (author_id, created_at desc);
create index stories_expiry on stories (expires_at);

-- One row per viewer per story (never a list inside the story row). Gone with the story.
create table story_views (
  story_id uuid not null references stories(id) on delete cascade,
  viewer_id uuid not null references users(id) on delete cascade,
  viewed_at timestamptz not null default now(),
  primary key (story_id, viewer_id)
);
create index story_views_viewer on story_views (viewer_id, story_id);
