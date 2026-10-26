-- Events v2, stage 4: Discover and search.
-- Categories are grouped for Discover sections by data, so a new category joins a section with an update, not a deploy.
alter table event_categories add column discover_group text check (discover_group is null or discover_group ~ '^[a-z_]{2,30}$');
update event_categories set discover_group = 'academic_career' where code in ('academic', 'career', 'workshops', 'study_groups', 'announcements');
update event_categories set discover_group = 'tech_innovation' where code in ('technology', 'entrepreneurship');
update event_categories set discover_group = 'sports_entertainment' where code in ('sports', 'music', 'arts', 'social', 'party');

-- "Not interested" in an event: private to the person, hides it from their Discover and Home recommendations only.
-- It never hides the event from the Events tab, search, or its own page, and the organizer is never told.
create table event_feedback (
  user_id uuid not null references users(id) on delete cascade,
  event_id uuid not null references events(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, event_id)
);

-- Ranking looks at a person's history and at how many people engaged with an event.
-- (Not named event_registrations_user: 009_events.sql already has an index with that name.)
create index if not exists event_registrations_active_user on event_registrations (user_id) where status = 'registered';
create index saved_events_event on saved_events (event_id);
create index events_discover on events (starts_at, id) where status = 'published';
