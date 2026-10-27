package com.univibe.app.data.repository

import com.univibe.app.core.eventDateParts
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventCategoryInfo
import com.univibe.app.data.model.EventNotificationPrefs
import com.univibe.app.data.model.EventNotificationPrefsChange
import com.univibe.app.data.model.EventDiscoverFilter
import com.univibe.app.data.model.EventDiscoverPage
import com.univibe.app.data.model.EventSection
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.EventActivity
import com.univibe.app.data.model.EventLink
import com.univibe.app.data.model.EventUpdate
import com.univibe.app.data.model.ShareTarget
import com.univibe.app.data.model.NewEvent
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.model.Venue
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.MediaUploader
import com.univibe.app.data.net.parseStudent
import com.univibe.app.data.model.AttendeePage
import com.univibe.app.data.model.EventAttendee
import com.univibe.app.data.model.EventReportItem
import com.univibe.app.data.model.ModeratorEvent
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.accentFor
import com.univibe.app.data.net.send
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

/** The app's category names and the server's. Both sides know the same six, plus the server's extras. */
private val TO_SERVER = mapOf(
    "Workshop" to "workshops", "Party" to "party", "Sports" to "sports",
    "Academic" to "academic", "Social" to "social", "Career" to "career",
)
private val FROM_SERVER = TO_SERVER.entries.associate { (k, v) -> v to k }

private fun categoryFromServer(code: String) = FROM_SERVER[code] ?: code.replaceFirstChar { it.uppercase() }

private const val PAGE = 50
private const val MAX_PAGES = 4

private fun millis(iso: String) = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

/**
 * One server event, as the app models it. Used by the Events tab, the details screen and Home Feed announcements, so the
 * same event looks the same everywhere (the server decides what is going, interested and cancelled).
 */
internal fun parseCampusEvent(o: JSONObject): CampusEvent {
    val start = millis(o.getString("startsAt"))
    val end = millis(o.getString("endsAt"))
    val (month, day, label) = eventDateParts(start)
    val organizer = o.getJSONObject("organizer")
    val id = o.getString("id")
    return CampusEvent(
        id = id, title = o.getString("title"), month = month, day = day, timeLabel = label,
        location = o.optString("venueName"), going = o.optInt("registeredCount", 0), interested = o.optInt("interestedCount", 0),
        category = categoryFromServer(o.optString("category")), accent = accentFor(id),
        rsvp = when (o.optString("myRsvp")) {
            "going" -> RsvpState.Going
            "interested" -> RsvpState.Interested
            else -> if (o.isNull("myRegistration")) RsvpState.None else RsvpState.Going
        },
        isPast = o.optString("phase") == "ended" || System.currentTimeMillis() >= end, startMillis = start, endMillis = end,
        hostName = organizer.optString("fullName"), hostCommunityId = null,
        cancelled = o.optString("status") == "cancelled", mine = o.optBoolean("isOrganizer", false),
        coverUrl = if (o.isNull("coverUrl")) null else o.optString("coverUrl").ifBlank { null },
        saved = o.optBoolean("saved", false),
        reason = if (o.isNull("reason")) null else o.optString("reason").ifBlank { null },
        revision = o.optInt("revision", 0), hidden = o.optBoolean("hidden", false),
        draft = o.optString("status") == "draft", serverPhase = o.optString("phase").ifBlank { null },
        remindersOn = if (o.isNull("remindersOn") || !o.has("remindersOn")) null else o.optBoolean("remindersOn", true),
        campusId = if (o.isNull("campusId")) null else o.optString("campusId").ifBlank { null },
        campusName = if (o.isNull("campusName")) null else o.optString("campusName").ifBlank { null },
    )
}

/**
 * Campus events from the UniVibe server: real events, real registrations, capacity enforced by the server.
 *
 * What the server does not have, and so is not faked here:
 *  - A venue directory: [getVenues] is empty. Organizers drop a pin on the map instead, and that position is what the map shows.
 *  - Hosting for a community: communities are not on the server yet, so a draft that asks for it is refused.
 */
class RemoteEventRepository(private val api: AuthedApi, private val uploader: MediaUploader? = null) : EventRepository {
    override val supportsInterested: Boolean get() = true
    override val supportsSaved: Boolean get() = true

    override suspend fun setSaved(eventId: String, saved: Boolean) = explained {
        api.send(if (saved) "PUT" else "DELETE", "/api/v1/events/$eventId/save", ok = setOf(204))
        Unit
    }

    private fun toEvent(o: JSONObject): CampusEvent = parseCampusEvent(o)

    // ---- Event notification settings (they refine the general "Event updates" switch)
    override val supportsEventNotificationPrefs: Boolean get() = true

    private fun parsePrefs(o: JSONObject) = EventNotificationPrefs(
        confirmations = o.optBoolean("confirmations", true), reminders = o.optBoolean("reminders", true), changes = o.optBoolean("changes", true),
        cancellations = o.optBoolean("cancellations", true), community = o.optBoolean("community", true), organizerUpdates = o.optBoolean("organizerUpdates", true),
        reminderOffsets = o.optJSONArray("reminderOffsets")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: listOf(1440, 60),
    )

    override suspend fun eventNotificationPrefs(): EventNotificationPrefs = parsePrefs(JSONObject(api.send("GET", "/api/v1/me/event-notification-preferences")))

    override suspend fun saveEventNotificationPrefs(change: EventNotificationPrefsChange): EventNotificationPrefs {
        val body = JSONObject()
        change.confirmations?.let { body.put("confirmations", it) }
        change.reminders?.let { body.put("reminders", it) }
        change.changes?.let { body.put("changes", it) }
        change.cancellations?.let { body.put("cancellations", it) }
        change.community?.let { body.put("community", it) }
        change.organizerUpdates?.let { body.put("organizerUpdates", it) }
        change.reminderOffsets?.let { body.put("reminderOffsets", org.json.JSONArray(it)) }
        return parsePrefs(JSONObject(api.send("PUT", "/api/v1/me/event-notification-preferences", body)))
    }

    // ---- Discover: the server ranks, filters and sections events; the app only draws what it returns.
    override val supportsDiscover: Boolean get() = true

    override suspend fun eventCategories(): List<EventCategoryInfo> {
        val items = JSONObject(api.send("GET", "/api/v1/event-categories")).getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).let { c -> EventCategoryInfo(c.getString("code"), c.getString("label")) } }
    }

    override suspend fun discoverSections(): List<EventSection> {
        val sections = JSONObject(api.send("GET", "/api/v1/discover/events/sections")).getJSONArray("sections")
        return (0 until sections.length()).map { i ->
            val s = sections.getJSONObject(i)
            val items = s.getJSONArray("items")
            EventSection(s.getString("key"), s.getString("title"), (0 until items.length()).map { toEvent(items.getJSONObject(it)) })
        }
    }

    override suspend fun discoverEvents(filter: EventDiscoverFilter, cursor: String?): EventDiscoverPage {
        val q = mutableListOf("limit=20")
        if (filter.categories.isNotEmpty()) q += "category=" + filter.categories.joinToString(",")
        if (filter.onlineOnly) q += "format=online,hybrid"
        filter.toMillis?.let { q += "to=" + URLEncoder.encode(Instant.ofEpochMilli(it).toString(), "UTF-8") }
        if (filter.registrationOpen) q += "registrationOpen=true"
        if (filter.savedOnly) q += "saved=true"
        if (filter.myCampus) q += "myCampus=true"
        cursor?.let { q += "cursor=" + URLEncoder.encode(it, "UTF-8") }
        val body = JSONObject(api.send("GET", "/api/v1/discover/events?" + q.joinToString("&")))
        val items = body.getJSONArray("items")
        return EventDiscoverPage(
            (0 until items.length()).map { toEvent(items.getJSONObject(it)) },
            if (body.isNull("nextCursor")) null else body.optString("nextCursor").ifBlank { null },
        )
    }

    override suspend fun setNotInterested(eventId: String, hidden: Boolean) = explained {
        api.send(if (hidden) "PUT" else "DELETE", "/api/v1/discover/events/$eventId/not-interested")
        Unit
    }

    /** Upcoming events first, then the recently ended ones (the server keeps those for a week, for the Past tab). */
    override suspend fun getEvents(): List<CampusEvent> {
        val out = LinkedHashMap<String, CampusEvent>()
        for (scope in listOf("upcoming", "past")) {
            var cursor: String? = null
            for (page in 0 until MAX_PAGES) {
                val body = JSONObject(api.send("GET", "/api/v1/events?scope=$scope&limit=$PAGE${cursor?.let { "&cursor=$it" } ?: ""}"))
                val items = body.getJSONArray("items")
                for (i in 0 until items.length()) toEvent(items.getJSONObject(i)).let { out[it.id] = it }
                cursor = if (body.isNull("nextCursor")) null else body.optString("nextCursor").ifBlank { null }
                if (cursor == null) break
            }
        }
        return out.values.sortedBy { it.startMillis }
    }

    override suspend fun getEvent(id: String): EventDetails? {
        val o = try {
            JSONObject(api.send("GET", "/api/v1/events/$id"))
        } catch (e: ApiException) {
            if (e.status == 404) return null else throw e
        }
        val event = toEvent(o)
        return EventDetails(
            event = event,
            description = o.optString("description"),
            host = event.hostName,
            hostAccent = accentFor(o.getJSONObject("organizer").optString("id", event.id)),
            capacity = if (o.isNull("capacity")) null else o.optInt("capacity"),
            latitude = o.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() },
            longitude = o.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() },
            eventType = o.optString("eventType", "in_person"),
            onlineUrl = if (o.isNull("onlineUrl")) null else o.optString("onlineUrl").ifBlank { null },
            registrationDeadlineMillis = if (o.isNull("registrationDeadline")) null else millis(o.getString("registrationDeadline")),
            externalUrl = if (o.isNull("externalRegistrationUrl")) null else o.optString("externalRegistrationUrl").ifBlank { null },
            categoryCode = o.optString("category"),
            organizerId = o.getJSONObject("organizer").optString("id").ifBlank { null },
        )
    }

    /** Server refusals ("event is full", "registration is closed") reach the screen with the server's own words. */
    private suspend fun <T> explained(call: suspend () -> T): T = try {
        call()
    } catch (e: ApiException) {
        when (e.status) {
            400, 422 -> throw IllegalArgumentException(e.message)
            404 -> throw IllegalStateException("That event isn't available any more.")
            409 -> throw IllegalStateException(e.message)
            else -> throw e
        }
    }

    /** One call sets the whole response, so the server never counts someone as both interested and going. */
    override suspend fun setRsvp(eventId: String, state: RsvpState) = explained {
        val wire = when (state) { RsvpState.Going -> "going"; RsvpState.Interested -> "interested"; RsvpState.None -> "none" }
        api.send("PUT", "/api/v1/events/$eventId/rsvp", JSONObject().put("state", wire))
        Unit
    }

    /**
     * What the server's create and edit routes accept. Editing never sends the community: the host can't change.
     * An online event may leave the venue empty (the server names it "Online"); clearing an optional field sends null.
     */
    private fun body(d: NewEvent, creating: Boolean): JSONObject {
        val o = JSONObject()
            .put("title", d.title).put("description", d.description)
            .put("category", d.categoryCode ?: TO_SERVER[d.category] ?: throw IllegalArgumentException("Pick a category"))
            .put("eventType", d.eventType)
            .put("startsAt", Instant.ofEpochMilli(d.startMillis).toString())
            .put("endsAt", Instant.ofEpochMilli(d.endMillis).toString())
            .put("capacity", d.capacity ?: JSONObject.NULL)
            .put("onlineUrl", d.onlineUrl ?: JSONObject.NULL)
            .put("externalRegistrationUrl", d.externalUrl ?: JSONObject.NULL)
            .put("registrationDeadline", d.registrationDeadlineMillis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("timezone", d.timezone ?: JSONObject.NULL)
        if (d.eventType != "online" || d.venue.isNotBlank()) o.put("venueName", d.venue)
        // The map pin travels with the venue. Online events and typed-only venues send null, which clears an old pin on edit.
        val pinned = d.eventType != "online" && d.latitude != null && d.longitude != null
        o.put("latitude", if (pinned) d.latitude else JSONObject.NULL)
        o.put("longitude", if (pinned) d.longitude else JSONObject.NULL)
        // Left out, the server uses the organizer's own campus when creating and keeps the current one when editing.
        if (d.allCampuses) o.put("campusId", JSONObject.NULL) else d.campusId?.let { o.put("campusId", it) }
        if (creating) {
            o.put("communityId", d.hostCommunityId ?: JSONObject.NULL)
            o.put("status", if (d.asDraft) "draft" else "published")
        }
        return o
    }

    /** Uploads a picked cover first; the event only refers to it by id. Without an uploader (the demo) a cover is simply skipped. */
    private suspend fun uploadCover(path: String?): String? = if (path == null || uploader == null) null else uploader.upload(path, "image", "event")

    override suspend fun createEvent(draft: NewEvent): String = explained {
        val cover = uploadCover(draft.coverPath)
        JSONObject(api.send("POST", "/api/v1/events", body(draft, creating = true).put("attendeesVisible", true).apply { if (cover != null) put("coverMediaId", cover) }, ok = setOf(201)))
            .getString("id")
    }

    override suspend fun updateEvent(id: String, draft: NewEvent) = explained {
        val cover = uploadCover(draft.coverPath)
        val patch = body(draft, creating = false)
        if (cover != null) patch.put("coverMediaId", cover) else if (draft.removeCover) patch.put("coverMediaId", JSONObject.NULL)
        api.send("PATCH", "/api/v1/events/$id", patch)
        Unit
    }

    // ---- Stage 6: updates, activity, links, sharing, reports
    override val supportsOrganizerTools: Boolean get() = true

    override suspend fun eventUpdates(eventId: String): List<EventUpdate> = explained {
        val items = JSONObject(api.send("GET", "/api/v1/events/$eventId/updates?limit=20")).getJSONArray("items")
        (0 until items.length()).map { val o = items.getJSONObject(it); EventUpdate(o.getString("id"), o.getString("body"), millis(o.getString("createdAt"))) }
    }

    override suspend fun postEventUpdate(eventId: String, body: String) = explained {
        api.send("POST", "/api/v1/events/$eventId/updates", JSONObject().put("body", body), ok = setOf(201)); Unit
    }

    override suspend fun eventActivity(eventId: String): List<EventActivity> = explained {
        val items = JSONObject(api.send("GET", "/api/v1/events/$eventId/audit?limit=50")).getJSONArray("items")
        (0 until items.length()).map {
            val o = items.getJSONObject(it)
            val fields = o.optJSONArray("fields")?.let { a -> (0 until a.length()).map { i -> a.getString(i) } } ?: emptyList()
            EventActivity(o.getLong("id"), o.getString("action"), o.optJSONObject("actor")?.optString("name")?.ifBlank { null }, millis(o.getString("at")), fields)
        }
    }

    override suspend fun publishEvent(id: String) = explained { api.send("POST", "/api/v1/events/$id/publish"); Unit }

    override suspend fun myDrafts(): List<CampusEvent> = explained {
        val items = JSONObject(api.send("GET", "/api/v1/me/events?scope=upcoming&limit=$PAGE")).getJSONArray("items")
        (0 until items.length()).map { toEvent(items.getJSONObject(it)) }.filter { it.draft && it.mine }
    }

    override suspend fun attendees(eventId: String, cursor: String?): AttendeePage = explained {
        val q = "limit=50" + (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        val root = JSONObject(api.send("GET", "/api/v1/events/$eventId/attendees?$q"))
        val items = root.getJSONArray("items")
        AttendeePage(
            (0 until items.length()).map { val o = items.getJSONObject(it); EventAttendee(parseStudent(o), if (o.has("checkedIn")) o.optBoolean("checkedIn") else null) },
            if (root.isNull("nextCursor")) null else root.optString("nextCursor").ifBlank { null },
        )
    }

    // ---- Moderator tools
    @Volatile private var moderator: Boolean? = null

    /** Asked once per sign-in session; the server answers 403 to everyone who is not a moderator. */
    override suspend fun isModerator(): Boolean {
        moderator?.let { return it }
        return try {
            api.send("GET", "/api/v1/moderation/access")
            true.also { moderator = it }
        } catch (e: ApiException) {
            false.also { moderator = it } // 403: not a moderator. Remembered, so the question is asked once.
        } catch (e: java.io.IOException) {
            false // offline: not remembered, so the next screen asks again
        }
    }

    override suspend fun eventReports(): List<EventReportItem> = explained {
        val items = JSONObject(api.send("GET", "/api/v1/moderation/reports?targetType=event&status=open&limit=50")).getJSONArray("items")
        (0 until items.length()).map {
            val o = items.getJSONObject(it)
            EventReportItem(o.getString("id"), o.getString("targetId"), o.optString("preview").ifBlank { "Event" }, o.optString("reason"), o.optString("details"), o.optInt("reportCount", 1), millis(o.getString("createdAt")))
        }
    }

    override suspend fun moderatorEvent(id: String): ModeratorEvent? = explained {
        val o = JSONObject(api.send("GET", "/api/v1/moderation/events/$id"))
        val org = o.getJSONObject("organizer")
        ModeratorEvent(
            o.getString("id"), o.getString("title"), o.optString("description"), o.optString("venueName"), o.optString("status"), o.optBoolean("hidden"),
            o.optInt("reportCount"), org.optString("name"), org.optString("handle"), org.optBoolean("suspended"),
        )
    }

    override suspend fun setEventHidden(id: String, hide: Boolean, note: String, userMessage: String) = explained {
        api.send("POST", "/api/v1/moderation/events/$id/${if (hide) "hide" else "restore"}", JSONObject().put("note", note).put("userMessage", userMessage)); Unit
    }

    override suspend fun completeEvent(id: String) = explained { api.send("POST", "/api/v1/events/$id/complete", ok = setOf(204)); Unit }

    override suspend fun setEventReminders(id: String, enabled: Boolean) = explained {
        api.send("PUT", "/api/v1/events/$id/reminders", JSONObject().put("enabled", enabled), ok = setOf(204)); Unit
    }

    override suspend fun communityEvents(communityId: String): List<CampusEvent> = explained {
        val items = JSONObject(api.send("GET", "/api/v1/events?communityId=$communityId&scope=upcoming&limit=$PAGE")).getJSONArray("items")
        (0 until items.length()).map { toEvent(items.getJSONObject(it)) }
    }

    override suspend fun eventLink(eventId: String): EventLink? = explained {
        val o = JSONObject(api.send("GET", "/api/v1/events/$eventId/link"))
        EventLink(o.getString("appLink"), if (o.isNull("webUrl")) null else o.getString("webUrl"), o.optBoolean("publicPreview", false))
    }

    override suspend fun shareEvent(eventId: String, target: ShareTarget, targetId: String, text: String) = explained {
        api.send("POST", "/api/v1/events/$eventId/share", JSONObject().put("target", target.wire).put("targetId", targetId).put("text", text), ok = setOf(201)); Unit
    }

    override suspend fun report(targetType: String, targetId: String, reason: String) = explained {
        api.send("POST", "/api/v1/reports", JSONObject().put("targetType", targetType).put("targetId", targetId).put("reason", reason).put("details", ""), ok = setOf(200, 201)); Unit
    }

    override suspend fun cancelEvent(id: String) = explained { api.send("POST", "/api/v1/events/$id/cancel", ok = setOf(204)); Unit }

    override suspend fun deleteEvent(id: String) = explained { api.send("DELETE", "/api/v1/events/$id", ok = setOf(204)); Unit }

    /** There is no venue directory on the server; the form takes a typed venue. */
    override suspend fun getVenues(): List<Venue> = emptyList()
}
