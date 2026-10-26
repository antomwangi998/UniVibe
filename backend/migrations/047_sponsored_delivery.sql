-- Monetization, stage 7: delivery tracking and ad reports.
-- campaign_events records that a sponsored card was SHOWN or TAPPED. It stores a pseudonymous viewer key
-- (a keyed hash of the student id, not the id itself) so frequency caps and click de-duplication work without
-- keeping a readable list of who saw what. Advertisers only ever receive aggregate counts.
create table campaign_events (
  id bigserial primary key,
  campaign_id uuid not null references campaigns(id) on delete cascade,
  kind text not null check (kind in ('impression', 'click')),
  viewer_key text not null check (char_length(viewer_key) between 16 and 64),
  placement text not null check (placement in ('home_feed', 'discover_feed', 'discover_events', 'business_directory')),
  -- One server-issued token per card served. The same token can be counted once per kind, so a replayed request does nothing.
  nonce text not null check (char_length(nonce) between 16 and 64),
  created_at timestamptz not null default now()
);
create unique index campaign_events_once on campaign_events (campaign_id, nonce, kind);
create index campaign_events_frequency on campaign_events (campaign_id, viewer_key, created_at) where kind = 'impression';
create index campaign_events_daily on campaign_events (viewer_key, created_at) where kind = 'impression';
create index campaign_events_click_gap on campaign_events (campaign_id, viewer_key, created_at) where kind = 'click';
create index campaign_events_time on campaign_events (campaign_id, created_at);

-- "Report this ad". Three open reports from different people hold a campaign back from delivery until staff look at it.
create table campaign_reports (
  id uuid primary key default gen_random_uuid(),
  campaign_id uuid not null references campaigns(id) on delete cascade,
  reporter_id uuid references users(id) on delete set null,
  reason text not null check (reason in ('misleading', 'inappropriate', 'spam', 'scam', 'offensive', 'other')),
  details text not null default '' check (char_length(details) <= 500),
  status text not null default 'open' check (status in ('open', 'upheld', 'dismissed')),
  reviewed_by uuid references users(id) on delete set null,
  reviewed_at timestamptz,
  created_at timestamptz not null default now()
);
create unique index campaign_reports_once on campaign_reports (campaign_id, reporter_id) where reporter_id is not null;
create index campaign_reports_open on campaign_reports (campaign_id) where status = 'open';
create index campaign_reports_queue on campaign_reports (status, created_at);
