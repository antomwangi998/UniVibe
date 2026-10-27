-- Multi-campus universities. A university can have several campuses; a student belongs to one campus.
-- Universities with no rows here (like the development placeholder) keep working exactly as before.
create table if not exists campuses (
  id text primary key,
  university_id text not null references universities(id) on delete cascade,
  name text not null,
  sort_order int not null default 0
);
create index if not exists campuses_university_idx on campuses (university_id, sort_order);

-- Null for people who signed up before campuses existed, and for universities without campuses.
alter table users add column if not exists campus_id text references campuses(id) on delete set null;
create index if not exists users_campus_idx on users (campus_id) where campus_id is not null;

-- Kenyatta University. Student email: name@students.ku.ac.ke (staff use other addresses, so they are not listed here).
insert into universities (id, name, email_domains)
values ('ku', 'Kenyatta University', array['students.ku.ac.ke'])
on conflict (id) do nothing;

-- Edit this list to match the campuses you want to offer (the old Ruiru campus became a model school in 2024, so it is left out).
insert into campuses (id, university_id, name, sort_order) values
  ('ku-main', 'ku', 'Main Campus (Kahawa)', 1),
  ('ku-parklands', 'ku', 'Parklands Campus', 2),
  ('ku-city', 'ku', 'City Campus (Nairobi CBD)', 3),
  ('ku-mombasa', 'ku', 'Mombasa Campus', 4),
  ('ku-kitui', 'ku', 'Kitui Campus', 5),
  ('ku-nakuru', 'ku', 'Nakuru Campus', 6),
  ('ku-embu', 'ku', 'Embu Campus', 7),
  ('ku-nyeri', 'ku', 'Nyeri Campus', 8)
on conflict (id) do nothing;
