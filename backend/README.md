# UniVibe API (backend)

Node 20 + TypeScript + Fastify + Postgres. First milestone: **authentication** (everything in `docs/AUTH_BACKEND.md`).
Hosting plan: server on **Render**, database on **Neon**, email through **Resend**.

## Deploy from your phone

1. **Neon**: create a project, copy its connection string (it ends with `?sslmode=require`).
2. **Resend**: create an API key. Until you verify a sending domain, Resend only delivers to your own account address (as far as I know), so test with your own email. Use `onboarding@resend.dev` as the sender until the domain is ready.
3. **Render**: New → Blueprint → pick this GitHub repo (it reads `backend/render.yaml`). Fill in the three secrets it asks for:
   `DATABASE_URL` (Neon), `RESEND_API_KEY`, `EMAIL_FROM` (for example `UniVibe <onboarding@resend.dev>`).
   `JWT_SECRET` and `CODE_PEPPER` are generated for you. Never commit them.
4. Open `https://<your-service>.onrender.com/health`. It should say `{"status":"ok"}`. The first request after a quiet period takes up to a minute on the free plan.
5. Add your real university in the Neon SQL editor:
   `insert into universities (id, name, email_domains) values ('your-id', 'Your University', array['students.youruni.ac.ke']);`
   Student verification = the verified email's domain (or a subdomain) is in `email_domains`.

Tables are created automatically on startup (`migrations/*.sql`, applied once each).

## API

All bodies are JSON. Errors look like `{ "error": "<code>", "message": "...", "retryAfter": <seconds, optional> }`.
Codes: `validation` 400, `invalid_code` 400, `expired_code` 400, `invalid_credentials` 401, `unauthorized` 401,
`email_not_verified` 403, `rate_limited` 429 (also sets `Retry-After`), `server_error` 500.
Protected routes need `Authorization: Bearer <accessToken>`. Access tokens last 15 minutes, refresh tokens 30 days and rotate on every use.

| Route | Purpose |
|---|---|
| `GET /health` | liveness |
| `GET /universities` | `[{ id, name, emailDomains }]` |
| `POST /auth/register` `{ fullName, email, admissionNumber?, universityId, password, acceptedTerms }` | always `202 { status, resendCooldownSeconds }`, whether or not the email is already registered |
| `POST /auth/verify-email` `{ email, code }` | `200 { accessToken, refreshToken, expiresIn, user }`: proving the code signs the student in |
| `POST /auth/resend-verification` `{ email }` | always `202` (60 s cooldown applies silently) |
| `POST /auth/login` `{ email, password }` | `200` same shape as verify. Wrong email or password: identical `401`. Right password but unverified: `403 email_not_verified` |
| `POST /auth/refresh` `{ refreshToken }` | new token pair. Reusing an already-rotated token revokes the whole login |
| `POST /auth/logout` `{ refreshToken }` | `204` |
| `GET /auth/status` | `{ emailVerified, studentVerified }` |
| `POST /auth/password-reset/request` `{ email }` | always `202` |
| `POST /auth/password-reset/confirm` `{ email, code, newPassword }` | `204`, ends every session |
| `POST /auth/change-password` `{ currentPassword, newPassword }` | `204`, ends every session (the app signs out) |
| `GET /me` | the user |
| `PUT /me/profile` `{ fullName?, yearOfStudy?, course, interests[≤10], bio≤300 }` | saves onboarding or a later edit, returns the user. `handle` is generated at sign-up and does not change |
| `POST /me/delete-account` `{ password }` | `204`, deletes the account and its sessions |

`user` = `{ id, fullName, handle, yearOfStudy, email, universityId, universityName, emailVerified, studentVerified, course, interests, bio, hasPhoto, onboardingComplete }`.

## Security choices

- Passwords: Argon2id. Email codes: 6 digits, 10-minute expiry, stored only as an HMAC, 5 attempts per code, 60 s resend cooldown.
- Refresh tokens are random, stored hashed, rotated on use, with reuse detection.
- Rate limits: per IP and per email on every sensitive route (in memory, so one server instance only; move to Redis or Postgres before scaling out).
- A password chosen at sign-up is applied only when that sign-up's own code is confirmed, so pre-registering someone else's email does not let an attacker keep control of the account.
- Login timing is equalised for unknown emails. Response bodies never confirm whether an email has an account.

## Not built yet

Profile photo upload, social features (posts, chat, events, communities, notifications), cleanup of never-verified accounts, push notifications, the Android `RemoteAuthRepository`.

## Local run and tests

```
cp .env.example .env     # fill in DATABASE_URL, JWT_SECRET, CODE_PEPPER
npm install
npm run dev              # codes are printed to the console when RESEND_API_KEY is empty
npm test                 # unit tests always; integration tests when TEST_DATABASE_URL points at a throwaway Postgres
```
CI (`.github/workflows/backend.yml`) runs typecheck, build and all tests against a Postgres container, and uploads `package-lock.json` as an artifact. Commit that file once so installs are reproducible.
