-- 059_profile_look_fn.sql
-- One place that decides which profile look other people may see. Every query that sends a person (feed authors,
-- comments, follower lists, chat members, discover, search, notifications) selects this function's result, so
-- the rule is the same everywhere and costs one indexed lookup per person: most students have no look, and for
-- them it returns null straight away.
--
-- Rule (kept in step with effectiveLook in profile_look.ts): theme, accent and badge show only while the owner has a live
-- period whose plan includes premium.profile_themes; the frame only while a live period includes premium.profile_frames.
-- "Live" means active, started and not ended, by the database clock. A refunded period never counts.
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
    select coalesce(bool_or('premium.profile_themes' = any (p.entitlements)), false) as themes,
           coalesce(bool_or('premium.profile_frames' = any (p.entitlements)), false) as frames
      from subscriptions s join subscription_plans p on p.id = s.plan_id
     where s.user_id = pl.user_id and s.business_id is null and s.status = 'active'
       and s.period_start <= now() and s.period_end > now()
  ) ent
  where pl.user_id = uid
$$;
