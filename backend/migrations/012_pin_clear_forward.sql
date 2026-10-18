-- Stage 12: "clear chat" (per person), one pinned message per conversation, forwarded flag.

-- Messages up to this seq are hidden from this person only. Others keep their history.
alter table conversation_members add column cleared_seq bigint not null default 0;

-- One pinned message per conversation. Unsent messages are ignored when it is read back.
alter table conversations add column pinned_message_id uuid references messages(id) on delete set null;

-- Forwarded copies carry no reply quote, no reactions and no link back to the original.
alter table messages add column forwarded boolean not null default false;
