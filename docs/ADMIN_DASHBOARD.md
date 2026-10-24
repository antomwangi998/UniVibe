# Admin dashboard

A web page for moderators and administrators, served by the backend at **`/admin`**. Open it in any browser and sign
in with a moderator or administrator account.

## Navigation

A sidebar groups the pages (a menu button opens it as a drawer on phones). Every page has a breadcrumb, a title and a one-line description. Moderators only see what they may use.

| Group | Page | Who | What it does |
|---|---|---|---|
| Monitor | Overview | admin | Student, post, message, event and report counts; 14-day charts; active users today and this week. Cards link to the matching list |
| Monitor | Analytics | admin | Pick 7, 30 or 90 days, or two dates (up to a year). Each figure is compared with the period just before it: new students, active students, posts, comments, messages, communities, joins, events, event sign-ups, reports, reports by reason, average time to first review |
| Monitor | System health | admin | Job queue, push devices, pending uploads, live location shares, database size, server uptime and memory |
| Moderate | Reports | moderator, admin | The reports queue: read the reported excerpt, dismiss, mark under review, remove or restore content, hide an event, suspend or restore a person. A badge shows open reports (admins) |
| Moderate | People | admin | Search by name, handle or email; open a person to see counts and past actions; suspend (1 to 30 days) or lift a suspension |
| Records | Audit log | admin | Every privileged action with who did it |

Every view has its own address, so links, refresh and the browser Back button work:
`#/overview`, `#/analytics?days=30` or `#/analytics?from=2026-10-01&to=2026-10-24`, `#/system`, `#/reports?status=open|under_review|action_taken|dismissed`, `#/people?q=name`, `#/people/<user id>`, `#/audit`.
Old `#/users` and `#/health` links still work. Opening a page you may not use sends you to your first allowed page.

## Who can do what

The server decides. `backend/src/permissions.ts` is the one list of capabilities and which role holds each; every route asks it, and `GET /api/v1/moderation/access` returns the signed-in person's list, which the dashboard uses to build its menu. Roles are read from the database on every request, so a demotion applies immediately.

| Capability | Moderator | Admin |
|---|---|---|
| `reports.review` (reports queue and its actions) | yes | yes |
| `overview.view`, `analytics.view`, `users.view`, `users.suspend`, `system.view`, `audit.view` | no | yes |

Anyone without the capability gets "not found" from `/admin/*`, so the routes do not advertise themselves.

## What the audit log now records

- Account suspensions and reinstatements, and report actions (as before).
- `staff_login` and `staff_login_failed` for moderator and administrator accounts. Wrong passwords on student accounts are not recorded here (they are already rate limited).
- `admin_access_denied` when a moderator calls an administrator route, at most once a minute per person. It records the capability and path only.

No passwords, tokens, IP addresses or message text are written. The table is append-only (migration `042_audit_append_only.sql`): rows cannot be edited or deleted. Deleting a person's account still works; it clears who the rows pointed at and changes nothing else.

## Making someone an admin or moderator

Roles are set in the database, so nobody can grant themselves access:

```sql
update users set role = 'admin' where email = 'you@your-university.ac.ke';   -- or 'moderator'
```

## What it never shows

Message text, except the short excerpt attached to a report. The overview is counts only. Administrators cannot be
suspended from the dashboard, and nobody can suspend themselves.

## How it is built

- `backend/src/modules/admin.ts`: `GET /api/v1/admin/overview | analytics | users | users/:id | health | audit` and
  `POST /api/v1/admin/users/:id/suspend | unsuspend`. Everyone who is not an administrator gets "not found".
- The reports queue uses the existing `/api/v1/moderation/*` routes.
- `backend/src/admin_ui/`: one page, one script, one stylesheet. No libraries and no inline code, so the page is served with
  `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'`.
- The page holds no data. It signs in through `/auth/login` like the app does; the refresh token is kept in
  `sessionStorage` only, so closing the tab signs you out.

## How the Analytics numbers are counted

- Days are UTC days. The comparison period is the same number of days immediately before the first day. A custom end date later than today is cut back to today.
- Active students: different people with a sign-in or session refresh in the period. This measures sign-ins, not time spent in the app.
- Posts, comments and messages count only items that are currently active, so removed content is not included.
- Time to first review averages `reviewed_at - created_at` for reports filed in the period that have been reviewed. Reports still waiting are not counted.
- Not shown, because the app does not record what they need: retention, feature adoption, notification delivery.
