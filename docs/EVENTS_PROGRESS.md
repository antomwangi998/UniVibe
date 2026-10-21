# Events module: progress against the scope

Hand-off for a new session. The scope is the 18-phase spec (Events tab, details, create/manage, RSVP/registration, Home Feed, Discover, notifications, communities, sharing, moderation, backend, performance, Android architecture, tests). Work is delivered as one zip per stage (`UniVibe-events-stageN.zip`, each includes the earlier stages) and pushed from Termux on branch `events-stageN`. API details: `docs/EVENTS_API.md`.

**Important:** nothing has been built or run. The authoring sandbox had no Postgres, Gradle or installed backend dependencies; only a TypeScript syntax check was done. First job in any session with real tooling: `npm run typecheck` and `TEST_DATABASE_URL=... npm test` in `backend/`, then `./gradlew testDebugUnitTest assembleDebug`, and fix what breaks (expect small errors, and some test assertions that guess response shapes).

## Already existed (reused, not rebuilt)
Events tab, EventCard, details, create/edit form, register/cancel with row-lock capacity, attendee list privacy, organizer check-in, 7-day purge, event reports (moderation), notify() with the `events` switch, search, Discover shell, communities, jobs/scheduler, push.

## Done by stage
| Stage | Scope item | Migration | Status |
|---|---|---|---|
| 1 Foundation | event type, address, join link, deadline, timezone, community_id, retry key, category table (15), draft/completed, community visibility, audit table, publish/complete routes, server-derived phase/registration | 025 | written |
| 2 Participation | Interested (never takes a seat), going replaces it, `PUT /rsvp`, saved events, `/me/saved-events` | 026 | written; Android uses Interested, no saved UI |
| 3 Home Feed | one announcement post per event, `event` in post JSON, relevance gate, EventFeedCard, cancellation state | 027 | written |
| 4 Discover | sections, filters, ranking with reasons, not-interested, richer search, server categories | 028 | written |
| 5 Notifications | confirmations, 24h/1h reminders (per-user offsets, per-event switch), change/cancel notices, organizer milestones, community fan-out job, preferences API + Android settings, notice text; Android create form brought up to server fields | 029 | written |

## Stage 6 (communities, sharing, moderation, organizer tools): written, not run
Migration 032. Backend: organizer updates, audit read, share to chat or community, links, public preview page, moderator hide/restore, suspended-organizer hiding, calendar `revision`. Android: community Events tab and shared-event posts, share sheet (link, chat, community), event card in chat bubbles, Add to calendar remembers the version and says honestly when an event changed (Android cannot edit a calendar entry for us), updates section and "Post an update", Activity sheet, Mark as completed, per-event reminders switch, report event and report organizer, notice text for updates and moderation.
Tests: `backend/test/events-stage6.integration.test.ts` (needs TEST_DATABASE_URL). No Android tests were added for stage 6.
Known gaps:
- Attendee list screen for organizers (`GET /events/:id/attendees` exists, no Android screen).
- Publish-a-draft button (the Android form never creates drafts yet; belongs with draft saving in stage 7).
- Android App Links for the https share link (needs a verified domain). The app link `univibe://events/{id}` works.
- Forwarding a chat message that holds an event answers "Photos can't be forwarded yet" (misleading text, harmless).
- Moderator UI on Android: the moderation actions `hide_event` and `restore_event` exist on the server; no Android moderator screen was touched.
- Chat share needs the conversation list; the sheet shows the first 20 chats.
- Set `PUBLIC_BASE_URL` on the server or share links stay app-only.

Stage 7, still to do:
- Run and fix all tests and the Android build; add missing tests (cache invalidation, offline/reconnection, notification routing, event card rendering).
- Android reads the server's `phase` and `registration` instead of the device clock (EventCard/isPast use `System.currentTimeMillis()`).
- Discover/profile/community lists currently skip event posts; give them event cards.
- Saved events screen/filter on the Events tab (server supports it); bookmark button on EventCard and details.
- Draft saving in the create form.
- Performance review: ranking query cost at scale, `event_registrations` counts, image loading, cache invalidation on RSVP.
- Offline cache of the feed may hold expired signed cover URLs.
- Final acceptance check against the 21 criteria and the final report (files, routes, migrations, tests actually run).

## Decisions to keep
No waitlist, no invitations, no payments or free/paid filter, no location-based "near you". Interested never takes a seat. One feed announcement per event. Community notices are in-app only. Private-community events are forced to `community` visibility and re-checked on every read. All time-dependent state comes from the server clock.
