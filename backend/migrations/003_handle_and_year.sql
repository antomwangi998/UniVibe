-- Every student gets a public handle (shown as @handle) and an optional year of study.
alter table users add column handle text;
alter table users add column year_of_study text not null default '';

-- Backfill accounts created before handles existed: name letters plus 4 characters of the id, which keeps them unique.
update users
set handle = coalesce(nullif(left(lower(regexp_replace(full_name, '[^a-zA-Z0-9]', '', 'g')), 15), ''), 'student')
             || substr(md5(id::text), 1, 4)
where handle is null;

alter table users alter column handle set not null;
alter table users add constraint users_handle_key unique (handle);
