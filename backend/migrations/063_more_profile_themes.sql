-- 063_more_profile_themes.sql
-- Four more Premium profile themes: nature, midnight, galaxy, minimal. Same fixed-list rule as before (no free text).
-- Only widens one check; every saved look stays valid. Keep in step with THEMES in profile_look.ts and ProfileLookStyle.kt.
-- An older app that meets a theme it does not know shows the default cover, so this is safe to deploy before the app update.
alter table profile_looks drop constraint if exists profile_looks_theme_check;
alter table profile_looks add constraint profile_looks_theme_check
  check (theme is null or theme in ('aurora', 'ember', 'tide', 'noir', 'bloom', 'gold', 'nature', 'midnight', 'galaxy', 'minimal'));
