-- Events v2, stage 3: the Home Feed announcement of an event.
-- A feed post that announces an event only REFERENCES it: the event stays the one canonical record, and the
-- post's card is rendered from the event's current state (so a cancellation shows up everywhere at once).
-- At most one announcement per event, so editing an event never creates another post.
-- If the event is deleted or purged, its announcement goes with it.
alter table posts add column event_id uuid references events(id) on delete cascade;
create unique index posts_event_announcement on posts (event_id) where event_id is not null;
