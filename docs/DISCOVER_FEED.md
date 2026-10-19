# Discover content (For You, Trending, Photos, Videos, Topics)

People discovery (`GET /api/v1/discover`, the swipe deck) is unchanged and is now the **People** category.
Search (`GET /api/v1/search`) and events (`/api/v1/events`) already existed and are not duplicated.

## Endpoints (all under `/api/v1`, all need a signed-in student, all rate limited)
| Endpoint | What |
|---|---|
| `GET /discover/feed?limit&cursor` | For You: ranked, personal, stable pages |
| `GET /discover/trending?window=24h\|7d&limit&cursor` | Campus trending, shared ranking cached 60 s per university |
| `GET /discover/photos`, `GET /discover/videos` | Newest campus posts with ready media of that kind |
| `GET /discover/topics?window` | Hashtags used by at least 2 different authors |
| `GET /discover/topics/:tag` | Posts under one hashtag |
| `POST /discover/feedback` / `POST /discover/feedback/undo` | `not_interested` (postId), `hide_author` (authorId), `mute_topic` (topic) |

Posts are returned in exactly the shape of `GET /posts`, plus `reason` (`following`, `interests`, `trending_campus`,
`popular_campus` or `null`). A reason is only set when the data supports it.

## Ranking (documented in `backend/src/modules/discover_feed.ts`)
`score = (0.6*engagement + velocity + 0.4 + personal) / (1 + hours_old/12)^1.2 / (1 + reports)`
- engagement = ln(1+people who liked) + 1.5 ln(1+people who commented) + 0.3 ln(1+min(shares,20)); counted per person, authors' own likes ignored.
- velocity = the same for the last 6 hours. `personal` (For You only): +0.5 followed author, +0.4 matches one of the student's interests.
- Scores are computed as of a fixed instant carried in the cursor, so pages never reshuffle. One author fills at most 3 slots of For You (2 of Trending).
- Trending needs 2 different people interacting. Followers-only posts never appear in Discover.

## Safety
Every post goes through the same `visible()` rule as the Home feed (university, blocks both ways, suspended, removed, followers-only),
checked again on every request even for cached rankings. Feedback is private to the student who gave it.

## Migration
`020_discover_feed.sql`: new table `discover_feedback` (+ unique indexes) and two indexes. Additive; no existing table is changed.

## Not built yet
- **Communities**: the backend has no communities table, and the app's community screens use the mock repository. Needs a backend first.
- **Events category**: the backend events API exists, but the app's `eventRepository` is still the mock. Wire it, then add the chip.
- **Search box in Discover**: backend `/search` exists; the app has no search screen yet.
- Impression history ("don't repeat what I already saw"), a Prometheus/metrics hookup (none exists in the repo), and Android UI tests.
