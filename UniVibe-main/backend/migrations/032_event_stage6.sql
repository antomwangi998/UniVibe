-- Events v2, stage 6: moderation, organizer updates, sharing, calendar revision.

-- Moderators can hide an event. A hidden event disappears for everyone except its organizer (who sees it marked hidden).
alter table events
  add column hidden_at timestamptz,
  add column hidden_by uuid references users(id) on delete set null,
  -- Counts changes that matter for a calendar entry (time, end, place, format, join link). Calendar apps use it to tell
  -- "same event, newer version" from "same event".
  add column revision int not null default 0;
create index events_hidden on events (hidden_at) where hidden_at is not null;

-- Moderation decisions about events are recorded like every other action.
alter table moderation_actions drop constraint moderation_actions_action_check;
alter table moderation_actions add constraint moderation_actions_action_check
  check (action in ('remove_content', 'restore_content', 'suspend_user', 'unsuspend_user', 'hide_event', 'restore_event'));

alter table event_audit drop constraint event_audit_action_check;
alter table event_audit add constraint event_audit_action_check
  check (action in ('create', 'publish', 'update', 'cancel', 'complete', 'announce_update', 'hide', 'restore'));

-- Short announcements an organizer publishes to the people who are going or interested ("doors moved to 7:30").
create table event_updates (
  id uuid primary key default gen_random_uuid(),
  event_id uuid not null references events(id) on delete cascade,
  author_id uuid references users(id) on delete set null,
  body text not null check (char_length(body) between 1 and 500),
  created_at timestamptz not null default now()
);
create index event_updates_event on event_updates (event_id, created_at desc, id desc);

-- A chat message can carry an event; the card is built per reader, so it only shows to people who may see the event.
alter table messages add column event_id uuid references events(id) on delete set null;
-- A community post can share an event. (posts.event_id stays reserved for the one announcement per event.)
alter table posts add column shared_event_id uuid references events(id) on delete cascade;
create index posts_shared_event on posts (shared_event_id) where shared_event_id is not null;
