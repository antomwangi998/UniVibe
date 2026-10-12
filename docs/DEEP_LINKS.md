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
| `univibe://chats/{chatId}` | A conversation (e.g. `univibe://chats/c1`) |
| `univibe://post/{postId}/comments` | Comments on a post |
| `univibe://student/{studentId}` | Another student's profile |
| `univibe://communities` | Communities list |
| `univibe://communities/{communityId}` | Community details (e.g. `univibe://communities/tech`) |
| `univibe://profile/edit` | Edit profile |
| `univibe://profile/photos` | Your photos |
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
- Event details (`univibe://events/{id}`) will be added with the event details screen.
