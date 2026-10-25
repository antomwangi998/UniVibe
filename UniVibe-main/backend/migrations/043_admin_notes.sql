-- Private notes staff leave on an account ("warned twice, see report"). Never shown to the person.
create table admin_notes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  author_id uuid references users(id) on delete set null,
  note text not null check (char_length(note) between 1 and 1000),
  created_at timestamptz not null default now()
);
create index admin_notes_user on admin_notes (user_id, created_at desc);
-- The directory sorts by these.
create index if not exists users_created_dir on users (created_at desc, id desc);
