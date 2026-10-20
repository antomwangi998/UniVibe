# Events: backend contract

Times are stored in UTC milliseconds with the venue's time zone; the app shows them in the device time zone. The mock only knows the device zone.

## Lifecycle
- **Upcoming**, **Live** (start <= now < end), **Ended** (now >= end), **Cancelled** (host action, orthogonal to time).
- **Auto-expiry:** an event is deleted 7 days after it ends (`end + 7 days`), together with RSVPs, reminders and any attached media. Run it as a server-side TTL job; never rely on clients to delete. After deletion every link returns "not found". The constant `EVENT_RETENTION_MILLIS` is the single knob on the client.
- Notify RSVPed users when an event is cancelled or its time or venue changes. Send reminders only to Going/Interested users and stop after the end time.

## Rules the server must enforce
- Title 3 to 80 characters, description up to 1000, category from a fixed list, venue name 2 to 80.
- End after start, at most 48 hours long, start at least 5 minutes from now on creation (an unchanged start may be kept on edit), at most one year ahead.
- Capacity 1 to 5000 or none; never below the number already going. Going is refused when full (use a transaction so two people cannot take the last place).
- Venue position is optional and must come from the venue directory or be omitted; both coordinates or neither.
- Hosting for a community needs owner or moderator rights there. The host and community can't change after creation.
- At most 5 upcoming events per user at a time; rate limit creation.
- Edit/cancel: host only, before the event ends. Delete: host only, and only while nobody else has RSVPed; otherwise cancel.
- Title and description are user content: apply the same abuse checks as posts, and let people report events.

## Not built yet
- Recurring events, waitlists, co-hosts, ticketing, Interested, saved events, organizer updates, configurable reminders (see Events v2 below for what is done).

## Events v2, stage 1 (migration 025)
Implemented on the server; the Android app does not use the new fields yet.

- **Model:** `eventType` (in_person, online, hybrid), `venueAddress`, `onlineUrl`, `externalRegistrationUrl`, `registrationDeadline`, `timezone`, `communityId`. Times stay UTC; `timezone` is the display zone, null means the viewer's. The campus is the existing `university_id`.
- **Categories** are rows in `event_categories` (`GET /api/v1/event-categories`). Adding one is an insert.
- **Status:** stored `draft | published | cancelled | completed`. Derived by the server on every read: `phase` (upcoming, live, ended) and `registration` (open, closed, full, ended, cancelled, draft). Clients must not recompute them.
- **Drafts:** `POST /events` with `"status": "draft"`; `POST /events/:id/publish` re-checks times, community rights and the upcoming-events limit, and is safe to repeat. Drafts are visible only to the organizer and never appear in `/events`.
- **Idempotent create:** send `createKey` (8-64 chars); a retry returns the same event (200 instead of 201).
- **Deadline:** registration after `registrationDeadline` is refused with 409 `registration_closed`, using the server clock.
- **Join link:** `onlineUrl` is returned only to the organizer and registered people; others get `hasOnlineLink`.
- **Complete:** `POST /events/:id/complete` (organizer, after start). Completed events count as past.
- **Community events:** only active owners/moderators may host. A private community's events are forced to `visibility: community` and can never be widened. `community` visibility = active members of an active community, re-checked on every read, in search, and in `/me/events`. A public community's events may be public or university-wide. The community name is returned only when the community is public or the viewer is a member.
- **Notifications:** only changes to time, venue, address, format or join link notify registrants; wording edits do not.
- **Audit:** `event_audit` records create, publish, update, cancel and complete (no read API yet).
- **Known limits:** a university admin cannot see or manage a community-only event unless a member.
