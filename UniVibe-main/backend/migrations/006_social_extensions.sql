-- Extends the existing schema (users, posts, comments, follows, media) with what the platform still lacked:
-- protected account fields, verification workflow state, privacy, blocking, notifications and an audit log.
-- Nothing here replaces or drops an existing table. All timestamps are timestamptz (UTC); the API returns ISO-8601 UTC.

alter table users add column role text not null default 'user' check (role in ('user', 'moderator', 'admin'));
alter table users add column suspended_until timestamptz;
alter table users add column faculty text not null default '';
alter table users add column graduation_year int check (graduation_year between 1990 and 2100);
alter table users add column updated_at timestamptz not null default now();
alter table users add column verification_status text not null default 'unverified'
  check (verification_status in ('unverified', 'pending', 'verified', 'rejected', 'expired'));
alter table users add column verification_method text;
alter table users add column verification_submitted_at timestamptz;
alter table users add column verification_reviewed_at timestamptz;
alter table users add column verification_reviewed_by uuid references users(id) on delete set null;
alter table users add column verification_note text;

update users set verification_status = 'verified', verification_method = 'email_domain',
  verification_reviewed_at = email_verified_at where student_verified;

-- The auth flow still writes users.student_verified; keep the richer status in step with it so there is one truth.
create function sync_verification_status() returns trigger language plpgsql as $$
begin
  if new.student_verified and new.verification_status <> 'verified' then
    new.verification_status := 'verified';
    new.verification_method := coalesce(new.verification_method, 'email_domain');
    new.verification_reviewed_at := coalesce(new.verification_reviewed_at, now());
  elsif not new.student_verified and new.verification_status = 'verified' then
    new.verification_status := 'unverified';
  end if;
  return new;
end $$;
create trigger users_sync_verification before insert or update of student_verified, verification_status on users
  for each row execute function sync_verification_status();

create index users_university on users (university_id);

-- Moderation can hide a post or comment without deleting the evidence.
alter table posts add column status text not null default 'active' check (status in ('active', 'removed'));
alter table comments add column status text not null default 'active' check (status in ('active', 'removed'));

-- New kinds of attachment: message photos and event covers.
alter table media drop constraint media_purpose_check;
alter table media add constraint media_purpose_check check (purpose in ('post', 'avatar', 'cover', 'message', 'event'));

create table privacy_settings (
  user_id uuid primary key references users(id) on delete cascade,
  discoverable boolean not null default true,
  show_online boolean not null default true,
  message_permission text not null default 'everyone' check (message_permission in ('everyone', 'followers', 'nobody')),
  updated_at timestamptz not null default now()
);

create table blocks (
  blocker_id uuid not null references users(id) on delete cascade,
  blocked_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);
create index blocks_blocked on blocks (blocked_id);

create table notification_preferences (
  user_id uuid primary key references users(id) on delete cascade,
  follows boolean not null default true,
  likes boolean not null default true,
  comments boolean not null default true,
  messages boolean not null default true,
  events boolean not null default true,
  groups boolean not null default true,
  push_enabled boolean not null default true
);

create table notifications (
  id uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references users(id) on delete cascade,
  actor_id uuid references users(id) on delete cascade,
  type text not null check (type in ('follow', 'like', 'comment', 'reply', 'message', 'group_invite', 'verification', 'event_update')),
  entity_type text,
  entity_id uuid,
  metadata jsonb not null default '{}'::jsonb,
  read_at timestamptz,
  created_at timestamptz not null default now()
);
create index notifications_inbox on notifications (recipient_id, created_at desc, id desc);
create index notifications_unread on notifications (recipient_id) where read_at is null;
create index notifications_entity on notifications (entity_type, entity_id);
-- One notification per (recipient, actor, type, entity): like -> unlike -> like, or a retried request, never duplicates.
create unique index notifications_dedupe on notifications
  (recipient_id, actor_id, type, coalesce(entity_id, '00000000-0000-0000-0000-000000000000'::uuid))
  where actor_id is not null;

create table audit_logs (
  id uuid primary key default gen_random_uuid(),
  actor_id uuid references users(id) on delete set null,
  action text not null,
  target_type text,
  target_id uuid,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index audit_logs_created on audit_logs (created_at desc);
