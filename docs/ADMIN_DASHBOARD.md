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
| Moderate | Reports | moderator, admin | The reports queue with tabs Open, Under review, Escalated, Action taken and Dismissed, and a "handled by" filter (anyone, me, nobody yet). Take a report or release it, add private notes, escalate with a reason, dismiss with a reason, or act (remove or restore content, hide an event, suspend or restore a person). Each card shows who has it and how long it has waited; "Notes and history" shows notes, other reports on the same item, and past actions. A badge shows open reports (admins) |
| Moderate | Trust & safety | admin | Waiting right now (by age, escalated, nobody yet), time to first look and to resolution (median and average), reports and actions per day, by reason, by kind of item, and items reported more than once |
| Moderate | People | admin | The directory lists everyone, 25 at a time. Filter by Active, Suspended, Email not verified, Joined in last 7 days, Has open reports or Deactivated; by campus; sort by newest, oldest, name or last seen; search by name, @handle or email. Open a person for activity counts, last seen, staff notes and moderation history; suspend (1 to 30 days) or lift a suspension |
| Records | Audit log | admin | Every privileged action with who did it |

Every view has its own address, so links, refresh and the browser Back button work:
`#/overview`, `#/analytics?days=30` or `#/analytics?from=2026-10-01&to=2026-10-24`, `#/system`, `#/reports?status=open|under_review|escalated|action_taken|dismissed&assigned=me|unassigned`, `#/safety?days=30`, `#/people?q=name&status=suspended&university=lakeside&sort=name&page=2`, `#/people/<user id>`, `#/audit`.
Old `#/users` and `#/health` links still work. Opening a page you may not use sends you to your first allowed page.

## Who can do what

The server decides. `backend/src/permissions.ts` is the one list of capabilities and which role holds each; every route asks it, and `GET /api/v1/moderation/access` returns the signed-in person's list, which the dashboard uses to build its menu. Roles are read from the database on every request, so a demotion applies immediately.

| Capability | Moderator | Admin |
|---|---|---|
| `reports.review` (reports queue and its actions) | yes | yes |
| `reports.assign` (give a report to someone else, or take one over) | no | yes |
| `overview.view`, `analytics.view`, `users.view`, `users.suspend`, `users.note`, `system.view`, `audit.view` | no | yes |

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

- `backend/src/modules/admin.ts`: `GET /api/v1/admin/overview | analytics | users (filters: q, status, university, sort, page, limit) | users/:id | health | audit` and
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

## People: changes to the API

- `GET /admin/users` no longer needs a search word. It takes `q`, `status`, `university`, `sort`, `page` and `limit`, returns `total` and `pages`, and rejects any other parameter with 400. Each person now carries `lastSeenAt` and `openReports`.
- `GET /admin/users/:id` adds `counts.communities`, `counts.eventsHosted`, `counts.eventsJoined` and `notes`.
- `POST /admin/users/:id/notes` adds a private staff note (`users.note`). Notes are never shown to the person, and the audit log records only that a note was added, not its text.
- **Breaking:** `POST /admin/users/:id/suspend` and `/unsuspend` now require a `note` (the reason, 3 to 500 characters). Anything calling them without one gets 400. The dashboard asks for it and shows a confirmation first.
- Migration `043_admin_notes.sql` adds the notes table and a sort index.
- Not built: account restrictions short of suspension, and an audit entry for merely viewing a person.

## Reports: how the workflow behaves

- **One handler at a time.** Taking a report, or marking it under review, assigns it to you. While it is assigned to someone else, other moderators get a 409 if they try to take it, dismiss it or act on it; an administrator can take it over. Settling a report does not move who it was assigned to.
- **Escalation is a flag, not a new status.** The four statuses (open, under_review, action_taken, dismissed) are unchanged, so the Android app's queues keep working. Escalating needs a reason, moves an open report to under review, and shows in the dashboard's Escalated tab. An administrator can take it over from there.
- **Repeated decisions.** Dismissing an already dismissed report, or marking an already-under-review report under review, returns 409. Follow-up actions from a settled report (for example restoring removed content, or suspending after a removal) still work, as before.
- **Reasons.** The dashboard asks for a reason before dismissing, escalating or acting. The server requires one only for escalating, because the Android app already calls dismiss and action without one.
- **Notes** are private to staff, kept with the report, and never written to the audit log (only that a note was added, and its length).
- **Opening a reported message** through the detail endpoint is recorded as `report.view_message`. The reports list already shows a 200-character preview of a reported message, as before.

### Report endpoints added or changed

- `GET /moderation/reports` also takes `assigned=me|unassigned` and `escalated=1`, and each item adds `assignedTo`, `escalatedAt`, `firstReviewedAt` and `noteCount`.
- `GET /moderation/reports/:id` (new): the report, notes, past actions on the same item, and how many reports it has by reason.
- `POST /moderation/reports/:id/assign` with `{to: "me" | "none" | <moderator id>}` (giving it to someone else needs `reports.assign`).
- `POST /moderation/reports/:id/notes` with `{note}`.
- `POST /moderation/reports/:id/escalate` with `{note}` (3 to 500 characters).
- `GET /admin/trust-safety?days=7|30|90` or `?from=&to=` (new, `analytics.view`).
- `GET /moderation/access` also returns your `id`.
- Migration `044_report_workflow.sql` adds the assignment, first-look and escalation columns and the notes table. It fills `first_reviewed_at` for existing reports from what was recorded (resolved time for settled reports, last update for ones under review), so older averages are approximate.

### Fixed in Analytics

"Time to first review" on the Analytics page was measuring time to resolution, because only the resolved time was stored. It now uses the new first-look time.

### Not built

Severity (reports have no such field, so age and escalation stand in), an administrator picker for giving a report to a specific moderator from the dashboard (the API supports it; the dashboard offers Take over and Release), and reopening a resolved report.
