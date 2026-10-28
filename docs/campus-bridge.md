# Campus bridge

Campuses (universities) stay separate communities. Every student, post and event keeps its own campus, shown as a badge.
What changed is how far a list *reaches*.

## Scope
Lists take `campusScope`: `home` (default), `all`, or one university id.

- **Home**: your campus, plus people you follow on other campuses (posts feed). Discover and search: your campus only.
- **All**: every campus. **One**: that campus only.
- Events: left out means "everything you may see" (public events from other campuses included); the app sends `home` explicitly.

## What crosses campuses
| Thing | Rule |
|---|---|
| Posts | Only audience `all` ("All campuses"). `campus` posts stay home-only. Followers posts reach followers anywhere. |
| People | Visible to other campuses unless `show_other_campuses` is off (default on). Own campus always sees them. |
| Follow | Allowed across campuses. |
| 1:1 messages | Across campuses only after you follow them. Groups stay within one campus. |
| Events | Only visibility `public` ("Open to other universities"). |
| Reports | Go to the moderators of the campus that owns the content. |
| Communities, stories, notes | Unchanged: one campus. |

## Menu
`GET /api/v1/campuses/scopes` returns the home campus, other campuses that have students, and `homeQuiet`
(home has fewer than `CAMPUS_QUIET_MEMBERS` students, default 40). A quiet home opens the app on All once, with a note.
Adding a campus is a row in `universities` (optionally `short_name`).

## Not done yet
Cross-campus communities, stories and notes; remembering the chosen scope between launches; per-campus moderation tools.
