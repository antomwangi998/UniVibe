# UniVibe API (backend)

Node 20 + TypeScript + Fastify + Postgres. First milestone: **authentication** (everything in `docs/AUTH_BACKEND.md`).
Hosting plan: server on **Render**, database on **Neon**, email through **Resend**.

## Deploy from your phone

1. **Neon**: create a project, copy its connection string (it ends with `?sslmode=require`).
2. **Resend**: create an API key. Until you verify a sending domain, Resend only delivers to your own account address (as far as I know), so test with your own email. Use `onboarding@resend.dev` as the sender until the domain is ready.
3. **Render**: New → Blueprint → pick this GitHub repo (it reads `backend/render.yaml`). Fill in the three secrets it asks for:
   `DATABASE_URL` (Neon), `RESEND_API_KEY`, `EMAIL_FROM` (for example `UniVibe <onboarding@resend.dev>`).
   `JWT_SECRET` and `CODE_PEPPER` are generated for you. Never commit them.
   For photo and video uploads also add `R2_ENDPOINT` (the bucket's S3 API address), `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` and `R2_BUCKET`
   from Cloudflare R2. The bucket stays private: the server hands out short-lived links. Without these four, everything else works and uploads answer `503`.
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
| `POST /posts` `{ text≤280, audience: campus\|followers, poll?: { options: 2–4 × ≤40 chars }, mediaIds?: up to 4 photos or 1 video }` | `201` post. Text may be empty when there is media; a poll can't have media. Posts carry `media: [{ id, kind, url }]` with links that expire after an hour |
| `GET /posts?filter=campus\|following` · `GET /posts/saved` · `GET /posts/:id` | posts this student may see (newest first, up to 100) |
| `PUT /posts/:id` `{ text }` · `DELETE /posts/:id` | author only |
| `PUT\|DELETE /posts/:id/like` · `/save` | idempotent, `204` |
| `POST /posts/:id/vote` `{ optionIndex }` · `/share` · `/report` `{ reason }` | one vote per student (`409 already_voted`); only campus posts can be shared |
| `GET /posts/:id/comments` · `POST /posts/:id/comments` `{ text≤280, replyToId? }` | replies always attach to the top-level comment |
| `PUT\|DELETE /comments/:id/like` · `DELETE /comments/:id` · `POST /comments/:id/report` | the comment's author or the post's author may delete |
| `POST /media/uploads` `{ kind: image\|video, purpose: post\|avatar\|cover, contentType, size }` | `201 { mediaId, uploadUrl, headers }`. Send the file to `uploadUrl` with a `PUT` and those headers (private Cloudflare R2 bucket, link valid 10 minutes). Photos: JPEG, PNG or WebP up to 8 MB. Videos: MP4, MOV or WebM up to 50 MB |
| `POST /media/:id/complete` | the server checks size, type and the file's real header; a bad file is deleted and answers `400 invalid_media` |
| `PUT /me/photos` `{ avatarMediaId?, coverMediaId? }` | `null` removes a photo; a key left out stays as it is. Returns the user |
| `GET /me/stats` | `{ posts, followers, following, saved }` |
| `GET /students/:id` | another student's profile (same university only) with counts and `followedByMe` |
| `PUT\|DELETE /students/:id/follow` · `GET /me/followers` · `GET /me/following` | follows; lists are `[{ student, isFollowing }]` |

Visibility: a **campus** post is visible to students of the author's university; a **followers** post only to followers (and its author). Anything a student may not see answers `404`, so private posts are never revealed.

`user` = `{ id, fullName, handle, yearOfStudy, avatarUrl, coverUrl, email, universityId, universityName, emailVerified, studentVerified, course, interests, bio, hasPhoto, onboardingComplete }`.

## Security choices

- Passwords: Argon2id. Email codes: 6 digits, 10-minute expiry, stored only as an HMAC, 5 attempts per code, 60 s resend cooldown.
- Refresh tokens are random, stored hashed, rotated on use, with reuse detection.
- Rate limits: per IP and per email on every sensitive route (in memory, so one server instance only; move to Redis or Postgres before scaling out).
- A password chosen at sign-up is applied only when that sign-up's own code is confirmed, so pre-registering someone else's email does not let an attacker keep control of the account.
- Login timing is equalised for unknown emails. Response bodies never confirm whether an email has an account.

## Not built yet

Chat, events, communities, notifications, blocking, cleanup of never-verified accounts, push notifications, the Android `RemoteAuthRepository`.

## Local run and tests

```
cp .env.example .env     # fill in DATABASE_URL, JWT_SECRET, CODE_PEPPER
npm install
npm run dev              # codes are printed to the console when RESEND_API_KEY is empty
npm test                 # unit tests always; integration tests when TEST_DATABASE_URL points at a throwaway Postgres
```
CI (`.github/workflows/backend.yml`) runs typecheck, build and all tests against a Postgres container, and uploads `package-lock.json` as an artifact. Commit that file once so installs are reproducible.


## Social platform modules (v1)

Blocking, privacy, notifications, Discover, messaging with WebSocket, events, search and moderation are documented in
[docs/V1_API.md](docs/V1_API.md), including rules, the WebSocket protocol, jobs and known limitations. No new environment
variables are required. Run the tests with `TEST_DATABASE_URL=... npm test`.
