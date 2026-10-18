# Venue maps

The event details screen shows a small read-only map (osmdroid + OpenStreetMap tiles). Tapping it, or "Open in Maps",
opens the user's installed maps app via a `geo:` link.

- No API key, no secrets in the APK, no location permission. The app never reads the device location.
- Needs the `INTERNET` permission (tiles). Offline, the preview shows an empty grey map; the venue name and button still work.
- Event coordinates in `MockData.eventInfo` are **placeholders**. A real backend must send the true venue `latitude`/`longitude`.
  Events with no coordinates simply hide the map.
- Loading tiles sends the user's IP address to OpenStreetMap's servers. Add this to the privacy policy.

## Before a real launch
OpenStreetMap's public tile servers are not meant for heavy production traffic (see the OSM Tile Usage Policy).
Before launch, either use a commercial tile provider (set it as the osmdroid tile source) or switch `VenueMap` to Google Maps
Compose. `VenueMap` is the only place that touches the map library, so the swap is local. If you use Google Maps, keep the
API key in `local.properties` via a manifest placeholder and restrict it to your package name and signing key; never commit it.
