-- Events v2, stage 1: richer event model, a configurable category table, drafts, community events and an audit trail.
-- Additive only: every existing row keeps working (in-person, published, no deadline, no community).

-- Categories become data, so a new one is an insert, not a code change. The 11 existing codes keep their meaning.
create table event_categories (
  code text primary key check (code ~ '^[a-z][a-z_]{1,31}$'),
  label text not null check (char_length(label) between 2 and 40),
  sort_order int not null default 100,
  active boolean not null default true
);
insert into event_categories (code, label, sort_order) values
  ('academic', 'Academic', 10), ('career', 'Career and Networking', 20), ('technology', 'Technology and Hackathons', 30),
  ('entrepreneurship', 'Innovation and Entrepreneurship', 40), ('clubs', 'Clubs and Societies', 50),
  ('sports', 'Sports and Fitness', 60), ('music', 'Music and Entertainment', 70), ('arts', 'Arts and Culture', 80),
  ('community', 'Community and Volunteering', 90), ('workshops', 'Workshops and Training', 100),
  ('study_groups', 'Study Groups', 110), ('announcements', 'University Announcements', 120),
  ('social', 'Social', 130), ('party', 'Party', 140), ('other', 'Other', 900);
alter table events drop constraint events_category_check;
alter table events add constraint events_category_fk foreign key (category) references event_categories(code);

alter table events
  add column event_type text not null default 'in_person' check (event_type in ('in_person', 'online', 'hybrid')),
  add column venue_address text check (venue_address is null or char_length(venue_address) <= 200),
  add column online_url text check (online_url is null or (char_length(online_url) <= 500 and online_url ~ '^https://')),
  add column external_registration_url text check (external_registration_url is null or (char_length(external_registration_url) <= 500 and external_registration_url ~ '^https://')),
  add column registration_deadline timestamptz,
  add column timezone text check (timezone is null or char_length(timezone) between 1 and 64),
  -- The university stays events.university_id. A community event also names its community.
  add column community_id uuid references communities(id) on delete set null,
  -- Client-generated key: a retried create returns the same event instead of a second one.
  add column create_key text check (create_key is null or char_length(create_key) between 8 and 64);

alter table events add constraint events_deadline_check check (registration_deadline is null or registration_deadline <= ends_at);

-- draft: only the organizer sees it. completed: the organizer closed it early. published and cancelled are unchanged.
alter table events drop constraint events_status_check;
alter table events add constraint events_status_check check (status in ('draft', 'published', 'cancelled', 'completed'));

-- 'community': visible only to active members of community_id. Required for events of private communities.
alter table events drop constraint events_visibility_check;
alter table events add constraint events_visibility_check check (visibility in ('public', 'university', 'community'));
-- If a community is ever hard-deleted, community_id becomes null and a 'community' event is then visible to its organizer only.

create unique index events_create_key on events (organizer_id, create_key) where create_key is not null;
create index events_community on events (community_id, starts_at, id) where community_id is not null and status = 'published';
create index events_category_time on events (category, starts_at) where status = 'published';
create index events_deadline on events (registration_deadline) where registration_deadline is not null and status = 'published';

-- Important changes by organizers and admins. Kept with the event; purged with it.
create table event_audit (
  id bigserial primary key,
  event_id uuid not null references events(id) on delete cascade,
  actor_id uuid references users(id) on delete set null,
  action text not null check (action in ('create', 'publish', 'update', 'cancel', 'complete')),
  detail jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index event_audit_event on event_audit (event_id, created_at desc);
