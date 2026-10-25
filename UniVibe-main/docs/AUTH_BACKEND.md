# UniVibe authentication — what is mocked and what the backend must provide

## Status
| Feature | State |
|---|---|
| Sign-up, log-in, email verification, password reset, onboarding screens | Built |
| `AuthRepository` interface + auth state machine (`AuthState`) | Built, unit-tested |
| `MockAuthRepository` | **Mock only.** Accounts stay on the device, no email is sent, codes are shown in debug builds. |
| Real backend | Server in `backend/` (Render + Neon + Resend). `RemoteAuthRepository` talks to it whenever `univibe.apiBaseUrl` is set (it is, in `gradle.properties`). Blank it to fall back to the mock in debug builds. Release builds refuse to start without a backend. |

The mock proves nothing about who owns an email address or who belongs to a university.
Never ship it.

## How the real backend is wired (done)
The steps below describe what was done, kept for reference.

## How to switch to a real backend
1. Implement `AuthRepository` against your API (new class `RemoteAuthRepository`).
2. Set the base URL outside source control, e.g. `~/.gradle/gradle.properties` or `-Punivibe.apiBaseUrl=https://...` (HTTPS only).
3. In `AppContainer.authRepository` return the remote class when `BuildConfig.API_BASE_URL` is not blank.
4. Screens and ViewModels need no changes.

## Required endpoints (all HTTPS, JSON)
| Endpoint | Purpose | Server rules |
|---|---|---|
| `POST /auth/register` | name, email, admissionNumber?, universityId, password, acceptedTerms | Validate everything again. Same response whether or not the email exists. Hash passwords with Argon2id/bcrypt. Send verification code/link. Rate-limit by IP and email. |
| `POST /auth/verify-email` | email + 6-digit code (or magic link token) | Single-use, expires in ≤10 min, ≤5 attempts, constant-time compare. Store only a hash of the code. Decide student verification here by matching the university's email domains and/or admission number. |
| `POST /auth/resend-verification` | resend code | ≥60 s cooldown, rate-limited. |
| `GET  /auth/status` | is the email verified? | Auth required. |
| `POST /auth/login` | email, password | Generic error for bad email or password. Lock/slow down after repeated failures. Return short-lived access token + rotating refresh token. Report `email_not_verified` only after the password is correct. |
| `POST /auth/refresh` | refresh token | Rotate on every use; revoke the whole family if an old token is replayed. |
| `POST /auth/logout` | revoke refresh token | |
| `POST /auth/password-reset/request` | email | Always 202. Short-lived, single-use token/code, stored hashed. Rate-limited. |
| `POST /auth/password-reset/confirm` | email, code, new password | Single use, ≤10 min, ≤5 attempts. Revoke all sessions afterwards. |
| `PUT  /me/profile` | course, interests, bio | Auth required. |
| `POST /me/photo` | profile photo upload | Validate type/size, scan, re-encode, strip EXIF. |
| `GET  /universities` | list | Public. |
| `POST /auth/google/nonce`, `/auth/google`, `/auth/google/register`, `/auth/google/link` | Sign in with Google | See `docs/GOOGLE_SIGN_IN.md`. |

## Client rules already followed
- No API URL, secret or token in source. Access/refresh tokens belong in `SecureStore`
  (Android Keystore AES-256-GCM; see `KeystoreSecureStore`). Passwords are never stored.
- The network layer must call `AuthRepository.handleSessionExpired()` on a 401 that a refresh cannot fix.
- Email links: if you prefer links over codes, add an intent filter + `https://` App Link for
  `/verify` and `/reset` and pass the token into the same repository methods.
- Add `INTERNET` permission and a network-security config that blocks cleartext when the real client is added.
