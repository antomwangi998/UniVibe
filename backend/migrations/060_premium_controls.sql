-- 060_premium_controls.sql
-- Staff controls for Premium features.
--
-- premium_feature_flags is a kill switch per entitlement key. A missing row means "on". Switching a key off removes it
-- from everyone's entitlements at once (the app, the routes and the profile look function all read it), without
-- touching anyone's subscription or payment. Switching it back on restores access: nobody loses days they paid for.
create table premium_feature_flags (
  key text primary key check (key ~ '^[a-z]+\.[a-z_]+$'),
  enabled boolean not null default true,
  updated_by uuid references users(id) on delete set null,
  updated_at timestamptz not null default now()
);

-- The profile look function now also respects the switches (replaces the version from 059).
create or replace function profile_look_for(uid uuid) returns jsonb
language sql stable as $$
  select case when ent.themes or ent.frames then
    nullif(jsonb_strip_nulls(jsonb_build_object(
      'theme', case when ent.themes then pl.theme end,
      'accent', case when ent.themes then pl.accent end,
      'badge', case when ent.themes then pl.badge end,
      'frame', case when ent.frames then pl.frame end
    )), '{}'::jsonb)
  end
  from profile_looks pl
  cross join lateral (
    select coalesce(bool_or('premium.profile_themes' = any (p.entitlements)), false)
             and not exists (select 1 from premium_feature_flags f where f.key = 'premium.profile_themes' and not f.enabled) as themes,
           coalesce(bool_or('premium.profile_frames' = any (p.entitlements)), false)
             and not exists (select 1 from premium_feature_flags f where f.key = 'premium.profile_frames' and not f.enabled) as frames
      from subscriptions s join subscription_plans p on p.id = s.plan_id
     where s.user_id = pl.user_id and s.business_id is null and s.status = 'active'
       and s.period_start <= now() and s.period_end > now()
  ) ent
  where pl.user_id = uid
$$;
