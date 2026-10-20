-- When a student was last connected. Written when their first device connects and when their last one disconnects.
-- It is only ever shown to someone else when that student leaves "Show online status" on (see modules/presence.ts).
alter table users add column last_seen_at timestamptz;
