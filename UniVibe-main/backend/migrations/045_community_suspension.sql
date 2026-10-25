-- Platform staff can suspend a community (the app already treats status 'suspended' as unavailable) and put it back.
alter table communities
  add column suspended_at timestamptz,
  add column suspended_by uuid references users(id) on delete set null,
  add column suspended_from text check (suspended_from in ('active', 'archived'));

alter table moderation_actions drop constraint moderation_actions_action_check;
alter table moderation_actions add constraint moderation_actions_action_check
  check (action in ('remove_content', 'restore_content', 'suspend_user', 'unsuspend_user', 'hide_event', 'restore_event', 'suspend_community', 'restore_community'));
