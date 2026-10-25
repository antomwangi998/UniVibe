-- Stage 6: direct and group conversations, messages, read/delivery cursors.
create table conversations (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('direct', 'group')),
  title text check (title is null or char_length(title) between 1 and 40),
  created_by uuid references users(id) on delete set null,
  -- "<smaller user id>:<larger user id>" for direct chats; unique, so a pair can never have two conversations.
  direct_key text unique,
  created_at timestamptz not null default now(),
  last_message_at timestamptz not null default now(),
  check ((kind = 'direct' and direct_key is not null) or (kind = 'group' and direct_key is null))
);
create index conversations_recent on conversations (last_message_at desc, id desc);

create table conversation_members (
  conversation_id uuid not null references conversations(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'admin', 'member')),
  joined_at timestamptz not null default now(),
  last_read_seq bigint not null default 0,
  last_delivered_seq bigint not null default 0,
  primary key (conversation_id, user_id)
);
create index conversation_members_user on conversation_members (user_id);

create table messages (
  id uuid primary key default gen_random_uuid(),
  -- seq increases with every insert, so it gives a total order and serves as the paging and read cursor.
  seq bigint generated always as identity,
  conversation_id uuid not null references conversations(id) on delete cascade,
  sender_id uuid references users(id) on delete set null,
  client_id text check (client_id is null or char_length(client_id) <= 64),
  content text not null default '' check (char_length(content) <= 4000),
  reply_to_id uuid references messages(id) on delete set null,
  status text not null default 'active' check (status in ('active', 'deleted')),
  created_at timestamptz not null default now(),
  edited_at timestamptz,
  deleted_at timestamptz
);
create unique index messages_seq on messages (seq);
create index messages_conversation on messages (conversation_id, seq desc);
-- A retried send with the same client_id returns the original message instead of creating a duplicate.
create unique index messages_idempotency on messages (conversation_id, sender_id, client_id) where client_id is not null;

alter table media add column message_id uuid;
alter table media add constraint media_message_fk foreign key (message_id) references messages(id) on delete cascade;
