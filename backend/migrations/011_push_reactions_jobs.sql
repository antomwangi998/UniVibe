-- Stage 11: message reactions, per-member conversation settings, push devices, and a Postgres-backed job queue.
-- (The project has no Redis or Bull, so the queue lives in Postgres: jobs are written in the same transaction as
-- the thing they describe, which is the transactional outbox.)

-- One reaction per person per message.
create table message_reactions (
  message_id uuid not null references messages(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  reaction text not null check (reaction in ('heart', 'like', 'laugh', 'fire', 'sad')),
  created_at timestamptz not null default now(),
  primary key (message_id, user_id)
);

-- Per-person conversation settings. Muting hides alerts only; it never deletes anything.
alter table conversation_members
  add column muted_until timestamptz,
  add column archived boolean not null default false,
  add column pinned boolean not null default false,
  add column marked_unread boolean not null default false;
-- Conversation list for one person, newest activity first, without scanning other people's memberships.
create index conversation_members_user_state on conversation_members (user_id, archived, pinned);

-- Several devices per person. An FCM token is only a delivery address, never proof of identity.
create table device_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  installation_id text not null check (char_length(installation_id) between 8 and 128),
  fcm_token text not null check (char_length(fcm_token) between 20 and 4096),
  platform text not null default 'android' check (platform in ('android')),
  registered_at timestamptz not null default now(),
  last_success_at timestamptz,
  revoked_at timestamptz,
  revoked_reason text,
  unique (user_id, installation_id)
);
-- A live token belongs to exactly one installation.
create unique index device_tokens_live_token on device_tokens (fcm_token) where revoked_at is null;
create index device_tokens_live_user on device_tokens (user_id) where revoked_at is null;

-- Push is its own switch, separate from "is it stored in the feed" (the existing columns).
alter table notification_preferences
  add column push_messages boolean not null default true,
  add column push_social boolean not null default true,
  add column push_events boolean not null default true;

-- What can actually be observed about push delivery: 'accepted' means FCM accepted the request, nothing more.
alter table notifications
  add column push_status text check (push_status is null or push_status in ('skipped', 'accepted', 'failed')),
  add column push_attempted_at timestamptz;

-- Queue and outbox. Rows are claimed with FOR UPDATE SKIP LOCKED, so several workers can run safely.
create table jobs (
  id uuid primary key default gen_random_uuid(),
  kind text not null,
  -- Ids only. Never tokens, passwords or message text.
  payload jsonb not null default '{}'::jsonb,
  -- Optional: a second job with the same (kind, dedupe_key) is ignored, so replays cannot duplicate work.
  dedupe_key text,
  status text not null default 'pending' check (status in ('pending', 'active', 'done', 'failed')),
  attempts int not null default 0,
  max_attempts int not null default 6,
  run_at timestamptz not null default now(),
  locked_until timestamptz,
  last_error text,
  created_at timestamptz not null default now(),
  finished_at timestamptz
);
create unique index jobs_dedupe on jobs (kind, dedupe_key) where dedupe_key is not null;
create index jobs_ready on jobs (run_at) where status in ('pending', 'active');
create index jobs_finished on jobs (finished_at) where status in ('done', 'failed');
