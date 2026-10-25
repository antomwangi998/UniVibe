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

## Not implemented yet
Advertiser portal screens on Android, including analytics charts (stage 8b), event-view and business-view tracking, admin dashboard pages (9), financial reports, CSV export and cost tracking (10), logo/cover/creative image upload, Daraja, subscriptions, and Discover and business-directory sponsored UI on Android.
Tests: `campaign-state`, `sponsored-token`, `analytics-calc` (40 tests, run under Node with a stand-in runner); `monetization.integration`, `sponsored.integration` (including analytics) and the push case in `push-reactions.integration` need Postgres and have not run; `SponsoredCardTest.kt` and `PushPayloadTest.kt` need Gradle and have not run.
