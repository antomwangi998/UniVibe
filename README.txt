UniVibe settings - patch 1 (registry, hub + search, security/login activity, account status, backend sessions)

Apply:
  A) Copy files/ over your project (whole files), OR
  B) cd UniVibe-main && patch -p0 < univibe-settings-phase1.diff   (use this if you edited the same files since the upload)

Backend: new migration 013_sessions_account_status.sql (applies automatically through migrate()).
Verify (not run in the authoring sandbox, which has no network):
  backend:  npm run typecheck && npm test      (integration tests need TEST_DATABASE_URL)
  android:  ./gradlew testDebugUnitTest
Log in again once after deploying so your access token carries the session id.
