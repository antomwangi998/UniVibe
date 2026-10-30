-- 058_profile_looks.sql
-- Premium profile looks: a theme, an accent colour, an avatar frame and a small badge, shown on a student's profile
-- to everyone who may see that profile. Choices come from fixed lists (no free text), so nothing here can carry
-- an insult, a link or a fake "verified" mark. The server serves a look only while its owner's Premium is live.
create table profile_looks (
  user_id uuid primary key references users(id) on delete cascade,
  theme text check (theme is null or theme in ('aurora', 'ember', 'tide', 'noir', 'bloom', 'gold')),
  accent text check (accent is null or accent ~ '^#[0-9A-F]{6}$'),
  frame text check (frame is null or frame in ('ring', 'double', 'dashed', 'halo')),
  badge text check (badge is null or badge in ('spark', 'book', 'moon', 'coffee', 'flame', 'bolt')),
  updated_at timestamptz not null default now()
);

-- Profile themes and frames now work end to end, so the personal plans list them.
update subscription_plans
   set entitlements = array['premium.chat_themes', 'premium.profile_themes', 'premium.profile_frames']::text[], updated_at = now()
 where code in ('premium_monthly', 'premium_annual');
