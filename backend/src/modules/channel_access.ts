/**
 * Discussion channels inside a community: who may do what.
 *
 * Everything here is a pure function of (the viewer's membership, the community's status, the channel's settings).
 * The routes call it with values they just read from the database, never with anything the client sent, and the same
 * function produces the permission flags shown in the app, so what a button offers and what the server allows
 * cannot drift apart.
 *
 * The model is deliberately small and explainable:
 *   - Who can SEE a channel:   members (default) or staff only.
 *   - Who can START things:    members (default) or staff only.
 *   - Locked:                  read-only for members; staff can still write.
 *   - Archived:                hidden from members, read-only for staff.
 *   - Replies on/off:          so an announcement channel can be "announce only" or "announce and discuss".
 *   - Attachments on/off.
 * Staff = the community's owner and moderators (the roles that exist today). There is no separate administrator tier.
 * Non-members and suspended members get nothing, whatever the community's public visibility is: channel conversations
 * are never made public by a community being public.
 */

export const CHANNEL_TYPES = ['text', 'announcement', 'qa', 'resources', 'media'] as const;
export type ChannelType = (typeof CHANNEL_TYPES)[number];

export type CommunityRole = 'owner' | 'moderator' | 'member';

export interface ChannelPolicy {
  type: ChannelType;
  viewRole: 'members' | 'staff';
  postRole: 'members' | 'staff';
  locked: boolean;
  archived: boolean;
  repliesEnabled: boolean;
  allowAttachments: boolean;
}

export interface Viewer {
  /** The viewer's role in the community, or null when they have no membership row. */
  role: CommunityRole | null;
  memberStatus: 'active' | 'suspended' | null;
}

export interface ChannelPermissions {
  view: boolean;
  /** Start a new top-level message (question, announcement, resource, ...). */
  send: boolean;
  /** Answer or comment on an existing message. */
  reply: boolean;
  react: boolean;
  /** May attach files to what they write (still needs send or reply). */
  attach: boolean;
  pin: boolean;
  deleteOwn: boolean;
  deleteAny: boolean;
  manage: boolean;
  /** Staff may mark any answer accepted; the question's author may too (see canAcceptAnswer). */
  acceptAnswerAny: boolean;
  /** Why writing is unavailable, for the composer's disabled state. Null when the viewer can write. */
  readOnlyReason: ReadOnlyReason | null;
}

export type ReadOnlyReason = 'not_a_member' | 'suspended' | 'community_inactive' | 'channel_archived' | 'channel_locked' | 'staff_only';

export const isStaffRole = (role: CommunityRole | null | undefined): boolean => role === 'owner' || role === 'moderator';

const NONE: ChannelPermissions = {
  view: false, send: false, reply: false, react: false, attach: false, pin: false,
  deleteOwn: false, deleteAny: false, manage: false, acceptAnswerAny: false, readOnlyReason: 'not_a_member',
};

/**
 * @param communityStatus  'active' | 'archived' | 'suspended' | 'deleted'. Archived communities are read-only;
 *                          suspended and deleted ones are not readable at all.
 */
export function channelPermissions(viewer: Viewer, communityStatus: string, ch: ChannelPolicy): ChannelPermissions {
  if (viewer.memberStatus === 'suspended') return { ...NONE, readOnlyReason: 'suspended' };
  if (viewer.memberStatus !== 'active' || viewer.role === null) return { ...NONE };
  if (communityStatus !== 'active' && communityStatus !== 'archived') return { ...NONE, readOnlyReason: 'community_inactive' };

  const staff = isStaffRole(viewer.role);
  const view = (ch.viewRole === 'members' || staff) && (!ch.archived || staff);
  if (!view) return { ...NONE, readOnlyReason: null };

  const communityWritable = communityStatus === 'active';
  // Announcement channels are staff-write by definition, whatever was stored.
  const staffOnlyPost = ch.postRole === 'staff' || ch.type === 'announcement';

  let readOnlyReason: ReadOnlyReason | null = null;
  if (!communityWritable) readOnlyReason = 'community_inactive';
  else if (ch.archived) readOnlyReason = 'channel_archived';
  else if (ch.locked && !staff) readOnlyReason = 'channel_locked';

  const canWrite = readOnlyReason === null;
  const send = canWrite && (!staffOnlyPost || staff);
  const reply = canWrite && ch.repliesEnabled;
  if (!send && canWrite && staffOnlyPost) readOnlyReason = 'staff_only';

  return {
    view: true,
    send,
    reply,
    // Reactions stay open in locked channels (a locked announcement can still get a thumbs-up).
    react: communityWritable && !ch.archived,
    attach: ch.allowAttachments && (send || reply),
    pin: staff && communityWritable && !ch.archived,
    deleteOwn: communityWritable && !ch.archived,
    deleteAny: staff && communityWritable,
    manage: staff && communityWritable,
    acceptAnswerAny: ch.type === 'qa' && staff && canWrite,
    // A viewer who can reply but not start threads is not "read only": the composer text depends on the action.
    readOnlyReason: send || reply ? null : readOnlyReason,
  };
}

/** Marking an answer accepted: the question's author or staff, in a Q&A channel that is open for writing. */
export function canAcceptAnswer(p: ChannelPermissions, ch: Pick<ChannelPolicy, 'type'>, isQuestionAuthor: boolean): boolean {
  if (ch.type !== 'qa' || !p.view) return false;
  if (p.acceptAnswerAny) return true;
  return isQuestionAuthor && (p.reply || p.send);
}

/** Only the owner deletes a channel for good; staff can archive it instead. */
export const mayDeleteChannel = (role: CommunityRole | null): boolean => role === 'owner';

// ---------- settings

export interface ChannelSettingsInput {
  type: ChannelType;
  viewRole?: 'members' | 'staff';
  postRole?: 'members' | 'staff';
  locked?: boolean;
  repliesEnabled?: boolean;
  allowAttachments?: boolean;
}

/**
 * Fills in safe defaults and forces what a channel type requires, so an announcement channel can never be saved as
 * writable by everyone and a media or resources channel can never be saved without attachments.
 */
export function normalizeSettings(i: ChannelSettingsInput): Required<ChannelSettingsInput> {
  const announcement = i.type === 'announcement';
  return {
    type: i.type,
    viewRole: i.viewRole ?? 'members',
    postRole: announcement ? 'staff' : i.postRole ?? 'members',
    locked: i.locked ?? false,
    repliesEnabled: i.repliesEnabled ?? !announcement,
    allowAttachments: i.type === 'media' || i.type === 'resources' ? true : i.allowAttachments ?? true,
  };
}

/** "Programming Help!" -> "programming-help". Empty when nothing usable is left. */
export function channelSlug(name: string): string {
  return name.toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 30).replace(/-+$/g, '');
}

export const CHANNEL_NAME_RE = /^[a-z0-9][a-z0-9-]{0,29}$/;

// ---------- message shape per channel type

export interface MessageShapeInput {
  type: ChannelType;
  content: string;
  title: string | null;
  attachments: number;
  /** True when this message answers or comments on another one. */
  isReply: boolean;
  /** True when the message carries a poll. */
  poll?: boolean;
}

/** Polls fit conversation and announcements; they make no sense as a question, a resource or a photo post. */
export const POLL_CHANNEL_TYPES: readonly ChannelType[] = ['text', 'announcement'];

const LINK_RE = /\bhttps?:\/\/[^\s<>"']{4,}/i;

/** Returns a message to show the author, or null when the message is acceptable for this channel type. */
export function messageShapeError(i: MessageShapeInput): string | null {
  const content = i.content.trim();
  const title = i.title?.trim() ?? '';
  if (i.poll) {
    if (i.isReply) return 'A poll cannot be a reply.';
    if (!POLL_CHANNEL_TYPES.includes(i.type)) return 'Polls are not available in this kind of channel.';
    if (i.attachments) return "A poll can't have photos or files.";
    if (!content) return 'Add a question for your poll.';
    if (content.length > 280) return 'Keep the poll question under 280 characters.';
    // Announcement polls still need their title; the normal rules below check it.
  }
  if (i.isReply) {
    if (title) return 'Replies do not have titles.';
    return content || i.attachments ? null : 'Write a reply or attach a file.';
  }
  switch (i.type) {
    case 'announcement':
      if (title.length < 1 || title.length > 100) return 'Give the announcement a title (up to 100 characters).';
      return content || i.attachments ? null : 'Write the announcement text or attach a file.';
    case 'qa':
      if (title.length < 3 || title.length > 120) return 'Ask your question in a short title (3 to 120 characters).';
      return null; // details are optional
    case 'media':
      if (title) return 'Media posts do not have titles.';
      return i.attachments ? null : 'Attach a photo to post in this channel.';
    case 'resources':
      if (title.length > 100) return 'Keep the title under 100 characters.';
      return i.attachments || LINK_RE.test(content) ? null : 'Share a link or attach a file.';
    case 'text':
      if (title) return 'Titles are not used in this channel.';
      return content || i.attachments ? null : 'Write a message or attach a file.';
  }
}
