-- Account deactivation: a person hides their account without deleting it. Logging in again brings it back.
-- A null value means the account is active. This is separate from suspended_until, which only moderators set.
alter table users add column if not exists deactivated_at timestamptz;
