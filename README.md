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
