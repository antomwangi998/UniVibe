-- Which campus an event is for. Null means every campus of the university. Who may see the event is unchanged (visibility decides that).
alter table events add column if not exists campus_id text references campuses(id) on delete set null;
create index if not exists events_campus_idx on events (campus_id) where campus_id is not null;
