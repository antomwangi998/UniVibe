-- Login sessions are the existing refresh-token families. Each login gets a human label (for example "Pixel 8"),
-- supplied by the app and carried through every rotation. It is display-only: no decision is ever based on it.
-- No IP address or location is stored, so none can be shown.
alter table refresh_tokens add column device_label text not null default '' check (char_length(device_label) <= 80);
create index refresh_tokens_live on refresh_tokens (user_id, family_id) where revoked_at is null;

-- What a moderator tells the person about an action on their account, written for them to read.
-- moderation_actions.note stays internal and is never returned to the person it is about.
alter table moderation_actions add column user_message text not null default '' check (char_length(user_message) <= 300);
