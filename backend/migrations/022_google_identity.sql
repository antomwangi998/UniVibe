-- Sign in with Google.
--
-- 1. A Google-only account has no password, so users.password_hash may now be null. Every password check treats a null
--    hash as "no password set" (login answers the usual generic error; change-password and delete-account ask the person
--    to set one through "Forgot password" first). Existing rows are untouched.
-- 2. auth_identities links a UniVibe user to an identity at an external provider. The provider's stable subject id
--    (never the email address) is the key. (provider, provider_subject) is unique, so one Google account can never
--    belong to two UniVibe accounts, and (user_id, provider) is unique, so one UniVibe account has at most one Google login.
-- 3. auth_nonces holds single-use, short-lived nonces that the app puts into the Google request. A Google ID token is only
--    accepted together with a nonce this server issued, and each nonce works once, so a copied token cannot be replayed.
alter table users alter column password_hash drop not null;

create table auth_identities (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  provider text not null check (provider in ('google')),
  provider_subject text not null check (char_length(provider_subject) between 1 and 255),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint auth_identities_provider_subject_key unique (provider, provider_subject),
  constraint auth_identities_user_provider_key unique (user_id, provider)
);

create table auth_nonces (
  nonce_hash text primary key,
  purpose text not null check (purpose in ('google')),
  expires_at timestamptz not null
);
create index auth_nonces_expires on auth_nonces (expires_at);
