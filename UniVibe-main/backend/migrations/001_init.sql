create table universities (
  id text primary key,
  name text not null,
  email_domains text[] not null
);

create table users (
  id uuid primary key default gen_random_uuid(),
  email text not null unique,
  full_name text not null,
  password_hash text not null,
  university_id text not null references universities(id),
  admission_number text,
  accepted_terms_at timestamptz not null,
  email_verified_at timestamptz,
  student_verified boolean not null default false,
  course text not null default '',
  interests text[] not null default '{}',
  bio text not null default '',
  has_photo boolean not null default false,
  onboarded_at timestamptz,
  created_at timestamptz not null default now()
);

-- Verification and password-reset codes. Only an HMAC of the code is stored.
-- pending_password_hash: the password chosen in the sign-up attempt that issued this code. It is applied only when
-- that exact code is confirmed, so someone who pre-registers another person's email cannot keep their password.
create table email_codes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  purpose text not null check (purpose in ('verify', 'reset')),
  code_hash text not null,
  pending_password_hash text,
  expires_at timestamptz not null,
  attempts int not null default 0,
  used_at timestamptz,
  created_at timestamptz not null default now()
);
create index email_codes_lookup on email_codes (user_id, purpose, created_at desc);

-- Refresh tokens rotate on every use. A token presented after it was rotated revokes its whole family.
create table refresh_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  family_id uuid not null,
  token_hash text not null unique,
  expires_at timestamptz not null,
  used_at timestamptz,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create index refresh_tokens_family on refresh_tokens (family_id);
create index refresh_tokens_user on refresh_tokens (user_id);
