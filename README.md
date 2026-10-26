<div align="center">

# UniVibe

**Connect. Share. Belong.**

A social app made for university students: share posts, chat, join communities and find out what's happening on campus.

[Features](#-features) · [Get the app](#-get-the-app) · [Run it yourself](#-run-it-yourself) · [How it's built](#-how-its-built) · [Documentation](#-documentation) · [Roadmap](#-roadmap)

</div>

---

## What is UniVibe?

Most social networks are built for everyone, which makes them noisy for a campus. UniVibe is built for one university
at a time. Students see their own campus feed, follow classmates, join clubs and societies, and never miss an event.

It has two parts that work together:

| Part | What it is | Where it lives |
|---|---|---|
| **Android app** | The app students use | `app/` |
| **Backend API** | Accounts, posts, chat, media and notifications | `backend/` |

## ✨ Features

**Share**
- Posts with photos, video and **polls**. Poll results fill in with animated teal bars, with single votes and countdowns for time-limited polls.
- 24-hour **Stories** and short **Notes**.
- Comments, likes, reposts, saved posts and link previews.

**Talk**
- One-to-one and **group chats** with photos, voice notes, typing indicators and read receipts you can switch off.
- **Notifications** for messages and activity. You choose whether alerts show the message text or just say "New message".

**Belong**
- **Communities** for clubs, courses and interests, with channels, rules and moderators.
- **Events** with RSVP (Going / Interested), categories and a venue map.
- **Discover**: swipe through students who share your interests, with a suggested-people feed.

**Safe by design**
- Sign in with email and password or **Google**. Email verification is required.
- Report and block tools, moderator queue and privacy settings (who can message you, who can see your profile).
- Photos are re-encoded on upload, which strips location data.
- Light theme, **pure-black dark theme**, adjustable text size, bold text, high contrast and reduce-motion settings.

## 📱 Get the app

UniVibe is an Android app (Android 8.0 or newer).

1. Open this repository on GitHub and go to **Releases** to download the latest `.apk`.
2. Open the file on your phone and allow "Install from this source" when asked.

You can also download a test build from any green run under **Actions → Android CI → Artifacts**:
`univibe-fast-apk` runs smoothly and is the one to use; `univibe-debug-apk` is for debugging.

## 🛠 Run it yourself

### The Android app

You need [Android Studio](https://developer.android.com/studio) (Koala or newer) and JDK 17.

1. Open this folder in Android Studio and let Gradle sync.
2. Point the app at a backend by adding this line to `gradle.properties`:
   ```
   univibe.apiBaseUrl=https://your-backend.example.com
   ```
3. Press **Run** on an emulator or phone.

No laptop? Push to GitHub. The **Android CI** workflow runs the tests and builds installable APKs for you.

### The backend

You need Node.js 20+ and a PostgreSQL database.

```bash
cd backend
npm install
export DATABASE_URL="postgres://user:pass@localhost:5432/univibe"
export JWT_SECRET="at-least-32-random-characters-................"
export CODE_PEPPER="another-32-random-characters-..............."
npm run dev        # starts the API and applies database migrations automatically
npm test           # runs the test suite
```

Everything else is optional and switches features on: `RESEND_API_KEY` (verification emails),
`R2_*` (photo and video storage), `FCM_SERVICE_ACCOUNT_JSON` (push notifications) and `GOOGLE_WEB_CLIENT_ID`
(Google sign-in). `backend/render.yaml` lists them all and deploys the API to [Render](https://render.com) in one step.

## 🧱 How it's built

| Layer | Technology |
|---|---|
| App | Kotlin, Jetpack Compose, Material 3, Navigation Compose |
| Images and maps | Coil, osmdroid with OpenStreetMap (no API key, no location permission) |
| Backend | Node.js, TypeScript, Fastify, WebSockets |
| Database | PostgreSQL (hosted on Neon), plain SQL migrations in `backend/migrations/` |
| Media | Cloudflare R2, `sharp` for resizing |
| Security | Argon2 password hashing, signed tokens (JWT), rate limiting |
| Email and push | Resend, Firebase Cloud Messaging |
| Build and deploy | GitHub Actions (tests, APK builds, tagged releases), Render |

### Project layout

```
UniVibe/
├── app/                  Android app
│   └── src/main/java/com/univibe/app/
│       ├── features/     one folder per screen area: home, chats, events, communities, ...
│       ├── ui/           theme and reusable components
│       ├── data/         models, repositories, networking
│       └── push/         notification handling
├── backend/              API server, database migrations, tests
├── docs/                 guides for each part of the system
└── .github/workflows/    automatic tests and builds
```

## 📚 Documentation

| Topic | Guide |
|---|---|
| Sign-up, login and verification | [docs/AUTH_BACKEND.md](docs/AUTH_BACKEND.md) |
| Sign in with Google | [docs/GOOGLE_SIGN_IN.md](docs/GOOGLE_SIGN_IN.md) |
| Posts and polls | [docs/POSTS_API.md](docs/POSTS_API.md) |
| Chat | [docs/CHAT_API.md](docs/CHAT_API.md) · [docs/REAL_CHAT.md](docs/REAL_CHAT.md) |
| Communities | [docs/COMMUNITIES_API.md](docs/COMMUNITIES_API.md) |
| Events and maps | [docs/EVENTS_API.md](docs/EVENTS_API.md) · [docs/MAPS.md](docs/MAPS.md) |
| Discover | [docs/DISCOVER_FEED.md](docs/DISCOVER_FEED.md) · [docs/DISCOVER_PROFILE.md](docs/DISCOVER_PROFILE.md) |
| Push notifications | [docs/PUSH_NOTIFICATIONS.md](docs/PUSH_NOTIFICATIONS.md) |
| Deep links | [docs/DEEP_LINKS.md](docs/DEEP_LINKS.md) |
| Backend API reference | [backend/docs/V1_API.md](backend/docs/V1_API.md) |

## 🗺 Roadmap

- Multiple-choice polls
- More sign-in options
- Student ID verification
- Screenshots and a short demo video for this page

Ideas and bug reports are welcome: open an **Issue** on GitHub.

## 🤝 Contributing

1. Fork the repository and create a branch.
2. Make your change and run the tests (`gradle testDebugUnitTest` for the app, `npm test` in `backend/`).
3. Open a pull request that says what you changed and why.

Never commit keystores, passwords or API keys.

## 📄 License

No license has been chosen yet, so all rights are reserved by default. Add a `LICENSE` file before accepting outside contributions.

---

<div align="center">
Built in Kenya 🇰🇪 for students everywhere.
</div>
