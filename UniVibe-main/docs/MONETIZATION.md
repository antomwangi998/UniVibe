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

## Not implemented yet
Delivery into Home Feed and Discover (stage 7), impression and click tracking and advertiser analytics (8), admin dashboard pages (9), financial reports, CSV export and cost tracking (10), notifications to advertisers and staff, report-an-ad, logo/cover/creative image upload, editing a draft campaign, Daraja integration, subscriptions (business plans), the Android UI.
Tests: `test/campaign-state.test.ts` (run), `test/monetization.integration.test.ts` (written for CI; needs Postgres; not run).
