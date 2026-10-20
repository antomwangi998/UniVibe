-- Story reactions, replies through chat, story reports, and the notification switch for reactions.

-- One reaction per person per Story (same names as message reactions). Gone with the Story or the person.
create table story_reactions (
  story_id uuid not null references stories(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  reaction text not null check (reaction in ('heart', 'like', 'laugh', 'fire', 'sad')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (story_id, user_id)
);
create index story_reactions_user on story_reactions (user_id);

-- A reply to a Story is an ordinary chat message that remembers which Story it answers. Only the id and the kind are
-- kept: the Story's own content is never copied into the chat, so when it expires nothing of it is left behind.
alter table messages
  add column story_id uuid references stories(id) on delete set null,
  add column story_kind text check (story_kind is null or story_kind in ('photo', 'video', 'text'));

-- Reactions notify the author like other social events, and can be switched off.
alter table notifications drop constraint notifications_type_check;
alter table notifications add constraint notifications_type_check check (type in (
  'follow', 'like', 'comment', 'reply', 'message', 'group_invite', 'verification', 'event_update',
  'community_join_request', 'community_join_approved', 'community_join_declined', 'community_invitation',
  'community_role_changed', 'community_member_removed', 'community_announcement', 'story_reaction'));
alter table notification_preferences add column story_reactions boolean not null default true;

-- A Story can be reported like a post or a comment.
alter table reports drop constraint reports_target_type_check;
alter table reports add constraint reports_target_type_check check (target_type in ('user', 'post', 'comment', 'message', 'event', 'community', 'story'));
