-- Communities: first-class groups with their own membership, roles, requests and invitations.
-- Community posts are ordinary rows in `posts` (same ids, media, comments, likes) that carry a community_id.
-- Nothing here drops or rewrites existing data; every statement is additive or widens an existing check.

create table communities (
  id uuid primary key default gen_random_uuid(),
  university_id text not null references universities(id),
  name text not null check (char_length(name) between 3 and 40),
  -- lower(name) with whitespace collapsed: names are unique per university, case-insensitively.
  name_key text not null,
  slug text not null check (slug ~ '^[a-z0-9][a-z0-9-]{1,47}$'),
  description text not null check (char_length(description) between 10 and 300),
  category text not null check (category in ('academics', 'clubs', 'sports', 'arts', 'tech', 'career', 'social', 'wellbeing', 'other')),
  -- Who can SEE the community's posts. Public: everyone at the university. Private: members only.
  visibility text not null default 'public' check (visibility in ('public', 'private')),
  -- How people BECOME members. Kept separate from visibility on purpose.
  membership_policy text not null default 'open' check (membership_policy in ('open', 'approval', 'invite_only')),
  -- A private community may still show its name and description to non-members in discovery.
  discoverable boolean not null default true,
  post_permission text not null default 'members' check (post_permission in ('members', 'staff')),
  invite_permission text not null default 'staff' check (invite_permission in ('staff', 'members')),
  rules text[] not null default '{}' check (cardinality(rules) <= 8),
  avatar_media_id uuid references media(id) on delete set null,
  cover_media_id uuid references media(id) on delete set null,
  owner_id uuid not null references users(id),
  status text not null default 'active' check (status in ('active', 'archived', 'suspended', 'deleted')),
  member_count int not null default 0 check (member_count >= 0),
  -- Client-generated idempotency key: a retried create returns the same community instead of a duplicate.
  create_key text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (visibility = 'public' or membership_policy <> 'open'),
  check (visibility = 'private' or membership_policy = 'open' or membership_policy = 'approval')
);
create unique index communities_name_unique on communities (university_id, name_key) where status <> 'deleted';
create unique index communities_slug_unique on communities (university_id, slug) where status <> 'deleted';
create unique index communities_create_key on communities (owner_id, create_key) where create_key is not null;
create index communities_browse on communities (university_id, status, created_at desc, id desc);
create index communities_owner on communities (owner_id);

create table community_members (
  community_id uuid not null references communities(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'moderator', 'member')),
  status text not null default 'active' check (status in ('active', 'suspended')),
  -- Per-community notification level (all activity down to muted).
  notification_level text not null default 'mentions' check (notification_level in ('all', 'highlights', 'mentions', 'announcements', 'muted')),
  joined_at timestamptz not null default now(),
  primary key (community_id, user_id)
);
create index community_members_user on community_members (user_id, joined_at desc);
create index community_members_list on community_members (community_id, joined_at desc, user_id);
-- Exactly one owner per community, enforced by the database.
create unique index community_members_one_owner on community_members (community_id) where role = 'owner';

create table community_requests (
  id uuid primary key default gen_random_uuid(),
  community_id uuid not null references communities(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'approved', 'rejected', 'cancelled')),
  decided_by uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  decided_at timestamptz
);
create unique index community_requests_one_pending on community_requests (community_id, user_id) where status = 'pending';
create index community_requests_queue on community_requests (community_id, status, created_at desc, id desc);

create table community_invitations (
  id uuid primary key default gen_random_uuid(),
  community_id uuid not null references communities(id) on delete cascade,
  inviter_id uuid references users(id) on delete set null,
  invitee_id uuid not null references users(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'declined', 'revoked', 'expired')),
  expires_at timestamptz not null default now() + interval '14 days',
  created_at timestamptz not null default now(),
  decided_at timestamptz
);
create unique index community_invitations_one_pending on community_invitations (community_id, invitee_id) where status = 'pending';
create index community_invitations_inbox on community_invitations (invitee_id, status, created_at desc, id desc);

-- Community posts are the same posts: one nullable association, no second post table.
alter table posts add column community_id uuid references communities(id) on delete set null;
alter table posts drop constraint posts_audience_check;
alter table posts add constraint posts_audience_check check (audience in ('campus', 'followers', 'community'));
alter table posts add constraint posts_community_consistent check ((audience = 'community') = (community_id is not null));
create index posts_community on posts (community_id, created_at desc, id desc) where community_id is not null;

-- Notifications reuse the existing table and delivery job; only the allowed types widen.
alter table notifications drop constraint notifications_type_check;
alter table notifications add constraint notifications_type_check check (type in (
  'follow', 'like', 'comment', 'reply', 'message', 'group_invite', 'verification', 'event_update',
  'community_join_request', 'community_join_approved', 'community_join_declined', 'community_invitation',
  'community_role_changed', 'community_member_removed'));
-- Switch for optional community social notifications. Role and removal notices are always delivered.
alter table notification_preferences add column communities boolean not null default true;

-- Communities can be reported through the existing reports queue.
alter table reports drop constraint reports_target_type_check;
alter table reports add constraint reports_target_type_check check (target_type in ('user', 'post', 'comment', 'message', 'event', 'community'));

-- Community avatars and covers use the existing media pipeline.
alter table media drop constraint media_purpose_check;
alter table media add constraint media_purpose_check check (purpose in ('post', 'avatar', 'cover', 'message', 'event', 'group', 'community'));
