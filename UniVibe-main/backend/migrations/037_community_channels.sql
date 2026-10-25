-- Community discussion channels (037) (Discord-style rooms inside a community).
-- Additive only: new tables, one nullable column on media, two widened check constraints. Nothing existing is
-- dropped, rewritten or renamed. Channel messages are their own rows (they are conversations, not Home Feed posts),
-- but they reuse the existing media pipeline, the existing reaction vocabulary, reports, notifications and jobs.

create table community_channel_groups (
  id uuid primary key default gen_random_uuid(),
  community_id uuid not null references communities(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 30),
  position int not null default 0,
  created_at timestamptz not null default now()
);
create unique index community_channel_groups_name on community_channel_groups (community_id, lower(name));
create index community_channel_groups_order on community_channel_groups (community_id, position, created_at, id);

create table community_channels (
  id uuid primary key default gen_random_uuid(),
  community_id uuid not null references communities(id) on delete cascade,
  -- Deleting a group never deletes its channels; they become ungrouped.
  group_id uuid references community_channel_groups(id) on delete set null,
  name text not null check (name ~ '^[a-z0-9][a-z0-9-]{0,29}$'),
  description text not null default '' check (char_length(description) <= 200),
  type text not null default 'text' check (type in ('text', 'announcement', 'qa', 'resources', 'media')),
  -- Who can see the channel, and who can start things in it. Staff = owner and moderators.
  view_role text not null default 'members' check (view_role in ('members', 'staff')),
  post_role text not null default 'members' check (post_role in ('members', 'staff')),
  -- Locked: read-only for ordinary members.
  locked boolean not null default false,
  replies_enabled boolean not null default true,
  allow_attachments boolean not null default true,
  position int not null default 0,
  -- Last used per-channel message number. Incremented under the row lock, so message numbers follow commit order
  -- and "everything after number N" can never skip a message that committed late.
  message_seq bigint not null default 0,
  last_message_at timestamptz,
  archived_at timestamptz,
  created_by uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (type <> 'announcement' or post_role = 'staff')
);
create unique index community_channels_name on community_channels (community_id, name);
create index community_channels_order on community_channels (community_id, position, created_at, id);

create table community_channel_messages (
  id uuid primary key default gen_random_uuid(),
  channel_id uuid not null references community_channels(id) on delete cascade,
  seq bigint not null,
  -- Null when the author's account was removed.
  author_id uuid references users(id) on delete set null,
  -- A retried send with the same client id returns the stored message instead of creating a second one.
  client_id text check (client_id is null or char_length(client_id) <= 64),
  -- Announcement titles and Q&A question titles. Null elsewhere.
  title text check (title is null or char_length(title) between 1 and 120),
  content text not null default '' check (char_length(content) <= 4000),
  -- Replies keep pointing at the original even after it is deleted (the row stays; only its content goes).
  reply_to_id uuid references community_channel_messages(id) on delete set null,
  -- deleted: removed by its author or channel staff. removed: removed by platform moderators.
  status text not null default 'active' check (status in ('active', 'deleted', 'removed')),
  pinned_at timestamptz,
  pinned_by uuid references users(id) on delete set null,
  -- Q&A: the reply that answered this question.
  accepted_answer_id uuid references community_channel_messages(id) on delete set null,
  created_at timestamptz not null default now(),
  edited_at timestamptz,
  deleted_at timestamptz,
  deleted_by uuid references users(id) on delete set null
);
create unique index community_channel_messages_seq on community_channel_messages (channel_id, seq);
create index community_channel_messages_page on community_channel_messages (channel_id, created_at desc, id desc);
create unique index community_channel_messages_idempotency on community_channel_messages (channel_id, author_id, client_id) where client_id is not null;
create index community_channel_messages_replies on community_channel_messages (reply_to_id, created_at, id) where reply_to_id is not null;
create index community_channel_messages_pins on community_channel_messages (channel_id, pinned_at desc) where pinned_at is not null;
create index community_channel_messages_author on community_channel_messages (channel_id, author_id, created_at desc);

-- Same reaction vocabulary as chat messages. A person can add several different reactions to one message, but each
-- kind only once: the primary key is the duplicate guard.
create table community_channel_reactions (
  message_id uuid not null references community_channel_messages(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  reaction text not null check (reaction in ('heart', 'like', 'laugh', 'fire', 'sad')),
  created_at timestamptz not null default now(),
  primary key (message_id, user_id, reaction)
);

create table community_channel_reads (
  channel_id uuid not null references community_channels(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  last_read_seq bigint not null default 0,
  updated_at timestamptz not null default now(),
  primary key (channel_id, user_id)
);
create index community_channel_reads_user on community_channel_reads (user_id);

-- Attachments use the existing upload flow (purpose 'message': photos and documents) and are linked to the channel message.
alter table media add column channel_message_id uuid references community_channel_messages(id) on delete cascade;
create index media_channel_message on media (channel_message_id, position) where channel_message_id is not null;

-- Notifications and reports reuse the existing tables; only the allowed values widen.
alter table notifications drop constraint notifications_type_check;
alter table notifications add constraint notifications_type_check check (type in (
  'follow', 'like', 'comment', 'reply', 'message', 'group_invite', 'verification', 'event_update',
  'community_join_request', 'community_join_approved', 'community_join_declined', 'community_invitation',
  'community_role_changed', 'community_member_removed', 'community_announcement', 'story_reaction',
  'community_channel_announcement'));

alter table reports drop constraint reports_target_type_check;
alter table reports add constraint reports_target_type_check check (target_type in (
  'user', 'post', 'comment', 'message', 'event', 'community', 'story', 'channel_message'));

-- Every existing community gets the two starter channels (new ones get them when they are created).
insert into community_channels (community_id, name, description, type, post_role, replies_enabled, position, created_by)
select c.id, 'announcements', 'Official updates from the moderators.', 'announcement', 'staff', false, 0, c.owner_id
  from communities c where c.status <> 'deleted';
insert into community_channels (community_id, name, description, type, position, created_by)
select c.id, 'general', 'Talk about anything related to the community.', 'text', 1, c.owner_id
  from communities c where c.status <> 'deleted';
