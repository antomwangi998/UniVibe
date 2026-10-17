# Communities: backend contract

Roles: `owner`, `moderator`, `member`. Privacy: `public` (join at once) or `private` (request, then approval).

Server rules (the app only mirrors them):
- `POST /v1/communities`: name 3 to 40 characters, unique case-insensitively across the university; description 10 to 300; category from a fixed list; up to 8 rules of 120 characters; at most 5 communities owned per user; rate limit creation. Creator becomes owner and first member.
- `PATCH /v1/communities/{id}`: owner only. The name is immutable. Switching private to public approves everyone waiting.
- `DELETE /v1/communities/{id}`: owner only. Cascade: posts addressed to it, events it hosts, memberships, requests, reports. Notify members.
- Membership: `PUT/DELETE /v1/communities/{id}/membership`. Public: immediate. Private: creates or withdraws a request. Owners cannot leave without deleting or transferring ownership (ownership transfer is not built).
- `GET /v1/communities/{id}/members` and `/posts`: for private communities, members only. Never reveal member lists or posts of private communities to outsiders, including through search, counts of specific people, or deep links.
- Requests: moderators list and approve/decline. Do not tell a declined user who declined them.
- Roles: only the owner promotes or demotes moderators. Moderators remove members, never moderators or the owner. Keep an audit log of every moderation action.
- Posting: a post with audience `community:{id}` requires membership; moderators may remove any post in their community.
- Reports: go to the community's moderators and to UniVibe's own moderation queue.
- Names and rules are user content: run them through the same abuse checks as posts.

Not built yet: ownership transfer, bans, community-wide announcements, pinned posts, member-to-member invitations.
