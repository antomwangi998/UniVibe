-- 061_personalization_core.sql
-- UniVibe Personalization Studio, first slice: a staff-curated image catalogue (themes / wallpapers), personal
-- favourites and private collections, and a personal chat appearance that is saved on the server.
--
-- Safe on existing data: only adds tables, and widens one check constraint (the list of known entitlement keys).
-- No image bytes live here. Rows point at objects that already went through the existing /media pipeline (R2).
-- Nothing is added to any subscription plan: a key is added to a plan by a later migration, once its feature
-- works end to end in the app (see 057).

-- New entitlement keys the server may know about. Plans still list only what works today.
alter table subscription_plans drop constraint if exists subscription_plans_entitlements_check;
alter table subscription_plans add constraint subscription_plans_entitlements_check check (entitlements <@ array[
  'premium.profile_themes', 'premium.profile_frames', 'premium.chat_themes', 'premium.sticker_packs',
  'premium.creator_analytics', 'premium.post_scheduling', 'premium.advanced_feed_controls',
  'premium.wallpaper_collections', 'premium.animated_stickers', 'premium.story_templates', 'premium.note_themes',
  'business.analytics', 'business.offers', 'business.scheduled_posts'
]::text[]);

create table theme_categories (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique check (slug ~ '^[a-z0-9-]{2,40}$'),
  name text not null check (char_length(name) between 2 and 40),
  sort_order int not null default 0,
  created_at timestamptz not null default now()
);

insert into theme_categories (slug, name, sort_order) values
  ('nature', 'Nature', 10), ('aesthetic', 'Aesthetic', 20), ('dark', 'Dark', 30), ('anime', 'Anime', 40),
  ('animals', 'Animals', 50), ('space', 'Space', 60), ('abstract', 'Abstract', 70), ('minimalist', 'Minimalist', 80),
  ('architecture', 'Architecture', 90), ('campus', 'Campus', 100), ('photography', 'Photography', 110),
  ('art', 'Art', 120), ('gaming', 'Gaming', 130), ('technology', 'Technology', 140), ('seasonal', 'Seasonal', 150),
  ('univibe', 'UniVibe originals', 160);

-- One published row per image. `premium` decides who may SAVE or APPLY it; anyone may preview the thumbnail.
-- Rights are explicit: no row can be published without a licence and a source line, so nothing is added "from the web".
create table theme_assets (
  id uuid primary key default gen_random_uuid(),
  category_id uuid not null references theme_categories(id) on delete restrict,
  title text not null check (char_length(title) between 2 and 80),
  description text not null default '' check (char_length(description) <= 500),
  creator_id uuid references users(id) on delete set null,
  source_attribution text not null check (char_length(source_attribution) between 2 and 200),
  license text not null check (license in ('univibe_original', 'licensed_provider', 'creator_submission')),
  object_key text not null,          -- full image, only ever served as a short-lived signed link
  thumb_key text not null,           -- preview image, served to everyone who can see the catalogue
  width int not null check (width between 1 and 20000),
  height int not null check (height between 1 and 20000),
  premium boolean not null default false,
  destinations text[] not null default '{profile_cover,chat_background,story_background,note_background}'
    check (destinations <@ array['profile_cover','chat_background','story_background','note_background','avatar_decoration']::text[]),
  status text not null default 'draft' check (status in ('draft', 'published', 'unpublished', 'removed')),
  created_by uuid references users(id) on delete set null,
  published_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (status <> 'published' or published_at is not null)
);
create index theme_assets_catalogue on theme_assets (category_id, published_at desc, id desc) where status = 'published';
create index theme_assets_recent on theme_assets (published_at desc, id desc) where status = 'published';

create table theme_favourites (
  user_id uuid not null references users(id) on delete cascade,
  asset_id uuid not null references theme_assets(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, asset_id)
);
create index theme_favourites_user_recent on theme_favourites (user_id, created_at desc);

-- Private by construction: every query filters on user_id, and the only ids exposed are this person's own.
create table theme_collections (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 40),
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index theme_collections_name on theme_collections (user_id, lower(name)) where deleted_at is null;
create index theme_collections_user on theme_collections (user_id, created_at desc) where deleted_at is null;

create table theme_collection_items (
  collection_id uuid not null references theme_collections(id) on delete cascade,
  asset_id uuid not null references theme_assets(id) on delete cascade,
  added_at timestamptz not null default now(),
  primary key (collection_id, asset_id)
);
create index theme_collection_items_recent on theme_collection_items (collection_id, added_at desc);

-- A person's own chat look. conversation_id null = my default for every chat; a row with a conversation overrides it.
-- It only changes what THIS person sees. The other member's screen is never touched.
create table user_chat_appearance (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  conversation_id uuid references conversations(id) on delete cascade,
  theme_id text not null default 'default' check (theme_id ~ '^[a-z0-9_]{1,32}$'),   -- existing app ids, e.g. 'default', 'p_aurora'
  wallpaper_id text not null default 'none' check (wallpaper_id ~ '^[a-z0-9_]{1,32}$'),
  wallpaper_asset_id uuid references theme_assets(id) on delete set null,
  blur int not null default 0 check (blur between 0 and 25),
  dim int not null default 0 check (dim between 0 and 80),
  bubble_shape text not null default 'rounded' check (bubble_shape in ('rounded', 'soft', 'sharp', 'pill')),
  updated_at timestamptz not null default now()
);
create unique index user_chat_appearance_one on user_chat_appearance (user_id, coalesce(conversation_id, '00000000-0000-0000-0000-000000000000'::uuid));
