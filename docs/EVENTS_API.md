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

## Events v2, stage 2 (migration 026): Interested and saved events
- `PUT /events/:id/rsvp` with `{"state": "interested" | "going" | "none"}` sets the whole response in one transaction and returns the event. `GET`/list views now carry `myRsvp` (going, interested, null), `interestedCount` and `saved`.
- **Interested never takes a seat.** Only registrations count against capacity. Going replaces interested; choosing interested releases a held seat. A refused change (full, closed) leaves the previous response untouched.
- Interested is refused on cancelled or ended events; withdrawing (`none`) is always allowed. There is no interested list, only the count.
- `POST`/`DELETE /events/:id/register` still work and behave as before (going / withdraw).
- `PUT`/`DELETE /events/:id/save` (idempotent, 204) and `GET /me/saved-events` (cursor-paged, newest saved first). The list only includes events the person may still see.
- **Not built:** waitlist (a full event is simply full), formal registration forms or eligibility rules, Attended state beyond the existing organizer check-in.
- The Android app now uses `PUT /rsvp` and shows Interested. It does not use saved events yet (no UI).

## Events v2, stage 3 (migration 027): Home Feed
- **One announcement per event.** Publishing (create as published, or `POST /events/:id/publish`) adds one post with `posts.event_id` set (empty text; author = organizer; audience `campus`, or `community` for community events). A unique index guarantees at most one, so repeating publish or editing the event never adds another. Deleting or purging the event deletes the post. Existing events are not backfilled.
- **Cards are the live event.** Post JSON has `eventId` and `event`, the same object as `GET /events/:id` (built by the same code), so RSVP, saved, counts and cancellation are identical on Home, the Events tab and details. A cancelled event stays in the feed marked cancelled.
- **Visibility.** A post's visibility now also requires the event to be visible to the viewer (drafts, blocked organizers, private-community membership). Losing community membership hides the post everywhere.
- **Home relevance (no flooding).** Event posts reach Home only for: the organizer; followers of the organizer; people who registered, are interested in, or saved the event; and members of the event's community (existing community gate). Only upcoming or live events; ended and completed ones leave Home. `not_interested` on the post and `hide_author` remove them. Everyone else finds events in the Events tab; broader distribution belongs to Discover (stage 4).
- Announcement posts cannot be edited as posts (409 `event_post`); edit the event. Other post actions (comment, like, report, hide) work as for any post.
- **Android:** Home draws event posts with `EventFeedCard` (reusing `EventCard`; RSVP sheet, save, share, open details). Discover, profile and community lists skip event posts for now (no event actions there yet). Home refreshes on resume, so changes made elsewhere appear.

## Events v2, stage 4 (migration 028): Discover and search
- `GET /api/v1/discover/events/sections` returns ordered sections, each `{key, title, items}`, items being the canonical event view plus `reason`. Order: communities, people you follow, recommended, trending, this week, one section per category group (`event_categories.discover_group`), online. **An event appears in at most one section**; empty sections are omitted. No location access is needed: relevance comes from campus, interests, communities, follows and history.
- `GET /api/v1/discover/events` is the filtered list. Filters, all applied in SQL: `category` (comma list of codes), `format` (in_person, online, hybrid), `from`, `to`, `communityId`, `campus=mine|any`, `registrationOpen=true`, `saved=true`, `scope=upcoming|past`, `q`, `sort=rank|time`, `limit`, `cursor`. Invalid values return 400. There is no free/paid filter because events carry no payment information.
- **Ranking** is a sum of named signals: joined community (+4), followed organizer (+3), interest match (+2), same campus (+1.5), similar events you registered for or saved (+1), starts soon (up to +3), engagement on a log scale (0.8 x ln(1 + going + interested + saved)), cover and description present (+0.5 each), full (-2), registration closed (-1), already going (-3). `reason` is the strongest applicable signal in one line.
- **Paging** is by position (cursor `o:N`, at most 300 deep); the ranking is recomputed per page, so clients drop repeats by id. Time-ordered lists (`sort=time`, `scope=past`) are stable.
- **Hide:** `PUT`/`DELETE /discover/events/:id/not-interested` (idempotent, 204, 404 for events you cannot see) hides one event from Discover and Home recommendations only; the Events tab, search and the event page still show it. To hide an organizer use the existing `POST /discover/feedback {kind: "hide_author", authorId}`; it also applies to events.
- **Search** (`/search`, `/search/events`, and `q` above) now matches title, description, venue, address, category label, organizer name or handle, university, and the community name only for people allowed to know that community.
- `GET /api/v1/event-categories` lists active categories; the Android filter chips use it.
- **Android:** Discover > Events shows the server sections, with Online / Spots open / Saved / category chips and Today / This week windows sent to the server, a "why" line on each card, and "Not interested". A repository without these endpoints keeps the old on-device filtering.
- **Not built:** "Upcoming near you" (no location data on events), free/paid filter, an explicit interests editor for events, a per-category "hide similar" (use hide organizer or not interested).

## Events v2, stage 5 (migration 029): notifications and reminders
All event notices use `type: event_update` with `metadata.kind` and carry no actor (the per-actor dedupe index used to drop a cancellation that followed a change; that is fixed). Tapping opens the event (`entityType: event`); an event that is gone or no longer visible shows the app's "not available" state.

| kind | to | when | setting |
|---|---|---|---|
| `confirmed` | the person | they register (at most once per 10 minutes) | confirmations |
| `reminder` (`offsetMinutes` 1440 or 60) | people going | 24 h / 1 h before the start | reminders + offsets |
| `changed` | going + interested | time, end, venue, address, format or join link really changed; repeats within 15 minutes become one | changes |
| `cancelled` | going + interested | the event is cancelled | cancellations |
| `registrations` (`count`, `full`) | the organizer | 1st, 10th, 25th, 50th, 100th... registration, and when it fills | organizerUpdates |
| `community_event` | community members | published in their community; in-app only, never pushed; only members at level all, highlights or announcements, not muted | community |

- Every kind also needs the general `events` switch; `push_events` still decides phone alerts for the others.
- `GET`/`PUT /me/event-notification-preferences`: `confirmations, reminders, changes, cancellations, community, organizerUpdates, reminderOffsets` (subset of 1440, 60; empty = none).
- `PUT /events/:id/reminders {enabled}` silences reminders for one event you registered for (409 otherwise); `remindersOn` is in the event view.
- **Reminder rules:** worked out from absolute times (no time zone or daylight-saving drift); each is sent once and only in the 30 minutes after its moment, so late registrants get no stale 24 h reminder; never for cancelled, completed, ended or already-started events, withdrawn people, or silenced events; moving the start clears the record so reminders follow the new time. The existing 5-minute scheduler runs `sendEventReminders`.
- Community fan-out is the `event.announce` job (queued with the announcement, idempotent, capped at 5000 recipients).
- **Not built:** invitations, waitlist notices, deadline-approaching notices (there are no invitations or waitlist).
- **Android:** create/edit form now sends the server's fields (categories from the server, in-person/online/hybrid, join link, registration deadline, external link, community hosting, device time zone) and validates them like the server; Settings > Notifications has an "Event notices" section; notification text covers every kind. The app has no draft saving and no per-event reminder switch yet (server supports both).
