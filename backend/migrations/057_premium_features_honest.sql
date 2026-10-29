-- 057_premium_features_honest.sql
-- A plan may list only features that work end to end. As of this migration the only finished Premium feature is
-- chat themes. Keys are added back here, one migration per shipped feature, never ahead of the feature.
-- Plans stay inactive; staff switch one on (PATCH /admin/monetization/plans/:id) when ready.
update subscription_plans set entitlements = array['premium.chat_themes']::text[], updated_at = now()
 where code in ('premium_monthly', 'premium_annual');
update subscription_plans set entitlements = '{}'::text[], updated_at = now() where code = 'business_premium';
