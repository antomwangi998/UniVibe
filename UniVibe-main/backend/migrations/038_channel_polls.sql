-- Polls in channel messages (text and announcement channels). Same rules as feed polls (see src/polls.ts):
-- 2 to 4 options, single choice, one vote per student, closes within 3 days. Votes are anonymous.
alter table community_channel_messages add column poll_options text[];
alter table community_channel_messages add column poll_ends_at timestamptz;
alter table community_channel_messages add constraint community_channel_messages_poll_check
  check ((poll_options is null and poll_ends_at is null) or (cardinality(poll_options) between 2 and 4 and poll_ends_at is not null));

create table community_channel_poll_votes (
  message_id uuid not null references community_channel_messages(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  option_index int not null check (option_index between 0 and 3),
  created_at timestamptz not null default now(),
  -- One vote per student per poll, enforced here so two simultaneous taps cannot both count.
  primary key (message_id, user_id)
);
