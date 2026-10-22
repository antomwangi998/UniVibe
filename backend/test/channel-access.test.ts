import { describe, expect, it } from 'vitest';
import {
  type ChannelPolicy, type Viewer, canAcceptAnswer, channelPermissions, channelSlug, mayDeleteChannel, messageShapeError, normalizeSettings,
} from '../src/modules/channel_access';

const text: ChannelPolicy = { type: 'text', viewRole: 'members', postRole: 'members', locked: false, archived: false, repliesEnabled: true, allowAttachments: true };
const member: Viewer = { role: 'member', memberStatus: 'active' };
const mod: Viewer = { role: 'moderator', memberStatus: 'active' };
const owner: Viewer = { role: 'owner', memberStatus: 'active' };
const outsider: Viewer = { role: null, memberStatus: null };
const suspended: Viewer = { role: 'member', memberStatus: 'suspended' };

describe('channel permissions', () => {
  it('gives non-members and suspended members nothing, even in a public community', () => {
    for (const v of [outsider, suspended]) {
      const p = channelPermissions(v, 'active', text);
      expect(p.view).toBe(false);
      expect(p.send || p.reply || p.react || p.pin || p.deleteAny || p.deleteOwn || p.manage).toBe(false);
    }
    expect(channelPermissions(outsider, 'active', text).readOnlyReason).toBe('not_a_member');
    expect(channelPermissions(suspended, 'active', text).readOnlyReason).toBe('suspended');
  });

  it('lets an ordinary member read, write, react and delete their own, but not moderate', () => {
    const p = channelPermissions(member, 'active', text);
    expect(p).toMatchObject({ view: true, send: true, reply: true, react: true, attach: true, deleteOwn: true });
    expect(p.pin).toBe(false);
    expect(p.deleteAny).toBe(false);
    expect(p.manage).toBe(false);
    expect(p.readOnlyReason).toBeNull();
  });

  it('gives owner and moderator the staff powers', () => {
    for (const v of [mod, owner]) {
      const p = channelPermissions(v, 'active', text);
      expect(p).toMatchObject({ pin: true, deleteAny: true, manage: true });
    }
    expect(mayDeleteChannel('owner')).toBe(true);
    expect(mayDeleteChannel('moderator')).toBe(false);
    expect(mayDeleteChannel('member')).toBe(false);
    expect(mayDeleteChannel(null)).toBe(false);
  });

  it('hides staff-only channels from members entirely', () => {
    const hidden = { ...text, viewRole: 'staff' as const };
    expect(channelPermissions(member, 'active', hidden).view).toBe(false);
    expect(channelPermissions(mod, 'active', hidden).view).toBe(true);
  });

  it('locked channels are read-only for members but still writable for staff, and reactions stay open', () => {
    const locked = { ...text, locked: true };
    const m = channelPermissions(member, 'active', locked);
    expect(m).toMatchObject({ view: true, send: false, reply: false, react: true, readOnlyReason: 'channel_locked' });
    expect(channelPermissions(mod, 'active', locked)).toMatchObject({ send: true, reply: true });
  });

  it('announcement channels: only staff announce, even if the stored postRole says members', () => {
    const ann: ChannelPolicy = { ...text, type: 'announcement', postRole: 'members', repliesEnabled: false };
    const m = channelPermissions(member, 'active', ann);
    expect(m).toMatchObject({ view: true, send: false, reply: false, react: true, readOnlyReason: 'staff_only' });
    expect(channelPermissions(mod, 'active', ann).send).toBe(true);
    // Replies switched on: members can discuss but still cannot announce.
    const withReplies = channelPermissions(member, 'active', { ...ann, repliesEnabled: true });
    expect(withReplies).toMatchObject({ send: false, reply: true, readOnlyReason: null });
  });

  it('archived channels disappear for members and are read-only for staff', () => {
    const archived = { ...text, archived: true };
    expect(channelPermissions(member, 'active', archived).view).toBe(false);
    const s = channelPermissions(mod, 'active', archived);
    expect(s).toMatchObject({ view: true, send: false, reply: false, react: false, pin: false, readOnlyReason: 'channel_archived' });
  });

  it('an archived community is readable but never writable; suspended and deleted ones are closed', () => {
    const a = channelPermissions(member, 'archived', text);
    expect(a).toMatchObject({ view: true, send: false, reply: false, react: false, deleteOwn: false, readOnlyReason: 'community_inactive' });
    expect(channelPermissions(mod, 'archived', text)).toMatchObject({ view: true, send: false, manage: false, pin: false });
    for (const status of ['suspended', 'deleted']) expect(channelPermissions(owner, status, text).view).toBe(false);
  });

  it('attachments follow the channel setting and need a writing permission', () => {
    expect(channelPermissions(member, 'active', { ...text, allowAttachments: false }).attach).toBe(false);
    expect(channelPermissions(member, 'active', { ...text, locked: true }).attach).toBe(false);
  });

  it('accepting an answer: question author or staff, only in a Q&A channel that is open', () => {
    const qa: ChannelPolicy = { ...text, type: 'qa' };
    const p = channelPermissions(member, 'active', qa);
    expect(canAcceptAnswer(p, qa, true)).toBe(true);
    expect(canAcceptAnswer(p, qa, false)).toBe(false);
    expect(canAcceptAnswer(channelPermissions(mod, 'active', qa), qa, false)).toBe(true);
    expect(canAcceptAnswer(channelPermissions(member, 'active', text), text, true)).toBe(false);
    expect(canAcceptAnswer(channelPermissions(member, 'active', { ...qa, locked: true }), qa, true)).toBe(false);
    expect(canAcceptAnswer(channelPermissions(outsider, 'active', qa), qa, true)).toBe(false);
  });
});

describe('channel settings', () => {
  it('uses safe defaults', () => {
    expect(normalizeSettings({ type: 'text' })).toEqual({ type: 'text', viewRole: 'members', postRole: 'members', locked: false, repliesEnabled: true, allowAttachments: true });
  });
  it('forces what a type requires', () => {
    const a = normalizeSettings({ type: 'announcement', postRole: 'members' });
    expect(a.postRole).toBe('staff');
    expect(a.repliesEnabled).toBe(false);
    expect(normalizeSettings({ type: 'media', allowAttachments: false }).allowAttachments).toBe(true);
    expect(normalizeSettings({ type: 'resources', allowAttachments: false }).allowAttachments).toBe(true);
    expect(normalizeSettings({ type: 'text', allowAttachments: false }).allowAttachments).toBe(false);
  });
  it('makes channel names from free text', () => {
    expect(channelSlug('Programming Help!')).toBe('programming-help');
    expect(channelSlug('  Café  Corner ')).toBe('cafe-corner');
    expect(channelSlug('!!!')).toBe('');
    expect(channelSlug('a'.repeat(50))).toHaveLength(30);
  });
});

describe('message shape per channel type', () => {
  const base = { content: '', title: null as string | null, attachments: 0, isReply: false };
  it('text: needs words or an attachment, and no title', () => {
    expect(messageShapeError({ ...base, type: 'text' })).not.toBeNull();
    expect(messageShapeError({ ...base, type: 'text', content: 'hi' })).toBeNull();
    expect(messageShapeError({ ...base, type: 'text', attachments: 1 })).toBeNull();
    expect(messageShapeError({ ...base, type: 'text', content: 'hi', title: 'x' })).not.toBeNull();
  });
  it('announcement: needs a title', () => {
    expect(messageShapeError({ ...base, type: 'announcement', content: 'Hello everyone' })).not.toBeNull();
    expect(messageShapeError({ ...base, type: 'announcement', title: 'Exam week', content: 'Hello' })).toBeNull();
    expect(messageShapeError({ ...base, type: 'announcement', title: 'x'.repeat(101), content: 'Hello' })).not.toBeNull();
  });
  it('qa: a question needs a real title; the details are optional', () => {
    expect(messageShapeError({ ...base, type: 'qa', title: 'Hi' })).not.toBeNull();
    expect(messageShapeError({ ...base, type: 'qa', title: 'How do coroutines work?' })).toBeNull();
  });
  it('media: needs an attachment', () => {
    expect(messageShapeError({ ...base, type: 'media', content: 'look' })).not.toBeNull();
    expect(messageShapeError({ ...base, type: 'media', attachments: 2 })).toBeNull();
  });
  it('resources: needs a link or a file', () => {
    expect(messageShapeError({ ...base, type: 'resources', content: 'good notes' })).not.toBeNull();
    expect(messageShapeError({ ...base, type: 'resources', content: 'see https://example.com/notes' })).toBeNull();
    expect(messageShapeError({ ...base, type: 'resources', attachments: 1 })).toBeNull();
  });
  it('replies are free text in any channel, with no title', () => {
    for (const type of ['text', 'announcement', 'qa', 'resources', 'media'] as const) {
      expect(messageShapeError({ ...base, type, isReply: true, content: 'thanks' })).toBeNull();
      expect(messageShapeError({ ...base, type, isReply: true })).not.toBeNull();
      expect(messageShapeError({ ...base, type, isReply: true, content: 'x', title: 't' })).not.toBeNull();
    }
  });
});

describe('polls in channels', () => {
  const base = { content: 'Which day?', title: null as string | null, attachments: 0, isReply: false, poll: true };
  it('are allowed in text channels with a question', () => {
    expect(messageShapeError({ ...base, type: 'text' })).toBeNull();
    expect(messageShapeError({ ...base, type: 'text', content: '' })).not.toBeNull();
    expect(messageShapeError({ ...base, type: 'text', content: 'q'.repeat(281) })).not.toBeNull();
  });
  it('need their title in announcement channels', () => {
    expect(messageShapeError({ ...base, type: 'announcement' })).not.toBeNull();
    expect(messageShapeError({ ...base, type: 'announcement', title: 'Vote on the venue' })).toBeNull();
  });
  it('are refused where they make no sense, and as replies or with files', () => {
    for (const type of ['qa', 'resources', 'media'] as const) expect(messageShapeError({ ...base, type })).not.toBeNull();
    expect(messageShapeError({ ...base, type: 'text', isReply: true })).not.toBeNull();
    expect(messageShapeError({ ...base, type: 'text', attachments: 1 })).not.toBeNull();
  });
});
