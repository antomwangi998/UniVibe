# Monetization (work in progress)

Status key: **written** = code exists; **run** = executed here; **deployed** = live. Nothing is deployed.

## Stage 2: data model (migration `046_monetization.sql`) - written, NOT run against Postgres
| Table | Purpose |
|---|---|
| `businesses`, `business_members`, `business_offers` | Directory listings. Creating a listing makes you its owner and grants nothing else. `status` (draft, pending_review, approved, rejected, suspended) and `verification` (unverified, verified) are separate staff decisions |
| `monetization_settings` | Key/value. `manual_payment` holds the interim "where to pay" details; ships **disabled and empty** |
| `promotion_packages` | Admin-managed price, duration, placements, caps. KES, integer minor units |
| `orders` | Freezes price and terms in `package_snapshot`, so price changes never alter an existing order |
| `payments`, `payment_events` | One receipt code can back one payment (unique index). Events are the payment's history |
| `refunds` | One open refund per payment. Full refunds only for now |
| `campaigns`, `campaign_status_history` | One campaign table for every campaign kind. Reuses `events` and `posts` by reference; never copies them |

Conventions adapted to this codebase: lower-case status words, uuid keys, user references `on delete set null` so financial records survive account deletion.

## Campaign lifecycle (`src/modules/monetization/campaign_state.ts`) - written, unit tests run under Node
Payment, review and schedule are three independent conditions; a campaign is shown only when all hold.
`draft -> pending_payment -> pending_review -> approved -> scheduled/active -> paused/completed`; `rejected` and `cancelled` (if paid) -> `refund_pending -> refunded`.
Only an admin approves/rejects; only the system activates; an advertiser cannot resume a staff pause; `completed` and `refunded` are final.

## Interim payments (no Daraja keys yet)
The advertiser pays the till shown in the app, then submits the M-Pesa receipt code. That is a **claim**, stored as `awaiting_verification`. A staff member with `payments.confirm` checks it against the M-Pesa statement and enters the amount received; only then does the order become `paid`. A receipt code cannot be reused. When Daraja is available it will write `provider = 'mpesa_daraja'` rows through the same tables.

## New staff capabilities (`permissions.ts`)
`campaigns.review`, `advertisers.view`, `packages.manage`, `payments.view`, `payments.confirm`, `refunds.approve`, `finance.view`, `finance.export`. Admin only for now; moderators are unchanged.

## API (stages 3-6) - written, NOT run
Advertiser (any signed-in student; ownership enforced per row, other people's ids answer 404):
`GET /monetization/packages`, `GET /monetization/payment-info`, `POST /businesses`, `GET /businesses` (organic directory), `GET|PUT /businesses/:id`, `POST /businesses/:id/submit`, `GET /me/businesses`,
`POST /campaigns`, `GET /me/campaigns`, `GET /campaigns/:id`, `POST /campaigns/:id/checkout|pause|resume|cancel`, `POST /orders/:id/payment` (submit receipt code).
Staff (`/admin/monetization/...`, 404 to everyone without the capability): `packages` (GET/POST/PATCH), `payment-settings` (GET/PUT), `payments` (GET, `:id/confirm`, `:id/reject`), `refunds` (GET, `:id/approve|reject|paid`), `campaigns` (GET, `:id`, `:id/approve|reject|pause|resume|cancel`), `businesses` (GET, `:id/review`), `sweep`.

## Policies as built
- **Price:** read from `promotion_packages` at checkout and frozen on the order. The request has no price field. A price edit needs `confirmPriceChange: true` and is audited with old and new value.
- **Payment:** receipt submission is a claim. Staff confirm with the amount received; a mismatch confirms nothing and is recorded. Nobody confirms their own order. Confirming twice is a no-op.
- **Review:** payment never approves content. Approval re-checks payment, event eligibility and business approval, then the clock rules decide scheduled or active.
- **Refunds:** rejecting or cancelling a paid campaign opens one refund request. A different staff member must approve it, then someone records the M-Pesa payout reference. Full refunds only.
- **Event stops being promotable** (cancelled, hidden, ended, deleted): the minute-by-minute sweep ends the promotion. Not yet shown: refund requested; already delivering: ends without an automatic refund (staff can still refund); an event that finished normally: campaign completes.
- **Unpaid orders** expire after 48 hours unless a receipt is waiting for staff.
- **Business edits:** editing an approved listing sends it back to review; renaming drops verification. Suspending a business stops its running campaigns.
- **Free packages** (price 0) skip payment and go to review.

## Stage 7: sponsored delivery - written, NOT run (token logic unit-tested)
Migration `047_sponsored_delivery.sql`: `campaign_events` (impressions and clicks, pseudonymous viewer key) and `campaign_reports`.
Endpoints: `GET /sponsored?placement=` , `POST /sponsored/impression`, `POST /sponsored/click`, `POST /sponsored/:campaignId/report`.
- **Separate from the feeds.** `/posts` and Discover responses are unchanged, so nothing existing can break. The app asks `/sponsored` once per feed load and places the card itself (Home: after the third post, only if the feed has that many).
- **Eligibility is decided on the server, per viewer:** campaign `active`, review approved, order `paid`, inside its dates, package impression cap not reached, placement included, advertiser at the viewer's university and not suspended or blocked either way, business approved, the event (if any) visible to the viewer by the normal event rules and not hidden by "not interested" or already registered, the post (if any) visible by the normal post rules, fewer than 3 open ad reports, and the viewer under the package's daily frequency cap.
- **Not overwhelming:** at most one card per request; at most 8 sponsored cards per student per UTC day across all campaigns; campaigns served least-delivered first with a random tiebreak.
- **Counting:** each card carries a signed token tied to the student, valid 30 minutes. An impression counts once per token, only while the campaign is still live and under its caps. A click must follow an impression of the same token, and a second click by the same student on the same ad within 30 minutes is not counted. Forged, expired, replayed or someone else's tokens are rejected.
- **Metrics defined:** *impression* = the card was on screen for about a second; *click* = a tap on the card or its button. Neither is a purchase. No conversion metric exists because the app receives no purchase evidence.
- **Privacy:** events store a keyed hash of the student id, never the id; advertisers will only ever get aggregate counts. A student-run event promotion names the organizer, whose name the event page already shows to the same viewers.
- **Caching:** there is no shared server cache, so a status change takes effect on the next request. The response says `private, max-age=60`, so a phone may keep showing a card for up to one minute after it stops.
- **Reports:** one per student per campaign, only for campaigns they were served. Three open reports hold a campaign back automatically; staff review comes with the admin pages (stage 9).
- **Data retention (not yet automated):** `campaign_events` should be rolled into daily totals and purged after 90 days; this job is not written yet.
- **Android (written, NOT compiled):** `SponsoredRepository`, `SponsoredCardView` (always labelled "Sponsored", names the advertiser, has "Report this ad"), wired into the Home campus feed. Discover and the business directory have the backend but no app UI yet.

## Hardening round (migration `048_monetization_hardening.sql`) - written, NOT run
- **Notifications:** new type `promotion` (cannot be switched off; it is about the person's own money and content). Sent to the advertiser on payment confirmed or rejected, campaign approved, rejected, paused or ended early, and refund approved, declined or paid; sent to every active admin when a receipt is submitted. The server sends only a `kind` and ids; the wording is fixed in the Android app. 
- **Overpayment:** staff confirm with `refundExcess: true`. The order is confirmed at its price and a separate `overpayment` refund is queued for the excess; paying it back does not touch the order or campaign. A short payment never confirms.
- **Sole administrator:** the rule that nobody approves their own refund request is waived only when there is exactly one active admin (otherwise a one-person team could never refund anyone). The audit entry then carries `soleAdmin: true`. Confirming your own order stays forbidden.
- **Ad reports:** `GET /admin/monetization/ad-reports` (most reported first, 100 at most) and `POST .../ad-reports/:campaignId/resolve` (`upheld` pauses a live campaign for staff to act on; `dismissed` releases it). Audited.
- **Draft editing:** `PUT /campaigns/:id` while the campaign is a draft; the kind cannot change, and everything is re-validated.
- **Retention:** every 6 hours the last three full UTC days are re-totalled into `campaign_daily` (impressions, clicks, distinct pseudonymous viewers) and raw `campaign_events` older than 90 days are deleted. Payment, order, refund and audit records are never purged by the app.

## Push for promotion notices - written, NOT run
- **Server:** `promotion` notices are pushed through the existing delivery job. They obey only the master push switch (`pushEnabled`); the social and event switches do not apply, because these are about the person's own money and listings. The push carries ids and a fixed `promotionKind` label, never amounts, receipt codes or reasons. Quiet hours still apply on the phone.
- **Android:** new notification channel "Promotions" (people can tune it in system settings); one shared wording table (`PromotionNotice`) used by both the in-app list and the push, so an unknown or forged kind falls back to a generic line. Tapping opens the notification list; there are no campaign screens to deep-link to yet.
- **Business owners** are now notified when a listing is approved, rejected, suspended, restored or verified (not when verification is removed).
- Not covered: an in-app inbox entry only appears for the person who owns the listing or campaign; staff receive receipt alerts the same way.

## Stage 8: advertiser analytics - written, pure calculations unit-tested, routes NOT run
Endpoints: `GET /campaigns/:id/analytics?from=&to=` (owner only, others get 404), `GET /me/advertising/summary`, and for staff `GET /admin/monetization/campaigns/:id/analytics` (adds integrity signals).
- **Metrics, as defined in the response and in `analytics_calc.ts`:** impressions, clicks, CTR (empty, not 0, when nothing was shown), reach (students, counted without names), by placement, by day (zero-filled), delivery status with the plain-language reasons a campaign is not running, payment and refund amounts.
- **Conversions:** only *RSVP after click* for event promotions: a student tapped the ad, then RSVPed within 7 days. It shows the two are connected; it does not claim the ad caused it. Purchases are not measured, and the API says so.
- **Not tracked, so not reported:** event detail views, business profile views and offer interactions are not recorded anywhere in the app today. Adding them needs new tracking in those screens (stage 8b).
- **Data sources:** raw `campaign_events` for the last days, `campaign_daily` roll-ups for older days (see `mergeDaily`). Reach over a window is exact while raw events exist (about 85 days) and an approximate sum of daily reach after that; the response says which.
- **Integrity signals (staff only):** unusually high CTR, clicks concentrated in one student, few distinct clickers, more views per student than the daily cap allows, and a single-hour burst. They only fire with enough data, name no one, and are prompts for review, not verdicts.
- **Privacy:** advertisers never see who viewed or clicked, only totals. Up to 5,000 sign-ups and 20,000 clickers are matched inside the server to count RSVPs after clicks; the matching result is a single number.
- **Ranges:** whole UTC days, at most 92 days, reversed or impossible dates are refused with a 422.

## Stage 9: admin Monetization Centre - screens smoke-tested against a fake page; backend NOT run
A **Monetization** group in the existing dashboard (nothing existing was changed or removed). Code: `src/admin_ui/monetization.ts`, spliced into the dashboard script. Each page shows only to staff holding its capability, and the API checks the same capability on every call.
| Page | Needs | What it does |
|---|---|---|
| Revenue | `finance.view` | Received, refunded, net collected, recognised, deferred; by campaign type and by month; counts needing attention; the rules for each figure |
| Campaigns | `campaigns.review` | Review queue and status tabs; approve, reject, pause, resume, cancel with reasons; a detail page with performance, CTR, delivery status, engagement checks and history |
| Payments | `payments.view` (`payments.confirm` to act) | Receipts waiting for your check; confirm with the amount actually received (and "refund any excess"), or reject with a reason |
| Refunds | `payments.view` (`refunds.approve` to act) | Approve, decline, then record the M-Pesa reference once sent |
| Packages and payment | `packages.manage` | Create packages, change prices (asks you to confirm, existing orders keep their price), hide or show; set the till or paybill number and switch payments on |
| Businesses and ad reports | `advertisers.view` (`campaigns.review` to act) | Approve, reject, suspend, restore, verify business listings; resolve reported ads |
- **Accounting rules used on the Revenue page** (also shown on the page): *received* = confirmed cash before refunds (payment volume); *refunded* = refunds actually paid out; *net collected* = received minus refunded; *recognised* = finished campaigns in full, running ones by days elapsed, a campaign cancelled without a refund in full, nothing for campaigns not yet started, unreviewed or awaiting refund; *deferred* = paid but not yet recognised. Costs and operating surplus are **not tracked yet**, so no surplus is shown.
- **Not in this stage:** an advertiser directory beyond business listings (suspending an advertiser's account is done on the existing People page), the full financial reports, CSV exports and cost tracking (stage 10).
- **Verification done:** the dashboard script compiles; a fake-page test (`admin-monetization-ui.test.ts`, 8 tests, run) opens every new page against canned API data and checks that confirming a payment sends the amount in cents. This does not check layout or styling: open `/admin` in a browser and look.
- **Not verified:** the new revenue SQL and every admin route against Postgres.

## Android screens - written, NOT compiled or run
Entry points: Profile has **Promotions** and **Campus businesses** rows; a promotion notice (in-app or push) opens Promotions.
| Screen (route) | What it does |
|---|---|
| Promotions (`promotions`) | Totals (running, waiting, views, taps, tap rate, paid, refunded) and the list of your promotions with plain status words |
| New promotion (`promotion-new`) | Choose what to promote (event, business ad, featured listing, Discover feature), the event or approved business, a package (price comes from the server), where it shows, headline, description, optional button, start and length, a live preview, then "Continue to payment" (or "Send for review" for a free package) |
| Promotion (`promotion/{id}`) | Status and what happens next, the reason if it was not approved, payment instructions (till or paybill number, amount, order reference) with a receipt code box that only records a claim, performance (views, taps, tap rate, reach, RSVPs after a tap, a daily bar chart, whether it is showing and why not), history, and Pause, Resume and Cancel with confirmation |
| My businesses, add, edit (`my-businesses`, `business-new`, `business-edit/{id}`) | Free listings with their review status; send for review; editing a live listing sends it back for review (the screen says so) |
| Campus businesses (`businesses`, `business/{id}`) | Organic directory with category filter; a labelled sponsored card sits above it and is never mixed into the listings; call, email and website buttons (https only) |
| Sponsored cards in Discover | One card in front of the fourth post in Discover's post lists (only when the list is that long) and one under the filters in the server-ranked Discover events list (`SponsoredSlot`); tapping opens the event, post or business. The plain on-device events list used when the server has no Discover has none |
- **Honesty rules in the UI:** prices and amounts are always the server's text; no screen computes money; "I have paid" says it only records the receipt and that nothing starts until payment is confirmed and the ad approved; the tap rate shows a dash, not 0%, when nothing was shown; there is no purchase-conversion figure.
- **Offline demo:** every promotion screen shows "needs a connection" instead of fake data (`NoPromotionsRepository`).
- **Verification done:** a static check that every import, icon and spacing name used by the new files exists and that brackets balance. **Not done:** Gradle compile, the two new Kotlin test classes (`PromotionsLogicTest`, `PromotionsParsingTest`), and any look at the screens on a device or emulator.
- **Not built:** a "Promote this event" button on the event page itself (use Profile > Promotions > New promotion), picking a start date and time by calendar (it offers "as soon as approved", "tomorrow" and "in 3 days"), editing a submitted promotion, ad images, and a business-review notice screen (notices open the Promotions list).

## Not implemented yet
Financial reports, CSV export and cost tracking (stage 10), event-view and business-view tracking, logo/cover/creative image upload, Daraja, subscriptions, a "Promote" button on the event page, and calendar pickers for promotion dates.
Tests: `campaign-state`, `sponsored-token`, `analytics-calc` and `admin-monetization-ui` run under Node with a stand-in runner; the `*.integration` tests need Postgres and have not run; all Kotlin tests need Gradle and have not run.

## Premium and Premium Business subscriptions (migration `056_subscriptions.sql`) - written, NOT run against Postgres
Built on the existing `orders` / `payments` / `refunds` tables, so receipt claims, staff confirmation, mismatch handling, duplicate-receipt protection and refunds are the same code paths as promotions. `promotion_packages` is untouched.
- **Tables:** `subscription_plans` (price in KES minor units, period days, entitlement keys, `active`), `subscriptions` (one row per paid period; `order_id` unique so an order activates once), `orders.plan_id` and `orders.business_id`.
- **Seeded, but OFF:** `premium_monthly` (KSh 99), `premium_annual` (KSh 799), `business_premium` (KSh 499). All plans ship `active = false`. Staff switch one on (`PATCH /admin/monetization/plans/:id`, `packages.manage`) only after every feature it lists works end to end. The Android app is not built yet, so nothing is on sale.
- **No automatic renewal.** With manual M-Pesa confirmation one paid order buys one period; buying again extends from the current end. Responses carry `renews: false`. Real auto-renewal needs Google Play Billing (digital features on Android generally must use it; check current Play policy before selling in-app) or a provider that supports it.
- **Entitlements are derived, never stored as a flag:** `entitlements.ts` (pure, tested) computes the live keys from `subscriptions` using the server clock. Expired, not-yet-started and refunded periods grant nothing; unknown keys grant nothing. Guards: `requireEntitlement` / `requireBusinessEntitlement` in `subscriptions.ts`.
- **API:** `GET /monetization/premium/plans?audience=user|business`, `GET /me/entitlements` (cacheable 60 s), `POST /monetization/premium/checkout` (price from the plan row; no price field; idempotent with `createKey`; max 3 open orders; business plans need an owner of an approved business), `GET /me/billing-history`, then the existing `POST /orders/:id/payment` for the receipt claim. Staff: `GET|PATCH /admin/monetization/plans`.
- **Activation:** only inside the staff payment-confirm transaction (`activateSubscriptionForOrder`). A paid-out full refund ends that period (`endSubscriptionForOrder`).
- **Paid is not verified:** a business subscription changes nothing about `businesses.verification`.
- **Not built yet:** every Premium feature itself (themes, frames, chat themes, sticker packs, creator analytics, scheduling, feed controls), business analytics/offers/scheduled posts, all Android Premium/Business screens, Google Play Billing, Daraja, admin subscription revenue figures (subscription cash appears in "received" but not in "recognised"), and integration tests for these routes.
- **Tests:** `test/entitlements.test.ts` (12) passed under a temporary stand-in for vitest; real vitest, `tsc` and Postgres were not available in this session.

## Android: UniVibe Premium screen - written, NOT compiled or run
Entry: Settings (searchable as "UniVibe Premium") opens route `premium`. Code: `features/premium/` (screen, view model, pure wording logic), `data/repository/PremiumRepository.kt`, `data/model/Premium.kt`; the container exposes `premiumRepository`.
- Shows status (Free, or Premium with days left, never "renews"), the plans the server has switched on, a Free vs Premium list built only from the keys those plans carry, billing history (paged), and a short FAQ. When no plan is on sale it says "Premium is not available yet"; when payments are off, Subscribe is disabled with the reason.
- Subscribe creates an order (price from the server, one idempotency key per attempt) and shows the till, amount and reference. "I have paid" only records the receipt claim and says Premium starts after confirmation. A waiting order in the history can be resumed with "Enter receipt code".
- No sample data: the offline demo shows "needs a connection" (`NoPremiumRepository`).
- Not built: Profile entry point, actual Premium features, business subscription screens, cached entitlement use by other screens. Tests: `PremiumLogicTest` (wording and comparison rows) has not run; Gradle was not available.

## Premium chat themes (first working Premium feature) - written, NOT compiled or run
- **What it is:** six Premium packs (Aurora Live, Neon Nights, Golden Hour, Starfield, Rainy Window, Sakura). Each sets an accent for my bubbles and an animated wallpaper together. They sit beside the existing free themes and wallpapers, which are unchanged. Chat menu > Theme and wallpaper > "Premium packs".
- **Gate:** entitlement key `premium.chat_themes`. The app holds the server's answer in memory only (`EntitlementsStore`, refreshed at most once a minute when a chat opens or the Premium screen loads, cleared on sign-out). Nothing is stored on disk, so editing local preferences grants nothing. If the plan ends, or the server cannot be reached, a stored Premium look shows the free default and the sheet says it is paused; the choice comes back with Premium.
- **Honest limit:** these looks are visible only to the person who picked them, on their own phone. The server decides whether the person is entitled, but it cannot stop a modified app from drawing the animation locally. That is acceptable for a private cosmetic; anything other people see (profile themes) must be enforced by the server.
- **Performance:** plain Canvas drawing, no assets. Holds still on Reduce motion and on low-memory phones (glass off). Time is read while drawing only, so the chat does not recompose per frame.
- **Migration 057:** plans now list only `premium.chat_themes`; the business plan lists nothing. Keys return one migration per shipped feature.
- **Tests (not run):** `PremiumChatThemesTest`.

## Premium profile looks (visible to other students) - written, NOT compiled; backend rules tested with a stand-in runner
- **What:** a theme (cover gradient and artwork), an accent colour, an avatar frame and a small badge. Every choice comes from a fixed list or a #RRGGBB colour, so there is no free text: nothing can carry an insult, a link or a fake "verified" mark. Badges show their words ("Night owl") and look nothing like the verified student mark.
- **Server is the authority:** migration `058_profile_looks.sql` adds `profile_looks`. `PUT /me/profile-look` refuses anything not unlocked right now (theme, accent, badge need `premium.profile_themes`; a frame needs `premium.profile_frames`); clearing is always allowed. `GET /me/profile-look` returns the saved look, what is shown, and what is unlocked.
- **Others see it:** `GET /students/:id` now includes `look`, computed per request from the owner's live entitlements. When the owner's Premium ends the look stops showing at once, stays saved, and returns when Premium does. Existing visibility rules (blocks, suspended students, campus rules) are untouched: the look rides on the same route.
- **Android:** rendered on the full student profile and on my own profile tab (cover gradient and artwork, rim colour, avatar frame, badge chip). Editor at Settings > Profile look (also reachable from the Premium screen): live preview, free to try; Save needs the unlocked parts and the server checks again.
- **Scope limit:** the look shows on profile screens only. It is not yet on post author rows, comments or lists, which keeps those lists fast and the change reviewable.
- **Plans:** 058 lists `premium.profile_themes` and `premium.profile_frames` on the personal plans beside chat themes. Plans stay inactive until staff switch one on.
- **Tests:** `test/profile-look.test.ts` (16, passed under a stand-in runner), `ProfileLookTest` (Android, not run). The Android ids test must stay equal to the server lists.

## Profile looks everywhere (migration `059_profile_look_fn.sql`) - NOT run against Postgres
- **One rule, one place:** SQL function `profile_look_for(uuid)` returns the look other people may see (null for most students: one indexed lookup, then stop). Person queries select it: `AUTHOR_COLS` (feed and post authors, comments, follower/following lists, student profile) and `cardColumns` (chat members and chat list, discover, search, notifications, community channels, privacy lists, notes, stories, events). The TypeScript `effectiveLook` stays as the tested statement of the same rule for `/me/profile-look`; if you change one, change the other.
- **Android:** `Student.look` and `Chat.look` are read from the server; `StudentAvatar(look = ...)` draws the frame (thinner on small avatars); a badge glyph sits beside names on post cards and comments. 26 avatar call sites that have a `Student` were updated.
- **Not covered, and why:** message sender avatars inside a conversation, story and note rings, and event host rows only carry a name or id, not a person object, so they show no frame yet. Community member lists that do not use the shared person serializers need checking on a real device.
- **Risks to check before release:** any query that selects `cardColumns`/`AUTHOR_COLS` inside a `group by` or `union` must still run (the new column is a function call, so it needs to be in the group by or both union sides); run the whole backend test suite on a database with migrations applied.

## Admin: Premium page and a small real-money test (migration `060_premium_controls.sql`) - NOT run against Postgres
**Where:** admin dashboard > Monetization > Premium (needs `packages.manage`; the subscriber list needs `payments.view`).
- **Ready to sell?** checklist (payments on with a number, a plan on sale, a feature on) and counts: students and businesses subscribed now, orders waiting, confirmed gross (before refunds, not earned revenue).
- **Plans:** put on / take off sale, change price (confirmed, audited, existing orders keep their price). A plan with no working features cannot be put on sale.
- **Feature switches:** `premium_feature_flags` kill switch per feature. Off removes it for everyone at once (app, routes, profile looks everywhere); nobody's subscription or payment changes, and turning it on restores it for the rest of their period. Audited.
- **Subscribers:** active, ended, refunded; name and handle only.
- **Not built:** granting free Premium (it would need an order, and a silent grant would bypass payment verification), per-user revocation, subscription revenue recognition in the Revenue page.

**Small real-money test (a few friends, KSh 99):**
1. Run migrations 056 to 060 on a Neon branch, then production; deploy the backend.
2. Admin > Packages and payment: enter your till or paybill number and switch payments on.
3. Admin > Premium: put "UniVibe Premium - monthly" on sale. Leave annual and business off.
4. In the app: Settings > UniVibe Premium > Subscribe, pay the till, enter the M-Pesa receipt code.
5. Admin > Payments: check the receipt against your M-Pesa statement, confirm. Their Premium starts at that moment.
6. In the app: open a chat > theme > Premium packs; Settings > Profile look; ask a friend to view your profile.
7. Refund path: Admin > Refunds, approve, send the money, record the reference; their Premium ends.
Take the plan off sale afterwards if you want to pause. People who already paid keep their days.
