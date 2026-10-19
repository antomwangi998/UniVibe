# Sign in with Google

"Continue with Google" is on the log-in and sign-up screens. It uses Android **Credential Manager** (the
"Sign in with Google" option) and the existing UniVibe backend. Email/password sign-in is unchanged.

> **Status: written, not yet built or run.** Nothing here has been compiled or tested against a real Google account yet.
> The code was written without a Gradle or Postgres environment. Push it and let both CI workflows run (Android CI,
> Backend CI), then do the manual checks at the bottom before relying on it.

## How it works

1. The app asks the server for a single-use nonce (`POST /auth/google/nonce`).
2. Credential Manager shows **Google's own account chooser** with that nonce. The app gets a Google ID token.
3. The app sends only `{ idToken, nonce }` to `POST /auth/google`. The server (`backend/src/google.ts`, using `jose`)
   checks the RS256 signature against Google's published keys, the issuer, the audience (your web client id), expiry,
   and that Google marked the email verified. It then checks the token's nonce equals the one it issued and deletes
   it, so a copied token cannot be replayed. Name and email are read from the verified token, never from the app.
4. The Google **subject id** (`sub`) is the identity key (`auth_identities`, unique on provider + subject).
   - Known subject → normal UniVibe session (same JSON as `/auth/login`, same refresh-token family and rotation).
   - Unknown subject, email already belongs to a verified UniVibe account → `link_required`. Nothing is merged
     until the person enters that account's **password** (`POST /auth/google/link`).
   - Unknown subject, no account → `registration_required` with a short-lived signed token and a prefill. The app
     shows a short screen (name, university, Terms). The account is created only by `POST /auth/google/register`.
5. The Google ID token is used once and discarded. It is never a UniVibe session. No Google access token is requested
   or stored, and no Gmail/Drive/contacts access is requested.

Registration and link tokens are signed with `JWT_SECRET`, expire after 15 minutes, and use their own audience, so
they cannot be used as access tokens (and access tokens cannot be used as them).

### Student verification stays separate
Signing in with Google never sets the student badge. `student_verified` is set at registration by the **existing
rule**: Google-verified email whose domain matches the chosen university's `email_domains`. A `@gmail.com` person
joins but is not a verified student, exactly like an email sign-up whose email does not match.

### Google-only accounts have no password
`users.password_hash` is now nullable. Such an account:
- cannot use the email/password form (same generic error and timing as a wrong password);
- can add a password any time with **Forgot password** (it proves control of the email, then sets one);
- must do that before **Change password** or **Delete account**, which ask for a password
  (the app shows an explanatory message).

An unverified email sign-up that nobody confirmed does not block Google: it is replaced when the real owner
registers with Google (its password does not survive).

## Required setup (nothing works until this is done)

### 1. Google Cloud Console
Project → APIs & Services → **OAuth consent screen** (External is fine; scopes: only the default
`openid`, `email`, `profile`). Then **Credentials → Create credentials → OAuth client ID** twice:

| Client | Type | Details |
|---|---|---|
| Web | **Web application** | No redirect URIs needed. Its **client ID** is the *server client id*. |
| Android | **Android** | Package name `com.univibe.app` (see `applicationId` in `app/build.gradle.kts`) and the **SHA-1** of the signing key. Create one per key (debug, release, Play App Signing). |

The Android client's id is never put in code, but it **must exist** or Google returns "no credential available".
If you change `applicationId` (it is marked as a placeholder), create new Android clients for the new name.

Get the SHA-1:
```
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
# or: gradle signingReport
```
**CI caveat:** the debug APK built by GitHub Actions is signed with a throwaway debug key that differs per run, so its
SHA-1 will not match your Android client and Google sign-in will fail on that APK. Options: sign CI builds with a fixed
keystore stored as GitHub Secrets (never commit it), or test from a locally built APK. Release builds distributed
through Google Play need the **Play App Signing** SHA-1 (Play Console → App integrity).

### 2. Backend (Render)
Set `GOOGLE_WEB_CLIENT_ID` = the **Web** client id (comma-separate several). It is not a secret. Redeploy. The
migration `022_google_identity.sql` runs automatically on start (additive; no data is deleted or reset). Without the
variable the server logs a warning and `/auth/google*` answers `503 google_unavailable`.

### 3. Android
Set the same Web client id in `gradle.properties` (or `-Punivibe.googleServerClientId=...`):
```
univibe.googleServerClientId=1234567890-abc.apps.googleusercontent.com
```
While blank, the Google button is hidden and the app behaves exactly as before. Release builds use ProGuard/R8; the
Credential Manager and Google ID libraries ship their own consumer rules.

Dependencies added (pinned in `gradle/libs.versions.toml`; no other versions were touched):
`androidx.credentials` + `credentials-play-services-auth` 1.3.0, `googleid` 1.1.1.
Verify these versions resolve and are still current when you first build.

## Password manager and autofill
- Email/password fields keep using ordinary Material text fields (no custom input), tagged with autofill content
  types: log-in = username/email + password; sign-up and reset = new username/email + **new password** (so the
  password manager can suggest a strong one).
- After a successful **sign-up**, **password reset** or **password change**, the app calls Credential Manager's
  `CreatePasswordRequest`, which shows the system's save/update prompt. It is optional, never required, and
  declining or an unavailable service changes nothing. The app never stores the password.
- Saved logins are offered on the log-in form through system autofill. Google Password Manager controls its own UI.

## Not done on purpose
- **Passkeys:** not implemented and not advertised. `auth_identities` has a provider column and can hold a passkey
  provider later, but registration, storage, sign-in and recovery would all need building and testing first.
- **No automatic Google prompt at app launch.** Returning people tap the button; Google's chooser lists their
  previously used accounts. Sign-out calls `clearCredentialState` so Google does not sign them straight back in.
- **Campus-verification screen:** there is no separate state. As before, a person who is not matched to the
  university signs in with the student badge off.

## Tests
Backend: `test/google.test.ts` (runs without a database: signature, issuer, audience, expiry, `alg:none`, unverified
email) and `test/google-auth.integration.test.ts` (needs `TEST_DATABASE_URL`: registration, returning sign-in,
duplicate/simultaneous registration, nonce replay, account linking, password-reset compatibility, suspension, 503).
Android: `GoogleAuthRepositoryTest`, `GoogleAuthViewModelTest` (cancel, no account, errors, offline, double tap,
registration prefill, linking, session restore).

Not automated (needs a device or Compose UI tests): Google's chooser itself, autofill suggestions, the system
password-save prompt, layout with the keyboard open.

## Manual check once configured
1. Fresh device with a Google account, tap Continue with Google on Sign up → registration screen prefilled → Create
   account → onboarding → app.
2. Sign out, tap Continue with Google again → straight into the app (no second account; check `auth_identities`).
3. Cancel Google's chooser → no error shown.
4. Existing email/password account, Continue with Google with the same email → link screen → wrong password
   rejected, right password links.
5. Airplane mode → friendly offline message.
6. Email/password log-in, forgot password, change password still work; the save-password prompt appears after sign-up.
