-- Notes: one short, expiring text update per student, shown above the Chats list.
-- A Note is its own thing (not a Story and not a post). At most one per author, so "replace" is an update in place.
-- Expiry is decided by the database clock: every read filters on expires_at > now(), and a purge only tidies up.
create table notes (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null unique references users(id) on delete cascade,
  text text not null check (char_length(text) between 1 and 200), -- 200 is a hard ceiling; the live limit is NOTE_MAX_CHARS (default 60)
  audience text not null default 'campus' check (audience in ('campus', 'followers')),
  -- Bumped on every publish or edit, so clients can ignore a stale answer that arrives after a newer one.
  version bigint not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create index notes_expiry on notes (expires_at);
create index notes_recent on notes (created_at desc);

-- "Mute this person's Notes". Separate from message mutes and from (future) Story mutes.
create table note_mutes (
  user_id uuid not null references users(id) on delete cascade,
  muted_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, muted_id),
  check (user_id <> muted_id)
);
