-- Stage 8: campus events, registrations, reminders. Times are timestamptz (UTC); the API returns ISO-8601 UTC.
create table events (
  id uuid primary key default gen_random_uuid(),
  organizer_id uuid not null references users(id) on delete cascade,
  university_id text not null references universities(id),
  group_id uuid,
  title text not null check (char_length(title) between 3 and 80),
  description text not null default '' check (char_length(description) <= 1000),
  category text not null check (category in ('academic', 'workshops', 'sports', 'social', 'clubs', 'technology', 'entrepreneurship', 'arts', 'other')),
  venue_name text not null check (char_length(venue_name) between 2 and 80),
  cover_media_id uuid references media(id) on delete set null,
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  capacity int check (capacity between 1 and 5000),
  visibility text not null default 'university' check (visibility in ('public', 'university')),
  attendees_visible boolean not null default true,
  status text not null default 'published' check (status in ('published', 'cancelled')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (ends_at > starts_at)
);
create index events_upcoming on events (university_id, starts_at, id) where status = 'published';
create index events_organizer on events (organizer_id, starts_at);
create index events_ends on events (ends_at);

create table event_registrations (
  event_id uuid not null references events(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  status text not null default 'registered' check (status in ('registered', 'cancelled')),
  registered_at timestamptz not null default now(),
  cancelled_at timestamptz,
  checked_in_at timestamptz,
  primary key (event_id, user_id)
);
create index event_registrations_user on event_registrations (user_id, status);
create index event_registrations_event on event_registrations (event_id, status, registered_at);

-- One row per (event, person) once a reminder was sent, so the reminder job can safely run again.
create table event_reminders (
  event_id uuid not null references events(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  sent_at timestamptz not null default now(),
  primary key (event_id, user_id)
);
