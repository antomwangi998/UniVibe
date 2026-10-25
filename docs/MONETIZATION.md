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

## Not implemented yet
Impression and click reporting for advertisers (stage 8), admin dashboard pages including the ad-report queue (9), financial reports, CSV export and cost tracking (10), notifications, campaign event retention job, logo/cover/creative image upload, editing a draft campaign, Daraja, subscriptions, Discover and business-directory sponsored UI on Android.
Tests: `test/campaign-state.test.ts` and `test/sponsored-token.test.ts` (run under Node with a stand-in runner); `test/monetization.integration.test.ts` and `test/sponsored.integration.test.ts` (need Postgres, not run); `SponsoredCardTest.kt` (needs Gradle, not run).
