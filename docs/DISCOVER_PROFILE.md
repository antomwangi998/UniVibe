# Discover profile: backend notes

Students control their Discover card in Settings > Privacy & safety > Discover profile: a card photo, a live preview,
and the "Show me in Discover" switch (the same `discoverable` setting as the Privacy screen).

Backend work needed before a real launch:
- Upload the card photo as a validated image (type by content, size limit, re-encode, strip metadata), and store a URL, not a file path.
- Moderate card photos before other students can see them (automated checks plus a report queue).
- Only return a student in `GET /v1/discover` if they are `discoverable` and, if you require it, have a photo. Never return blocked users in either direction.
- Delete the stored photo when the student removes it or deletes their account.
- Mutual matching must stay consent-based: a "connect" becomes a match only when both sides say yes.

The mock candidates have no photos (they show the gradient and initials). Real candidates will arrive with `photoUrl`.
