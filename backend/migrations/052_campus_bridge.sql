-- Campus bridge: campuses stay separate communities, but content, people and events can now be shared across them.

-- A post can be shared with every campus ('all'). 'campus' stays the default, so nothing already posted leaks.
alter table posts drop constraint if exists posts_audience_check;
alter table posts add constraint posts_audience_check check (audience in ('campus', 'followers', 'community', 'all'));

-- Students may opt out of being found by other campuses (default on). Their own campus always sees them.
alter table privacy_settings add column if not exists show_other_campuses boolean not null default true;

-- A short label for filter chips ("KU"); the app falls back to the full name.
alter table universities add column if not exists short_name text;
update universities set short_name = 'KU' where id = 'ku' and short_name is null;

create index if not exists users_university_idx on users (university_id);
