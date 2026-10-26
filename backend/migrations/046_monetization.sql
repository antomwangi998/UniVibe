-- Monetization, stage 2: businesses, packages, orders, payments, refunds, campaigns.
-- Additive only. Nothing here changes an existing table, so events, posts and feeds keep working untouched.
-- Conventions follow the rest of the schema: lower-case status words, timestamptz in UTC, uuid keys.
-- Money is stored as integer minor units (KES cents), never as floating point.
-- Financial rows must outlive an account, so user references are ON DELETE SET NULL (never cascade).

-- ---------------------------------------------------------------- businesses
create table businesses (
  id uuid primary key default gen_random_uuid(),
  university_id text not null references universities(id),
  name text not null check (char_length(name) between 2 and 80),
  description text not null default '' check (char_length(description) <= 1000),
  category text not null check (category in ('food', 'retail', 'services', 'education', 'health', 'tech', 'entertainment', 'housing', 'transport', 'other')),
  logo_media_id uuid references media(id) on delete set null,
  cover_media_id uuid references media(id) on delete set null,
  location_text text check (location_text is null or char_length(location_text) <= 160),
  contact_phone text check (contact_phone is null or char_length(contact_phone) between 7 and 20),
  contact_email text check (contact_email is null or char_length(contact_email) <= 254),
  website_url text check (website_url is null or (char_length(website_url) <= 500 and website_url ~ '^https://')),
  opening_hours jsonb not null default '{}'::jsonb,
  offerings text not null default '' check (char_length(offerings) <= 1000),
  -- Listing workflow. A free listing is visible once approved; paid placement is a separate campaign.
  status text not null default 'draft' check (status in ('draft', 'pending_review', 'approved', 'rejected', 'suspended')),
  -- Verification is a staff decision about the business's identity, separate from listing approval.
  verification text not null default 'unverified' check (verification in ('unverified', 'verified')),
  review_note text not null default '' check (char_length(review_note) <= 500),
  reviewed_by uuid references users(id) on delete set null,
  reviewed_at timestamptz,
  created_by uuid references users(id) on delete set null,
  create_key text check (create_key is null or char_length(create_key) between 8 and 64),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index businesses_directory on businesses (university_id, category, name) where status = 'approved';
create index businesses_review on businesses (status, created_at) where status = 'pending_review';

-- Who may manage a business. Creating a listing makes you its owner; it grants nothing beyond that listing.
create table business_members (
  business_id uuid not null references businesses(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  role text not null check (role in ('owner', 'manager')),
  created_at timestamptz not null default now(),
  primary key (business_id, user_id)
);
create index business_members_user on business_members (user_id);
create unique index businesses_create_key on businesses (created_by, create_key) where create_key is not null;

create table business_offers (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references businesses(id) on delete cascade,
  title text not null check (char_length(title) between 3 and 80),
  details text not null default '' check (char_length(details) <= 500),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  status text not null default 'active' check (status in ('active', 'removed')),
  created_at timestamptz not null default now(),
  check (ends_at > starts_at)
);
create index business_offers_live on business_offers (business_id, ends_at) where status = 'active';

-- ---------------------------------------------------------------- settings (where to pay, etc.)
-- Key/value, edited only by staff with packages.manage, every change audited.
-- 'manual_payment' holds the interim "where to pay" instructions used until a payment provider is connected.
create table monetization_settings (
  key text primary key check (key ~ '^[a-z_]{2,40}$'),
  value jsonb not null,
  updated_by uuid references users(id) on delete set null,
  updated_at timestamptz not null default now()
);
-- Disabled until staff enter a real till number: no fake default destination is ever shown.
insert into monetization_settings (key, value) values
  ('manual_payment', '{"enabled": false, "method": "till", "number": "", "accountName": "", "instructions": ""}'::jsonb);

-- ---------------------------------------------------------------- packages
create table promotion_packages (
  id uuid primary key default gen_random_uuid(),
  code text not null unique check (code ~ '^[a-z0-9_-]{3,40}$'),
  kind text not null check (kind in ('event_promotion', 'featured_business', 'sponsored_post', 'featured_discover', 'business_plan')),
  name text not null check (char_length(name) between 3 and 60),
  description text not null default '' check (char_length(description) <= 500),
  currency text not null default 'KES' check (currency ~ '^[A-Z]{3}$'),
  price_minor bigint not null check (price_minor >= 0),
  duration_days int not null check (duration_days between 1 and 90),
  placements text[] not null check (cardinality(placements) >= 1 and placements <@ array['home_feed', 'discover_feed', 'discover_events', 'business_directory']::text[]),
  -- Delivery limits. Null means unlimited within the duration.
  impression_cap int check (impression_cap is null or impression_cap > 0),
  frequency_cap_per_day int not null default 3 check (frequency_cap_per_day between 1 and 20),
  active boolean not null default true,
  created_by uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index promotion_packages_available on promotion_packages (kind) where active;

-- ---------------------------------------------------------------- orders and payments
-- An order freezes the price and terms at creation (package_snapshot), so a later price change never alters it.
create table orders (
  id uuid primary key default gen_random_uuid(),
  reference text not null unique check (reference ~ '^UV-[A-Z0-9]{8}$'),
  advertiser_id uuid references users(id) on delete set null,
  package_id uuid references promotion_packages(id) on delete restrict,
  package_snapshot jsonb not null,
  amount_minor bigint not null check (amount_minor >= 0),
  currency text not null check (currency ~ '^[A-Z]{3}$'),
  status text not null default 'pending_payment' check (status in ('pending_payment', 'paid', 'cancelled', 'expired', 'refunded')),
  idempotency_key text check (idempotency_key is null or char_length(idempotency_key) between 8 and 64),
  expires_at timestamptz not null,
  paid_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index orders_idempotency on orders (advertiser_id, idempotency_key) where idempotency_key is not null;
create index orders_advertiser on orders (advertiser_id, created_at desc);
create index orders_open on orders (expires_at) where status = 'pending_payment';

-- provider: 'manual_till' = the advertiser paid to the till and a staff member confirmed it against the M-Pesa
-- statement; 'mpesa_daraja' is reserved for the provider integration and is never written until it exists.
-- A payment is 'confirmed' only by a staff action (manual) or a verified provider response (Daraja). Never by the app.
create table payments (
  id uuid primary key default gen_random_uuid(),
  order_id uuid not null references orders(id) on delete restrict,
  provider text not null check (provider in ('manual_till', 'mpesa_daraja')),
  status text not null default 'awaiting_verification' check (status in ('pending', 'awaiting_verification', 'confirmed', 'failed', 'refunded')),
  amount_minor bigint not null check (amount_minor >= 0),
  currency text not null check (currency ~ '^[A-Z]{3}$'),
  -- The M-Pesa receipt code (e.g. "QGH7XXXXXX"), normalised to upper case. The advertiser's claim until staff confirm.
  provider_reference text check (provider_reference is null or provider_reference ~ '^[A-Z0-9]{8,20}$'),
  checkout_request_id text,
  received_minor bigint check (received_minor is null or received_minor >= 0),
  confirmed_by uuid references users(id) on delete set null,
  confirmed_at timestamptz,
  failure_reason text check (failure_reason is null or char_length(failure_reason) <= 300),
  reconciliation text not null default 'unreconciled' check (reconciliation in ('unreconciled', 'reconciled', 'mismatch')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
-- One receipt code can back one payment only: a code cannot be reused for a second order, and a repeated
-- submission or callback cannot create a second transaction.
create unique index payments_receipt_once on payments (provider, provider_reference) where provider_reference is not null and status <> 'failed';
create unique index payments_checkout_once on payments (checkout_request_id) where checkout_request_id is not null;
create index payments_order on payments (order_id);
create index payments_queue on payments (status, created_at) where status in ('pending', 'awaiting_verification');

-- Everything that happens to a payment, in order. Appended to, never edited.
create table payment_events (
  id bigserial primary key,
  payment_id uuid not null references payments(id) on delete cascade,
  kind text not null check (char_length(kind) between 2 and 40),
  actor_id uuid references users(id) on delete set null,
  detail jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index payment_events_payment on payment_events (payment_id, id);

create table refunds (
  id uuid primary key default gen_random_uuid(),
  payment_id uuid not null references payments(id) on delete restrict,
  order_id uuid not null references orders(id) on delete restrict,
  amount_minor bigint not null check (amount_minor > 0),
  reason text not null check (char_length(reason) between 3 and 500),
  status text not null default 'requested' check (status in ('requested', 'approved', 'rejected', 'paid_out')),
  requested_by uuid references users(id) on delete set null,
  decided_by uuid references users(id) on delete set null,
  decided_at timestamptz,
  decision_note text not null default '' check (char_length(decision_note) <= 500),
  -- The reversal's M-Pesa receipt or other reference, entered by the person who paid it out.
  payout_reference text check (payout_reference is null or char_length(payout_reference) <= 60),
  created_at timestamptz not null default now()
);
-- At most one open refund per payment, so a repeated click cannot queue two.
create unique index refunds_one_open on refunds (payment_id) where status in ('requested', 'approved');
create index refunds_queue on refunds (status, created_at) where status in ('requested', 'approved');

-- ---------------------------------------------------------------- campaigns
create table campaigns (
  id uuid primary key default gen_random_uuid(),
  advertiser_id uuid references users(id) on delete set null,
  kind text not null check (kind in ('event_promotion', 'business_listing', 'sponsored_post', 'discover_feature')),
  business_id uuid references businesses(id) on delete set null,
  -- Promotes the existing event. No copy of the event is made; its own visibility and lifecycle rules still decide.
  event_id uuid references events(id) on delete set null,
  post_id uuid references posts(id) on delete set null,
  package_id uuid references promotion_packages(id) on delete restrict,
  order_id uuid unique references orders(id) on delete restrict,
  headline text not null check (char_length(headline) between 3 and 80),
  body text not null default '' check (char_length(body) <= 300),
  image_media_id uuid references media(id) on delete set null,
  cta_label text check (cta_label is null or char_length(cta_label) between 2 and 24),
  cta_url text check (cta_url is null or (char_length(cta_url) <= 500 and cta_url ~ '^https://')),
  placements text[] not null check (cardinality(placements) >= 1 and placements <@ array['home_feed', 'discover_feed', 'discover_events', 'business_directory']::text[]),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  status text not null default 'draft' check (status in ('draft', 'pending_payment', 'pending_review', 'approved', 'scheduled', 'active', 'paused', 'completed', 'rejected', 'cancelled', 'refund_pending', 'refunded')),
  -- Review is tracked on its own: payment, review and schedule are three separate conditions.
  review_status text not null default 'pending' check (review_status in ('pending', 'approved', 'rejected')),
  review_note text not null default '' check (char_length(review_note) <= 500),
  reviewed_by uuid references users(id) on delete set null,
  reviewed_at timestamptz,
  paused_by text check (paused_by is null or paused_by in ('advertiser', 'admin')),
  -- Copied from the package at order time so delivery never reads a price list that may have changed.
  impression_cap int check (impression_cap is null or impression_cap > 0),
  frequency_cap_per_day int not null default 3 check (frequency_cap_per_day between 1 and 20),
  delivered_impressions bigint not null default 0 check (delivered_impressions >= 0),
  delivered_clicks bigint not null default 0 check (delivered_clicks >= 0),
  create_key text check (create_key is null or char_length(create_key) between 8 and 64),
  status_changed_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (ends_at > starts_at),
  check (kind <> 'event_promotion' or event_id is not null or status in ('cancelled', 'refunded', 'completed')),
  check (kind <> 'business_listing' or business_id is not null or status in ('cancelled', 'refunded', 'completed')),
  check (kind <> 'sponsored_post' or post_id is not null or business_id is not null or status in ('cancelled', 'refunded', 'completed'))
);
create unique index campaigns_create_key on campaigns (advertiser_id, create_key) where create_key is not null;
create index campaigns_advertiser on campaigns (advertiser_id, created_at desc);
create index campaigns_review on campaigns (status, created_at) where status = 'pending_review';
-- Delivery reads only campaigns that can possibly be live, so this stays small however much history builds up.
create index campaigns_live on campaigns (starts_at, ends_at) where status in ('scheduled', 'active');
create index campaigns_event on campaigns (event_id) where event_id is not null;
-- One open promotion per event, so the same event cannot be bought twice at once.
create unique index campaigns_one_open_per_event on campaigns (event_id)
  where kind = 'event_promotion' and status in ('draft', 'pending_payment', 'pending_review', 'approved', 'scheduled', 'active', 'paused', 'refund_pending');

-- Every status change, who made it and why. Campaign history in the admin dashboard reads this.
create table campaign_status_history (
  id bigserial primary key,
  campaign_id uuid not null references campaigns(id) on delete cascade,
  from_status text,
  to_status text not null,
  actor_id uuid references users(id) on delete set null,
  actor_kind text not null check (actor_kind in ('advertiser', 'admin', 'system')),
  note text not null default '' check (char_length(note) <= 500),
  created_at timestamptz not null default now()
);
create index campaign_status_history_campaign on campaign_status_history (campaign_id, id);
