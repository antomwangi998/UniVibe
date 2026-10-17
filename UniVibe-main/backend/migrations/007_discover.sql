-- Discover: students find other students on their campus. Actions are private to the person who took them.
create table discover_actions (
  user_id uuid not null references users(id) on delete cascade,
  target_id uuid not null references users(id) on delete cascade,
  action text not null check (action in ('pass', 'connect')),
  created_at timestamptz not null default now(),
  primary key (user_id, target_id),
  check (user_id <> target_id)
);

create table discover_preferences (
  user_id uuid primary key references users(id) on delete cascade,
  courses text[] not null default '{}',
  faculties text[] not null default '{}',
  years text[] not null default '{}',
  interests text[] not null default '{}',
  updated_at timestamptz not null default now()
);

create index users_discover on users (university_id, created_at desc, id desc) where onboarded_at is not null;
