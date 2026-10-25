-- Group profile (description, photo), group permissions, report-a-group, and voice messages.
alter table conversations
  add column description text check (description is null or char_length(description) <= 300),
  add column photo_media_id uuid references media(id) on delete set null,
  add column only_admins_can_message boolean not null default false,
  add column only_admins_can_edit_info boolean not null default true,
  add column only_admins_can_add_members boolean not null default true;

alter table media drop constraint media_kind_check;
alter table media add constraint media_kind_check check (kind in ('image', 'video', 'audio'));
alter table media drop constraint media_purpose_check;
alter table media add constraint media_purpose_check check (purpose in ('post', 'avatar', 'cover', 'message', 'event', 'group'));
alter table media add column duration_ms integer check (duration_ms is null or duration_ms between 1 and 300000);

create table conversation_reports (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references conversations(id) on delete cascade,
  reporter_id uuid not null references users(id) on delete cascade,
  reason text not null,
  details text not null default '' check (char_length(details) <= 500),
  created_at timestamptz not null default now(),
  unique (conversation_id, reporter_id)
);
