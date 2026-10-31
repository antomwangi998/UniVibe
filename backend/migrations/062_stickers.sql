-- 062_stickers.sql
-- Sticker packs, installed packs, favourites, recents, and the sticker message type.
-- Only adds tables and columns; existing messages and media are untouched.
-- Image bytes live in R2 (uploaded through /media with purpose 'sticker', staff only). The database stores keys and metadata.

-- Staff upload stickers through the existing signed-upload flow, so the media table must accept the new purpose.
alter table media drop constraint media_purpose_check;
alter table media add constraint media_purpose_check check (purpose in ('post', 'avatar', 'cover', 'message', 'event', 'group', 'community', 'story', 'sticker'));

create table sticker_packs (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique check (slug ~ '^[a-z0-9-]{3,60}$'),
  name text not null check (char_length(name) between 2 and 60),
  description text not null default '' check (char_length(description) <= 300),
  category text not null check (category in ('campus_vibes', 'funny_reactions', 'emotions', 'love_friendship', 'study_mode', 'tech_gaming', 'univibe_originals', 'seasonal')),
  creator_id uuid references users(id) on delete set null,
  cover_key text not null,
  cover_thumb_key text not null,
  premium boolean not null default false,     -- needs premium.sticker_packs to install and to send
  featured boolean not null default false,
  sort_order int not null default 0,
  -- Rights are explicit, as for theme assets: nothing is published without a licence and a source line.
  license text not null check (license in ('univibe_original', 'licensed_provider', 'creator_submission')),
  source_attribution text not null check (char_length(source_attribution) between 2 and 200),
  status text not null default 'draft' check (status in ('draft', 'published', 'unpublished', 'removed')),
  created_by uuid references users(id) on delete set null,
  published_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (status <> 'published' or published_at is not null)
);
create index sticker_packs_store on sticker_packs (published_at desc, id desc) where status = 'published';
create index sticker_packs_category on sticker_packs (category, published_at desc) where status = 'published';

-- Never hard-deleted: a message that used the sticker still points at it, and shows "unavailable" once it is removed.
create table stickers (
  id uuid primary key default gen_random_uuid(),
  pack_id uuid not null references sticker_packs(id) on delete cascade,
  position int not null default 0,
  label text check (label is null or char_length(label) between 1 and 40),
  keywords text[] not null default '{}' check (cardinality(keywords) <= 8),
  object_key text not null,
  thumb_key text,
  width int not null check (width between 1 and 512),
  height int not null check (height between 1 and 512),
  animated boolean not null default false,
  size_bytes int not null check (size_bytes > 0),
  status text not null default 'active' check (status in ('active', 'removed')),
  created_at timestamptz not null default now()
);
create index stickers_pack on stickers (pack_id, position, id) where status = 'active';

-- Installing is a row; uninstalling flips it (the row stays, so a re-install is one tap and nothing is re-bought).
-- acquired_via 'purchase' is reserved for per-pack sales; nothing writes it until a verified payment flow exists.
create table user_sticker_packs (
  user_id uuid not null references users(id) on delete cascade,
  pack_id uuid not null references sticker_packs(id) on delete cascade,
  status text not null default 'installed' check (status in ('installed', 'uninstalled')),
  acquired_via text not null default 'free' check (acquired_via in ('free', 'subscription', 'purchase')),
  pack_favourite boolean not null default false,
  installed_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, pack_id)
);
create index user_sticker_packs_user on user_sticker_packs (user_id, status, installed_at desc);

create table user_sticker_favourites (
  user_id uuid not null references users(id) on delete cascade,
  sticker_id uuid not null references stickers(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, sticker_id)
);
create index user_sticker_favourites_recent on user_sticker_favourites (user_id, created_at desc);

create table user_recent_stickers (
  user_id uuid not null references users(id) on delete cascade,
  sticker_id uuid not null references stickers(id) on delete cascade,
  last_used_at timestamptz not null default now(),
  use_count int not null default 1,
  primary key (user_id, sticker_id)
);
create index user_recent_stickers_recent on user_recent_stickers (user_id, last_used_at desc);

-- The sticker message type: only the id is stored. ON DELETE RESTRICT keeps "which sticker was this" answerable forever.
alter table messages add column sticker_id uuid references stickers(id) on delete restrict;
alter table messages add constraint messages_sticker_alone check (sticker_id is null or content = '');
create index messages_sticker on messages (sticker_id) where sticker_id is not null;
