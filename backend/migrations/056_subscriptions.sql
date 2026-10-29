-- 056_subscriptions.sql
-- UniVibe Premium (students) and Premium Business subscriptions.
--
-- Reuses the existing `orders`, `payments`, `payment_events` and `refunds` tables (046/048): a subscription is
-- bought with an order exactly like a promotion is, so receipt claims, staff confirmation, mismatch handling,
-- duplicate-receipt protection and refunds all work unchanged. `promotion_packages` is not touched.
--
-- Honest scope: with manual M-Pesa confirmation there is no automatic renewal. One paid order buys ONE period.
-- Buying again extends from the current end. Nothing here invents a renewal the provider has not confirmed.
--
-- Safe to run on existing data: only adds tables, one nullable column and one constraint relaxation.

create table subscription_plans (
  id uuid primary key default gen_random_uuid(),
  code text not null unique check (code ~ '^[a-z0-9_-]{3,40}$'),
  audience text not null check (audience in ('user', 'business')),
  name text not null check (char_length(name) between 3 and 60),
  description text not null default '' check (char_length(description) <= 500),
  currency text not null default 'KES' check (currency ~ '^[A-Z]{3}$'),
  price_minor bigint not null check (price_minor > 0),
  period_days int not null check (period_days between 1 and 400),
  -- Keys must be known to the server (src/modules/monetization/entitlements.ts). Checked here too so a typo cannot ship.
  entitlements text[] not null default '{}' check (entitlements <@ array[
    'premium.profile_themes', 'premium.profile_frames', 'premium.chat_themes', 'premium.sticker_packs',
    'premium.creator_analytics', 'premium.post_scheduling', 'premium.advanced_feed_controls',
    'business.analytics', 'business.offers', 'business.scheduled_posts'
  ]::text[]),
  -- Plans ship INACTIVE. A plan is switched on by staff only once every feature it lists works end to end,
  -- so the app can never sell something that is not built.
  active boolean not null default false,
  sort_order int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- One row per paid period. Access is derived from period_start/period_end, never from a stored "is premium" flag.
create table subscriptions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  business_id uuid references businesses(id) on delete cascade,
  plan_id uuid not null references subscription_plans(id) on delete restrict,
  order_id uuid not null unique references orders(id) on delete restrict, -- idempotency: one order activates once
  status text not null default 'active' check (status in ('active', 'refunded')),
  period_start timestamptz not null,
  period_end timestamptz not null,
  created_at timestamptz not null default now(),
  check (period_end > period_start)
);
create index subscriptions_user_live on subscriptions (user_id, period_end desc) where status = 'active';
create index subscriptions_business_live on subscriptions (business_id, period_end desc) where status = 'active' and business_id is not null;

alter table orders add column plan_id uuid references subscription_plans(id) on delete restrict;
alter table orders add column business_id uuid references businesses(id) on delete set null;
create index orders_plan on orders (plan_id) where plan_id is not null;

insert into subscription_plans (code, audience, name, description, price_minor, period_days, entitlements, sort_order) values
  ('premium_monthly', 'user', 'UniVibe Premium - monthly', 'One month of Premium. Does not renew by itself.', 9900, 30,
    array['premium.profile_themes', 'premium.profile_frames', 'premium.chat_themes', 'premium.sticker_packs',
          'premium.creator_analytics', 'premium.post_scheduling', 'premium.advanced_feed_controls'], 10),
  ('premium_annual', 'user', 'UniVibe Premium - annual', 'Twelve months of Premium. Does not renew by itself.', 79900, 365,
    array['premium.profile_themes', 'premium.profile_frames', 'premium.chat_themes', 'premium.sticker_packs',
          'premium.creator_analytics', 'premium.post_scheduling', 'premium.advanced_feed_controls'], 20),
  ('business_premium', 'business', 'Premium Business - monthly', 'One month of Premium Business for one business. Does not renew by itself.', 49900, 30,
    array['business.analytics', 'business.offers', 'business.scheduled_posts'], 30);
