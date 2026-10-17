# Push notifications (FCM)

Status: **written but not yet compiled or run** (no Android SDK, Gradle or Firebase project was available when this was
written). Expect to fix a compile error or two on first CI run and send them back.

## How it fits together
1. App signs in -> `PushRegistrar` fetches the FCM token and calls `PUT /api/v1/me/devices` (installation id + token).
   The server identifies the student from the access token; the FCM token is only an address.
2. Something happens on the server (message stored, follow, like, ...) -> a row is added to the `jobs` table in the same
   database transaction -> the worker checks the recipient's rules (mute, block, read, preferences) -> sends an FCM
   **data-only** message with ids and generic labels (never message text or tokens).
3. `UniVibeMessagingService` turns the payload into a notification (`PushPayload` -> `PushNotifier`) on one of five channels:
   Direct messages, Group messages, Social activity, Campus updates, Security and account alerts.
4. Tapping it fires a `univibe://...` link at `MainActivity`, the same route as every deep link (see DEEP_LINKS.md).
   Cold start, app already open and signed-out all work; the screens re-check access, so a stale id shows "not found".
5. Log out calls `DELETE /api/v1/me/devices/:installationId` first and deletes the local FCM token.

## One-time setup (what you must do)
**Firebase project**
1. console.firebase.google.com -> create a project -> add an Android app with package `com.univibe.app`
   (change `applicationId` first if you have already replaced the placeholder).
2. Download `google-services.json` and commit it as `app/google-services.json`. (It holds project identifiers, not the
   server secret.) The Gradle file applies the Google services plugin only when this file exists.
3. Project settings -> Service accounts -> Generate new private key. **This JSON is a server secret.**
   Put it in the Render environment variable `FCM_SERVICE_ACCOUNT_JSON` (raw JSON or base64 of it). Never commit it,
   never put it in the app.

Without those, the app and server still build and run; push is skipped and the in-app notification list works as before.

## Behaviour worth knowing
* Android 13+: the permission prompt appears once after sign-in and is never repeated by the app. If declined, in-app
  notifications keep working; people can enable alerts later in system settings.
* Notification text is generic ("New message", "New message in your group"), so lock screens do not leak content.
* Pushes for the same conversation or notification replace each other (same tag), so retries cannot stack duplicates.
* The server does not know whether a conversation is open on screen, so a push can arrive while the app is open.
* FCM delivery is best effort. The server records `accepted` only when FCM accepted the request.
* Not built yet on Android: a settings screen for the new server-side switches (`pushMessages`, `pushSocial`,
  `pushEvents`, per-conversation mute). The API exists; the Settings screens still use local-only toggles.

## Testing on a device
1. Deploy the backend with `FCM_SERVICE_ACCOUNT_JSON`. Install a debug build that includes `google-services.json`.
2. Sign in on two accounts (phone + a second device or an API client). Follow the first account from the second.
3. The first phone should get "New follower" within a few seconds with the app in the background.
4. Check `GET /api/v1/admin/jobs` (admin account) for failed jobs, and the server log for `job failed` lines.
