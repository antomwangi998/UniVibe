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

## Not implemented yet
Routes, advertiser flow, delivery, analytics, admin pages, reports, cost tracking, Android UI, subscriptions (business plans).
