import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, url } from './helpers';

describe.skipIf(!url)('story reactions, replies, viewers, audience (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => { pool = createPool(url as string); await migrate(pool); });
  afterAll(async () => { await pool.end(); });
  beforeEach(async () => { await pool.query('delete from stories'); h.make(); });
  afterEach(async () => { await h.close(); });

  const story = async (token: string, extra: Record<string, unknown> = {}) =>
    (await call('POST', `${V1}/stories`, token, { kind: 'text', text: 'hello', ...extra })).body;

  it('one reaction per person, replaceable, removable; never on my own Story; unknown reactions refused', async () => {
    const a = await h.student();
    const b = await h.student();
    const s = await story(a.token);
    expect((await call('PUT', `${V1}/stories/${s.id}/reaction`, a.token, { reaction: 'heart' })).status).toBe(400);
    expect((await call('PUT', `${V1}/stories/${s.id}/reaction`, b.token, { reaction: 'poop' })).status).toBe(400);
    expect((await call('PUT', `${V1}/stories/${s.id}/reaction`, b.token, { reaction: 'heart' })).status).toBe(200);
    expect((await call('PUT', `${V1}/stories/${s.id}/reaction`, b.token, { reaction: 'heart' })).status).toBe(200); // repeat is safe
    expect((await call('PUT', `${V1}/stories/${s.id}/reaction`, b.token, { reaction: 'fire' })).status).toBe(200); // replaces
    expect((await pool.query('select reaction from story_reactions where story_id = $1', [s.id])).rows).toEqual([{ reaction: 'fire' }]);
    // The author is notified once, however many times it changed.
    expect((await pool.query("select count(*)::int as n from notifications where recipient_id = $1 and type = 'story_reaction'", [a.id])).rows[0].n).toBe(1);
    const mine = (await call('GET', `${V1}/me/stories`, a.token)).body;
    expect(mine.items[0]).toMatchObject({ id: s.id, viewCount: 0, reactions: { fire: 1 } });
    expect((await call('DELETE', `${V1}/stories/${s.id}/reaction`, b.token)).status).toBe(204);
    expect((await pool.query('select count(*)::int as n from story_reactions where story_id = $1', [s.id])).rows[0].n).toBe(0);
  });

  it('reacting needs the right to see the Story: blocked and non-followers get "not found"', async () => {
    const a = await h.student();
    const stranger = await h.student();
    const friend = await h.student();
    await call('PUT', `/students/${a.id}/follow`, friend.token);
    const s = await story(a.token, { audience: 'followers' });
    expect((await call('PUT', `${V1}/stories/${s.id}/reaction`, stranger.token, { reaction: 'like' })).status).toBe(404);
    expect((await call('PUT', `${V1}/stories/${s.id}/reaction`, friend.token, { reaction: 'like' })).status).toBe(200);
    await call('POST', `${V1}/users/${friend.id}/block`, a.token);
    expect((await call('PUT', `${V1}/stories/${s.id}/reaction`, friend.token, { reaction: 'laugh' })).status).toBe(404);
  });

  it('only the author can list viewers, and blocked people are left out', async () => {
    const a = await h.student();
    const b = await h.student();
    const c = await h.student();
    const s = await story(a.token);
    await call('POST', `${V1}/stories/${s.id}/view`, b.token);
    await call('POST', `${V1}/stories/${s.id}/view`, c.token);
    await call('PUT', `${V1}/stories/${s.id}/reaction`, b.token, { reaction: 'sad' });
    expect((await call('GET', `${V1}/stories/${s.id}/viewers`, b.token)).status).toBe(404);
    const all = (await call('GET', `${V1}/stories/${s.id}/viewers`, a.token)).body;
    expect(all.total).toBe(2);
    expect(all.items.map((x: { viewer: { id: string } }) => x.viewer.id).sort()).toEqual([b.id, c.id].sort());
    expect(all.items.find((x: { viewer: { id: string } }) => x.viewer.id === b.id).reaction).toBe('sad');
    await call('POST', `${V1}/users/${c.id}/block`, a.token);
    const after = (await call('GET', `${V1}/stories/${s.id}/viewers?limit=1`, a.token)).body;
    expect(after.items).toHaveLength(1);
    expect(after.items[0].viewer.id).toBe(b.id);
  });

  it('a reply is a normal chat message that points at the Story, and respects messaging rules', async () => {
    const a = await h.student();
    const b = await h.student();
    const s = await story(a.token);
    expect((await call('POST', `${V1}/stories/${s.id}/reply`, a.token, { content: 'hi me' })).status).toBe(400);
    expect((await call('POST', `${V1}/stories/${s.id}/reply`, b.token, { content: '   ' })).status).toBe(400);
    const sent = await call('POST', `${V1}/stories/${s.id}/reply`, b.token, { content: 'nice one', clientId: 'c1' });
    expect(sent.status).toBe(201);
    expect(sent.body.message).toMatchObject({ content: 'nice one', story: { id: s.id, kind: 'text', available: true } });
    // Retrying with the same clientId creates nothing new.
    const again = await call('POST', `${V1}/stories/${s.id}/reply`, b.token, { content: 'nice one', clientId: 'c1' });
    expect(again.status).toBe(200);
    expect((await pool.query('select count(*)::int as n from messages where conversation_id = $1', [sent.body.conversationId])).rows[0].n).toBe(1);
    // The author sees it in the same conversation.
    const list = await call('GET', `${V1}/conversations/${sent.body.conversationId}/messages`, a.token);
    expect(list.status).toBe(200);
    // Once the Story expires the message stays, but only says it is gone.
    await pool.query("update stories set expires_at = now() - interval '1 minute' where id = $1", [s.id]);
    const later = (await call('GET', `${V1}/conversations/${sent.body.conversationId}/messages`, a.token)).body;
    const msg = (later.items ?? later.messages)[0];
    expect(msg.story).toMatchObject({ kind: 'text', available: false });
    expect((await call('POST', `${V1}/stories/${s.id}/reply`, b.token, { content: 'too late' })).status).toBe(404);
  });

  it('replies obey blocks and the author’s “who can message me” setting', async () => {
    const a = await h.student();
    const b = await h.student();
    const s = await story(a.token);
    await pool.query("insert into privacy_settings (user_id, message_permission) values ($1, 'nobody') on conflict (user_id) do update set message_permission = 'nobody'", [a.id]);
    expect((await call('POST', `${V1}/stories/${s.id}/reply`, b.token, { content: 'hey' })).status).toBe(403);
    await pool.query("update privacy_settings set message_permission = 'everyone' where user_id = $1", [a.id]);
    await call('POST', `${V1}/users/${b.id}/block`, a.token);
    expect((await call('POST', `${V1}/stories/${s.id}/reply`, b.token, { content: 'hey' })).status).toBe(404);
  });

  it('changing the audience takes effect at once and only the author can do it', async () => {
    const a = await h.student();
    const b = await h.student();
    const s = await story(a.token);
    expect((await call('GET', `${V1}/users/${a.id}/stories`, b.token)).status).toBe(200);
    expect((await call('PATCH', `${V1}/stories/${s.id}`, b.token, { audience: 'followers' })).status).toBe(404);
    expect((await call('PATCH', `${V1}/stories/${s.id}`, a.token, { audience: 'public' })).status).toBe(400);
    const changed = await call('PATCH', `${V1}/stories/${s.id}`, a.token, { audience: 'followers' });
    expect(changed.status).toBe(200);
    expect((await call('GET', `${V1}/users/${a.id}/stories`, b.token)).status).toBe(404);
    expect((await call('POST', `${V1}/stories/${s.id}/view`, b.token)).status).toBe(404);
  });

  it('a Story can be reported once by someone who may see it', async () => {
    const a = await h.student();
    const b = await h.student();
    const stranger = await h.student();
    const s = await story(a.token, { audience: 'followers' });
    await call('PUT', `/students/${a.id}/follow`, b.token);
    const body = { targetType: 'story', targetId: s.id, reason: 'spam' };
    expect((await call('POST', `${V1}/reports`, stranger.token, body)).status).toBe(404);
    expect((await call('POST', `${V1}/reports`, b.token, body)).status).toBe(201);
    expect((await call('POST', `${V1}/reports`, b.token, body)).status).toBe(200);
    expect((await call('POST', `${V1}/reports`, a.token, body)).status).toBe(404); // not your own
  });
});
