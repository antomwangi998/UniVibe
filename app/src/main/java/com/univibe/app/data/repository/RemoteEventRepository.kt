package com.univibe.app.data.repository

import com.univibe.app.core.eventDateParts
import com.univibe.app.data.model.CampusEvent
import com.univibe.app.data.model.EventDetails
import com.univibe.app.data.model.NewEvent
import com.univibe.app.data.model.RsvpState
import com.univibe.app.data.model.Venue
import com.univibe.app.data.net.ApiException
import com.univibe.app.data.net.AuthedApi
import com.univibe.app.data.net.accentFor
import com.univibe.app.data.net.send
import org.json.JSONObject
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

/**
 * Campus events from the UniVibe server: real events, real registrations, capacity enforced by the server.
 *
 * What the server does not have, and so is not faked here:
 *  - A venue directory and map positions: venues are plain text, so [getVenues] is empty and no map is shown.
 *  - Hosting for a community: communities are not on the server yet, so a draft that asks for it is refused.
 */
class RemoteEventRepository(private val api: AuthedApi) : EventRepository {
    override val supportsInterested: Boolean get() = true

    private fun millis(iso: String) = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

    private fun toEvent(o: JSONObject): CampusEvent {
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
            isPast = System.currentTimeMillis() >= end, startMillis = start, endMillis = end,
            hostName = organizer.optString("fullName"), hostCommunityId = null,
            cancelled = o.optString("status") == "cancelled", mine = o.optBoolean("isOrganizer", false),
        )
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

    private fun body(d: NewEvent): JSONObject {
        check(d.hostCommunityId == null) { "Hosting for a community isn't available yet." }
        return JSONObject()
            .put("title", d.title).put("description", d.description)
            .put("category", TO_SERVER[d.category] ?: throw IllegalArgumentException("Pick a category"))
            .put("venueName", d.venue)
            .put("startsAt", Instant.ofEpochMilli(d.startMillis).toString())
            .put("endsAt", Instant.ofEpochMilli(d.endMillis).toString())
            .put("capacity", d.capacity ?: JSONObject.NULL)
    }

    override suspend fun createEvent(draft: NewEvent): String = explained {
        JSONObject(api.send("POST", "/api/v1/events", body(draft).put("visibility", "university").put("attendeesVisible", true), ok = setOf(201)))
            .getString("id")
    }

    override suspend fun updateEvent(id: String, draft: NewEvent) = explained {
        api.send("PATCH", "/api/v1/events/$id", body(draft))
        Unit
    }

    override suspend fun cancelEvent(id: String) = explained { api.send("POST", "/api/v1/events/$id/cancel", ok = setOf(204)); Unit }

    override suspend fun deleteEvent(id: String) = explained { api.send("DELETE", "/api/v1/events/$id", ok = setOf(204)); Unit }

    /** There is no venue directory on the server; the form takes a typed venue. */
    override suspend fun getVenues(): List<Venue> = emptyList()
}
