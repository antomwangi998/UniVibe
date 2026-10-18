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
- Cover photos, recurring events, waitlists, check-in, co-hosts, ticketing.
