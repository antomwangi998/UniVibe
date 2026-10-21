# Deep links

Scheme: `univibe://<route>`. Every screen in the signed-in app has one.
If the app is closed or the user is signed out, the link is kept and opened after sign-in.
Links only navigate; they never perform actions (delete, block, etc.).

| Link | Screen |
|---|---|
| `univibe://home` `discover` `chats` `events` `profile` | Main tabs |
| `univibe://settings` | Settings |
| `univibe://settings/account` | Account |
| `univibe://settings/account/password` | Change password |
| `univibe://settings/account/delete` | Delete account |
| `univibe://settings/privacy` | Privacy & safety |
| `univibe://settings/privacy/blocked` | Blocked students |
| `univibe://settings/privacy/safety` | Safety tips |
| `univibe://settings/notifications` | Notifications |
| `univibe://settings/accessibility` | Accessibility |
| `univibe://settings/help` | Help & support |
| `univibe://settings/help/faq` | FAQ |
| `univibe://settings/help/report` | Report a problem |
| `univibe://settings/help/guidelines` | Community guidelines |
| `univibe://settings/help/about` | About |
| `univibe://settings/help/terms` | Terms of Service |
| `univibe://settings/help/privacy-policy` | Privacy Policy |
| `univibe://chats/{chatId}` | A conversation (e.g. `univibe://chats/c1`) |
| `univibe://post/{postId}/comments` | Comments on a post |
| `univibe://student/{studentId}` | Another student's full profile (opened from Discover cards; links on to chat, communities and post comments) |
| `univibe://settings/privacy/discover` | Discover profile (card photo, preview, visibility) |
| `univibe://notifications` | Notifications list |
| `univibe://event/new` | New event form (a link never creates anything) |
| `univibe://event/{eventId}/edit` | Edit event (hosts of upcoming events only) |
| `univibe://community/new` | New community form (a link never creates anything) |
| `univibe://community/{communityId}/edit` | Edit community (owners only; others see "Not available") |
| `univibe://group/new` | New group (opens the form; a link never creates anything) |
| `univibe://chats/{chatId}/info` | Group info (only works for groups you belong to) |
| `univibe://post/new` | Create post (opens an empty composer; a link never posts anything) |
| `univibe://events/{eventId}` | Event details (e.g. `univibe://events/e1`) |
| `univibe://communities` | Communities list |
| `univibe://communities/{communityId}` | Community details (e.g. `univibe://communities/tech`) |
| `univibe://profile/edit` | Edit profile |
| `univibe://profile/photos` | Your photos |
| `univibe://settings/security` | Security |
| `univibe://settings/security/login-activity` | Login activity (devices you're logged in on) |
| `univibe://settings/appearance` | Appearance (theme) |
| `univibe://settings/status` | Account status |
| `univibe://profile/saved` | Saved posts |
| `univibe://profile/followers` `univibe://profile/following` | Follower lists |

## Try it from Termux (app installed)
```
am start -a android.intent.action.VIEW -d "univibe://settings/privacy"
```
or `termux-open-url "univibe://events"`.

## Later
- Web links (`https://yourdomain/...`) need Android App Links: a hosted `/.well-known/assetlinks.json`
  and `android:autoVerify="true"`. Add them once you own a domain.
- Email verification / password-reset links can reuse the same mechanism (see docs/AUTH_BACKEND.md).
- Ids in links are untrusted input: screens look them up and show a "not found" state for unknown ids.

## Event share links (stage 6)
`univibe://events/{id}` opens the event (already supported). The web link `<PUBLIC_BASE_URL>/e/{id}` is served by the backend as a page with an "Open in UniVibe" button that uses the app link. Android App Links (opening the https link straight in the app) are NOT set up: they need a verified domain and an `assetlinks.json`, so for now the web page is the bridge.
