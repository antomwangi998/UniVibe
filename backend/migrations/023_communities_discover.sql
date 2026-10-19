-- Community mutes (works for non-members too), announcements, and the announcement notification type.
create table community_mutes (
  user_id uuid not null references users(id) on delete cascade,
  community_id uuid not null references communities(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, community_id)
);

alter table posts add column is_announcement boolean not null default false;
alter table posts add constraint posts_announcement_in_community check (not is_announcement or community_id is not null);

alter table notifications drop constraint notifications_type_check;
alter table notifications add constraint notifications_type_check check (type in (
  'follow', 'like', 'comment', 'reply', 'message', 'group_invite', 'verification', 'event_update',
  'community_join_request', 'community_join_approved', 'community_join_declined', 'community_invitation',
  'community_role_changed', 'community_member_removed', 'community_announcement'));

-- Suggestions look at recent community activity.
create index posts_community_recent on posts (community_id, created_at desc) where community_id is not null and status = 'active';
