-- Reports and moderation. Reporter identity is visible to moderators only, never to the person reported.
create table reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid references users(id) on delete set null,
  university_id text not null references universities(id),
  target_type text not null check (target_type in ('user', 'post', 'comment', 'message', 'event')),
  target_id uuid not null,
  reason text not null check (reason in ('spam', 'harassment', 'hate', 'sexual', 'violence', 'self_harm', 'impersonation', 'misinformation', 'other')),
  details text not null default '' check (char_length(details) <= 500),
  status text not null default 'open' check (status in ('open', 'under_review', 'action_taken', 'dismissed')),
  reviewed_by uuid references users(id) on delete set null,
  resolution_note text not null default '' check (char_length(resolution_note) <= 500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  reviewed_at timestamptz
);
-- Reporting the same thing twice does not create a second report. (A deleted reporter leaves a null, which is allowed.)
create unique index reports_once on reports (reporter_id, target_type, target_id) where reporter_id is not null;
create index reports_queue on reports (university_id, status, created_at desc, id desc);
create index reports_target on reports (target_type, target_id);

create table moderation_actions (
  id uuid primary key default gen_random_uuid(),
  report_id uuid references reports(id) on delete set null,
  moderator_id uuid references users(id) on delete set null,
  action text not null check (action in ('remove_content', 'restore_content', 'suspend_user', 'unsuspend_user')),
  target_type text not null,
  target_id uuid not null,
  note text not null default '' check (char_length(note) <= 500),
  created_at timestamptz not null default now()
);
create index moderation_actions_target on moderation_actions (target_type, target_id);
