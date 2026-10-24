# Admin dashboard

A web page for moderators and administrators, served by the backend at **`/admin`**. Open it in any browser and sign
in with a moderator or administrator account.

| Tab | Who | What it does |
|---|---|---|
| Overview | admin | Student, post, message, event and report counts; 14-day charts; active users today and this week |
| Reports | moderator, admin | The reports queue: read the reported excerpt, dismiss, mark under review, remove or restore content, hide an event, suspend or restore a person |
| People | admin | Search by name, handle or email; see counts and past actions; suspend (1 to 30 days) or lift a suspension |
| System | admin | Job queue (waiting, failed, retrying, oldest), push devices, pending uploads, live location shares, database size, server uptime and memory |
| Audit log | admin | Every privileged action with who did it |

## Making someone an admin or moderator

Roles are set in the database, so nobody can grant themselves access:

```sql
update users set role = 'admin' where email = 'you@your-university.ac.ke';   -- or 'moderator'
```

## What it never shows

Message text, except the short excerpt attached to a report. The overview is counts only. Administrators cannot be
suspended from the dashboard, and nobody can suspend themselves.

## How it is built

- `backend/src/modules/admin.ts`: `GET /api/v1/admin/overview | users | users/:id | health | audit` and
  `POST /api/v1/admin/users/:id/suspend | unsuspend`. Everyone who is not an administrator gets "not found".
- The reports queue uses the existing `/api/v1/moderation/*` routes.
- `backend/src/admin_ui/`: one page, one script, one stylesheet. No libraries and no inline code, so the page is served with
  `default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'`.
- The page holds no data. It signs in through `/auth/login` like the app does; the refresh token is kept in
  `sessionStorage` only, so closing the tab signs you out.
