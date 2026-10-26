-- Report workflow: who is handling it, when it was first looked at, whether it was escalated, and private notes.
-- Escalation is a flag, not a new status, so the app's existing queues (open, under_review, action_taken, dismissed) keep working.
alter table reports
  add column assigned_to uuid references users(id) on delete set null,
  add column assigned_at timestamptz,
  add column first_reviewed_at timestamptz,
  add column escalated_at timestamptz,
  add column escalated_by uuid references users(id) on delete set null;

-- reviewed_at only ever meant "resolved". Fill the new first-look time from what we know.
update reports set first_reviewed_at = case
  when status in ('action_taken', 'dismissed') then coalesce(reviewed_at, updated_at)
  when status = 'under_review' then updated_at end;

create index reports_assigned on reports (assigned_to, status) where assigned_to is not null;
create index reports_escalated on reports (escalated_at) where escalated_at is not null;

create table report_notes (
  id uuid primary key default gen_random_uuid(),
  report_id uuid not null references reports(id) on delete cascade,
  author_id uuid references users(id) on delete set null,
  note text not null check (char_length(note) between 1 and 1000),
  created_at timestamptz not null default now()
);
create index report_notes_report on report_notes (report_id, created_at);
