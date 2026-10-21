-- Events v2, stage 7: indexes for the lists stage 6 and 7 added.
-- Organizer's own events (drafts and upcoming) by time.
create index if not exists events_organizer_time on events (organizer_id, starts_at desc, id desc);
-- Messages that share an event (looked up when an event is deleted or hidden).
create index if not exists messages_event on messages (event_id) where event_id is not null;
