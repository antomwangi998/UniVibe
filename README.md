# UniVibe — Connect. Share. Belong.

Campus social network for students at one university. Kotlin · Jetpack Compose · Material 3 · Navigation Compose.

## Open and run
1. Android Studio Koala (2024.1.1) or newer, JDK 17.
2. File ▸ Open ▸ select this folder. Let Gradle sync.
   - There is no `gradlew`/wrapper jar in this zip. In Studio use
     Settings ▸ Build ▸ Gradle ▸ "Use Gradle from: gradle-wrapper.properties", or run `gradle wrapper` once.
3. Run the `app` configuration on an emulator/device (minSdk 26).
4. Release build: `./gradlew assembleRelease` (add your own signing config; never commit keystores).

Application ID `com.univibe.app` is a placeholder.

## Status (honest)
Written without access to Android SDK/Gradle, so **nothing here has been compiled, run or screenshotted yet.**
Expect to fix a few compile errors on first sync; send them back and they'll be resolved.

| Item | State |
|---|---|
| Gradle + version catalog (AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.09.02) | written, unverified |
| Theme: teal light/dark, shapes, spacing, DataStore-persisted System/Light/Dark | written, unverified |
| Floating rounded bottom nav, edge-to-edge, saved tab state | written, unverified |
| Components: Button, Card, TopBar, BottomBar, Avatar, Chip, TextField, BottomSheet, Skeleton, Empty/Error, ConfirmDialog, PostCard, DiscoveryCard, EventCard, Logo | written, unverified |
| Screens: Home, Discover (swipe), Chats inbox, Events (RSVP + cancel confirm), Profile, Settings | written, unverified (mock data) |
| Unit tests (mock repositories) | written, not run |
| Previews (light + dark) | written, not viewed |
| Contrast check | primary lightened to #0F766E for white text (≈5.5:1); other pairs not measured |

## Not built yet
Phase 2 auth (welcome, sign-up, login, verification, reset, onboarding, auth state machine), conversation screen,
communities, create post, comments, notifications, photo picker, report/block flows, real images (Coil),
Inter/Poppins font files, settings sub-screens (rows are placeholders), screenshot review, UI tests.

## Mock vs real
Everything in `data/repository/MockRepositories.kt` is invented sample data. UI talks only to the interfaces in
`Repositories.kt`; a real backend replaces the mocks without touching screens.

## Layout
`app/` entry + theme VM · `navigation/` · `ui/theme` · `ui/components` · `data/{model,prefs,repository}` · `features/*`

## Fonts
Drop Inter (or Poppins) `.ttf` files in `res/font/` and point `UniVibeFont` in `ui/theme/Type.kt` at a `FontFamily(Font(R.font...))`.

## Building without a laptop
Push to GitHub; the **Android CI** workflow runs unit tests and builds a debug APK.
Download it from the run's *Artifacts* (`univibe-debug-apk`) and install on your phone.
Failed build? Open the run log and send the first `e:` error lines.

## UI improvement pass (from the "UI improvement prompt")
Done: animated story rings + online dots, post composer + create-post sheet, FAB above the floating bar, 16:9/4:5 media,
save/like states, Discover filters + progress + info sheet + bouncy actions, profile cover/stat cards/photos/saved/groups/
editable interests/privacy toggles, chats pinned section + filter + swipe mute/archive, events tabs (Upcoming/My/Past) +
search + category filters + Going/Interested/Not going sheet, softer borders, Poppins, rounded nav icons.
Not done: real photos (gradient placeholders), event details screen, comments, "Nearby" filter (skipped: needs location consent).

## Phase 2 — Authentication (mock backend)
Welcome, sign-up, log-in, email verification (6-digit code, resend cooldown), forgot/reset password, 4-step onboarding,
log out, and an `AuthState` machine in `AuthGate`. All data comes from `MockAuthRepository` (device-only, **not real
authentication**). In debug builds the verification/reset code is shown in a labelled "DEV MODE" banner because no
email is sent. See `docs/AUTH_BACKEND.md` for the endpoints a real backend needs.
Unit tests: validators, repository state transitions, login view model.
Not done: real backend, deep links, Google/other sign-in, student-ID document verification, UI tests.

## Settings, deep links, floating bar (update 5)
- Full Settings tree: Account (change password, delete account), Privacy & safety (visibility, who can message,
  read receipts, blocked students, safety tips), Notifications, Accessibility (text size, reduce motion), Help & support
  (FAQ, report a problem by email, guidelines, about). Choices persist with DataStore; text size and reduce-motion apply app-wide.
- Post menu: Report / Block feeds the blocked list (mock moderation; no server).
- Deep links: see docs/DEEP_LINKS.md.
- The bottom bar now hovers over edge-to-edge content and hides while scrolling content up, returning when scrolling back.
- Placeholders to replace: support email in HelpScreens.kt, Terms/Privacy text, guideline wording.
