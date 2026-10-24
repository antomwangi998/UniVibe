-- Live location: a position one person keeps updating for a limited time, shown on a message in a chat.
-- Only the latest point is kept (no trail). It is cleared the moment sharing stops or runs out.
create table live_locations (
  message_id uuid primary key references messages(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  lat double precision check (lat is null or lat between -90 and 90),
  lng double precision check (lng is null or lng between -180 and 180),
  started_at timestamptz not null default now(),
  updated_at timestamptz,
  expires_at timestamptz not null,
  ended_at timestamptz
);
create index live_locations_active on live_locations (user_id) where ended_at is null;
create index live_locations_to_clear on live_locations (expires_at) where lat is not null;
