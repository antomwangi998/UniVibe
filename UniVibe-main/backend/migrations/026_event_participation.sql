-- Events v2, stage 2: "Interested" and saved events.
-- Interested is a soft signal: it never takes a seat. Registration (going) stays in event_registrations and is the only thing capacity counts.
-- A person is either interested or registered for an event, never both; the server keeps that true in one transaction.

create table event_interest (
  event_id uuid not null references events(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (event_id, user_id)
);
create index event_interest_user on event_interest (user_id, created_at desc);

create table saved_events (
  user_id uuid not null references users(id) on delete cascade,
  event_id uuid not null references events(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, event_id)
);
create index saved_events_list on saved_events (user_id, created_at desc, event_id);
