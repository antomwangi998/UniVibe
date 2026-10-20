-- Events v2, stage 5: notification preferences, configurable reminders, per-event reminder switch.
-- The existing `events` and `push_events` switches stay the master switches; these refine them.
alter table notification_preferences
  add column event_confirmations boolean not null default true,
  add column event_reminders boolean not null default true,
  add column event_changes boolean not null default true,
  add column event_cancellations boolean not null default true,
  add column event_community boolean not null default true,
  add column event_organizer_updates boolean not null default true,
  -- Minutes before the start. Only 24 hours and 1 hour are offered; an empty list means no reminders.
  add column event_reminder_offsets int[] not null default '{1440,60}'
    check (event_reminder_offsets <@ array[1440, 60] and cardinality(event_reminder_offsets) <= 2);

-- A person can silence reminders for one event they registered for.
alter table event_registrations add column reminders boolean not null default true;

-- One row per (event, person, reminder) once sent, so the job can run again and never repeats a reminder.
-- When an event's start changes the rows are cleared, so reminders are relative to the new time.
alter table event_reminders add column offset_minutes int not null default 60 check (offset_minutes in (1440, 60));
alter table event_reminders drop constraint event_reminders_pkey;
alter table event_reminders add primary key (event_id, user_id, offset_minutes);
