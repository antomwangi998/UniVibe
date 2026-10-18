import type { Pool } from 'pg';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createPool, migrate } from '../src/db';
import { harness, type Student, url } from './helpers';

describe.skipIf(!url)('moderation and search (needs TEST_DATABASE_URL)', () => {
  let pool: Pool;
  const h = harness(() => pool);
  const { call } = h;
  const V1 = '/api/v1';

  beforeAll(async () => {
    pool = createPool(url as string);
    await migrate(pool);
    await pool.query("insert into universities (id, name, email_domains) values ('other', 'Other University', array['other.example']) on conflict do nothing");
  });
  afterAll(async () => { await pool.end(); });
  beforeEach(() => { h.make(); });
  afterEach(async () => { await h.close(); });

  const post = async (token: string, text: string, audience = 'campus') => (await call('POST', '/posts', token, { text, audience })).body.id as string;
  const makeRole = (s: Student, role: 'moderator' | 'admin') => pool.query('update users set role = $2 where id = $1', [s.id, role]);
  const report = (token: string, targetType: string, targetId: string, reason = 'spam') => call('POST', `${V1}/reports`, token, { targetType, targetId, reason });
  const queue = async (token: string, status = 'open') => (await call('GET', `${V1}/moderation/reports?status=${status}&limit=50`, token)).body.items as { id: string; targetId: string; reporter: { id: string } | null; preview: string | null; reportCount: number }[];

  describe('reporting', () => {
    it('accepts reports for visible things, dedupes, and validates input', async () => {
      const author = await h.student();
      const reporter = await h.student();
      const id = await post(author.token, 'spammy post');
      const first = await report(reporter.token, 'post', id);
      expect(first.status).toBe(201);
      const again = await report(reporter.token, 'post', id, 'harassment');
      expect(again.status).toBe(200);
      expect(again.body.id).toBe(first.body.id);
      expect(Number((await pool.query('select count(*) from reports where target_id = $1', [id])).rows[0].count)).toBe(1);
      expect((await report(reporter.token, 'post', id, 'not-a-reason')).status).toBe(400);
      expect((await report(reporter.token, 'planet', id)).status).toBe(400);
      expect((await call('POST', `${V1}/reports`, reporter.token, { targetType: 'post', targetId: id, reason: 'spam', status: 'dismissed' })).status).toBe(400);
      expect((await call('POST', `${V1}/reports`, reporter.token, { targetType: 'post', targetId: 'nope', reason: 'spam' })).status).toBe(400);
      expect((await call('POST', `${V1}/reports`, undefined, { targetType: 'post', targetId: id, reason: 'spam' })).status).toBe(401);
    });

    it('cannot report yourself, your own content, or anything you cannot see', async () => {
      const author = await h.student();
      const stranger = await h.student();
      const outsider = await h.student('other.example', 'other');
      const own = await post(author.token, 'mine');
      const followersOnly = await post(author.token, 'followers only', 'followers');
      expect((await report(author.token, 'post', own)).status).toBe(404);
      expect((await report(author.token, 'user', author.id)).status).toBe(404);
      expect((await report(stranger.token, 'post', followersOnly)).status).toBe(404);
      expect((await report(outsider.token, 'post', own)).status).toBe(404);
      expect((await report(outsider.token, 'user', author.id)).status).toBe(404);
      await call('POST', `${V1}/users/${stranger.id}/block`, author.token);
      expect((await report(author.token, 'user', stranger.id)).status).toBe(404); // blocked pairs cannot see each other
    });

    it('reports comments, users, events and only your own conversations\' messages', async () => {
      const a = await h.student();
      const b = await h.student();
      const outsider = await h.student();
      const id = await post(a.token, 'thread');
      const comment = (await call('POST', `/posts/${id}/comments`, a.token, { text: 'rude comment' })).body.id;
      expect((await report(b.token, 'comment', comment, 'harassment')).status).toBe(201);
      expect((await report(b.token, 'user', a.id, 'impersonation')).status).toBe(201);
      const hour = (n: number) => new Date(Date.now() + n * 3600_000).toISOString();
      const ev = await call('POST', `${V1}/events`, a.token, { title: 'Dodgy event', category: 'other', venueName: 'Somewhere', startsAt: hour(24), endsAt: hour(26) });
      expect((await report(b.token, 'event', ev.body.id)).status).toBe(201);
      const conv = (await call('POST', `${V1}/conversations`, a.token, { type: 'direct', userId: b.id })).body.id;
      const msg = (await call('POST', `${V1}/conversations/${conv}/messages`, a.token, { content: 'abusive text' })).body.id;
      expect((await report(b.token, 'message', msg, 'harassment')).status).toBe(201);
      expect((await report(outsider.token, 'message', msg, 'harassment')).status).toBe(404); // not in that conversation
      expect((await report(a.token, 'message', msg)).status).toBe(404); // your own message
    });

    it('rate limits reports', async () => {
      const author = await h.student();
      const reporter = await h.student();
      const ids: string[] = [];
      for (let i = 0; i < 21; i++) ids.push(await post(author.token, `post ${i}`));
      const results: number[] = [];
      for (const id of ids) results.push((await report(reporter.token, 'post', id)).status);
      expect(results.filter((s) => s === 201)).toHaveLength(20);
      expect(results[20]).toBe(429);
    });
  });

  describe('moderator workflow', () => {
    it('ordinary users cannot see or use any moderation endpoint', async () => {
      const a = await h.student();
      const reporter = await h.student();
      const id = await post(a.token, 'x');
      const rep = await report(reporter.token, 'post', id);
      for (const [m, p, body] of [
        ['GET', `${V1}/moderation/reports`, undefined],
        ['POST', `${V1}/moderation/reports/${rep.body.id}/review`, { status: 'dismissed' }],
        ['POST', `${V1}/moderation/reports/${rep.body.id}/action`, { action: 'remove_content' }],
        ['GET', `${V1}/moderation/audit-logs`, undefined],
      ] as const) {
        expect((await call(m, p, reporter.token, body)).status, p).toBe(403);
        expect((await call(m, p, undefined, body)).status, p).toBe(401);
      }
      // the person reported cannot read the report either
      expect((await call('GET', `${V1}/moderation/reports`, a.token)).status).toBe(403);
      await makeRole(reporter, 'moderator');
      expect((await call('GET', `${V1}/moderation/audit-logs`, reporter.token)).status).toBe(403); // moderators are not admins
    });

    it('moderators review, remove, restore and suspend, with every step audited', async () => {
      const mod = await h.student();
      const admin = await h.student();
      const author = await h.student();
      const reporter1 = await h.student();
      const reporter2 = await h.student();
      await makeRole(mod, 'moderator');
      await makeRole(admin, 'admin');
      const id = await post(author.token, 'violating post');
      const r1 = await report(reporter1.token, 'post', id, 'hate');
      await report(reporter2.token, 'post', id, 'spam');
      const items = await queue(mod.token);
      const mine = items.find((i) => i.id === r1.body.id)!;
      expect(mine.preview).toBe('violating post');
      expect(mine.reportCount).toBe(2);
      expect(mine.reporter!.id).toBe(reporter1.id); // moderators see who reported
      expect((await call('POST', `${V1}/moderation/reports/${r1.body.id}/review`, mod.token, { status: 'under_review', note: 'looking' })).body.status).toBe('under_review');
      expect((await queue(mod.token, 'under_review')).map((i) => i.id)).toContain(r1.body.id);
      // remove: gone for everyone, including the author, and from the feed
      expect((await call('POST', `${V1}/moderation/reports/${r1.body.id}/action`, mod.token, { action: 'remove_content', note: 'hate' })).body.status).toBe('action_taken');
      expect((await call('GET', `/posts/${id}`, reporter1.token)).status).toBe(404);
      expect((await call('GET', `/posts/${id}`, author.token)).status).toBe(404);
      // the other report about the same post was settled by the same decision
      expect(Number((await pool.query("select count(*) from reports where target_id = $1 and status = 'action_taken'", [id])).rows[0].count)).toBe(2);
      expect((await call('POST', `${V1}/moderation/reports/${r1.body.id}/review`, mod.token, { status: 'dismissed' })).status).toBe(409);
      // restore
      expect((await call('POST', `${V1}/moderation/reports/${r1.body.id}/action`, mod.token, { action: 'restore_content' })).status).toBe(200);
      expect((await call('GET', `/posts/${id}`, reporter1.token)).status).toBe(200);
      // suspend the author: locked out immediately, then restored
      expect((await call('POST', `${V1}/moderation/reports/${r1.body.id}/action`, mod.token, { action: 'suspend_user', days: 3 })).status).toBe(200);
      const locked = await call('GET', '/posts', author.token);
      expect(locked.status).toBe(403);
      expect(locked.body.error).toBe('account_suspended');
      expect((await call('POST', `${V1}/moderation/reports/${r1.body.id}/action`, mod.token, { action: 'unsuspend_user' })).status).toBe(200);
      expect((await call('GET', '/posts', author.token)).status).toBe(200);
      // audit trail: admin sees every privileged action
      const logs = (await call('GET', `${V1}/moderation/audit-logs?limit=50`, admin.token)).body.items as { action: string; actorId: string }[];
      const mine2 = logs.filter((l) => l.actorId === mod.id).map((l) => l.action);
      expect(mine2).toEqual(expect.arrayContaining(['report.under_review', 'remove_content', 'restore_content', 'suspend_user', 'unsuspend_user']));
    });

    it('validates actions and protects privileged accounts', async () => {
      const mod = await h.student();
      const mod2 = await h.student();
      const admin = await h.student();
      const reporter = await h.student();
      await makeRole(mod, 'moderator');
      await makeRole(mod2, 'moderator');
      await makeRole(admin, 'admin');
      const userReport = async (target: Student) => (await report(reporter.token, 'user', target.id)).body.id as string;
      const act = (token: string, id: string, body: Record<string, unknown>) => call('POST', `${V1}/moderation/reports/${id}/action`, token, body);
      // content actions need content
      const plain = await h.student();
      const r0 = await userReport(plain);
      expect((await act(mod.token, r0, { action: 'remove_content' })).status).toBe(422);
      expect((await act(mod.token, r0, { action: 'delete_everything' })).status).toBe(400);
      expect((await act(mod.token, r0, { action: 'suspend_user', days: 365 })).status).toBe(400);
      // admins are untouchable, moderators only suspendable by admins, nobody suspends themselves
      expect((await act(mod.token, await userReport(admin), { action: 'suspend_user' })).status).toBe(403);
      expect((await act(mod.token, await userReport(mod2), { action: 'suspend_user' })).status).toBe(403);
      expect((await act(admin.token, await userReport(mod2), { action: 'suspend_user', days: 1 })).status).toBe(200);
      const selfReport = (await report(reporter.token, 'user', mod.id)).body.id as string;
      await pool.query('update users set role = $2 where id = $1', [reporter.id, 'moderator']);
      expect((await act(mod.token, selfReport, { action: 'suspend_user' })).status).toBe(403);
      // unknown reports
      expect((await act(mod.token, '00000000-0000-4000-8000-000000000000', { action: 'suspend_user' })).status).toBe(404);
      expect((await act(mod.token, 'not-a-uuid', { action: 'suspend_user' })).status).toBe(404);
    });

    it('moderators only see their own university\'s reports', async () => {
      const outsiderMod = await h.student('other.example', 'other');
      await makeRole(outsiderMod, 'moderator');
      const author = await h.student();
      const reporter = await h.student();
      const rep = await report(reporter.token, 'post', await post(author.token, 'campus content'));
      expect((await queue(outsiderMod.token)).map((i) => i.id)).not.toContain(rep.body.id);
      expect((await call('POST', `${V1}/moderation/reports/${rep.body.id}/action`, outsiderMod.token, { action: 'remove_content' })).status).toBe(404);
    });
  });

  describe('search', () => {
    const tag = () => `Zq${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
    const rename = (s: Student, fullName: string) => call('PUT', '/me/profile', s.token, { course: 'X', interests: [], bio: '', fullName });

    it('finds only people the searcher may see, in handle order, with stable pages', async () => {
      const t = tag();
      const searcher = await h.student();
      const people = [await h.student(), await h.student(), await h.student()];
      const blocked = await h.student();
      const suspended = await h.student();
      const outsider = await h.student('other.example', 'other');
      for (const p of [...people, blocked, suspended, outsider]) await rename(p, `${t} Person`);
      await call('POST', `${V1}/users/${blocked.id}/block`, searcher.token);
      await pool.query("update users set suspended_until = now() + interval '1 day' where id = $1", [suspended.id]);
      const all = await call('GET', `${V1}/search/users?q=${t}&limit=50`, searcher.token);
      expect(all.body.items.map((u: { id: string }) => u.id).sort()).toEqual(people.map((p) => p.id).sort());
      const seen: string[] = [];
      let cursor: string | null = null;
      do {
        const res: { body: { items: { id: string }[]; nextCursor: string | null } } = await call('GET', `${V1}/search/users?q=${t}&limit=2${cursor ? `&cursor=${cursor}` : ''}`, searcher.token);
        res.body.items.forEach((u) => seen.push(u.id));
        cursor = res.body.nextCursor;
      } while (cursor);
      expect(seen).toHaveLength(3);
      expect(new Set(seen).size).toBe(3);
      expect(JSON.stringify(all.body)).not.toMatch(/@lakeside\.example/); // emails are never searched or returned
      // the searcher never finds themselves
      await rename(searcher, `${t} Me`);
      expect((await call('GET', `${V1}/search/users?q=${t}&limit=50`, searcher.token)).body.items.map((u: { id: string }) => u.id)).not.toContain(searcher.id);
    });

    it('finds only posts the searcher may see', async () => {
      const t = tag();
      const author = await h.student();
      const reader = await h.student();
      const follower = await h.student();
      const outsider = await h.student('other.example', 'other');
      await call('PUT', `/students/${author.id}/follow`, follower.token);
      const open = await post(author.token, `${t} open`);
      const followersOnly = await post(author.token, `${t} followers`, 'followers');
      const removed = await post(author.token, `${t} removed`);
      await pool.query("update posts set status = 'removed' where id = $1", [removed]);
      const ids = async (s: Student) => (await call('GET', `${V1}/search/posts?q=${t}&limit=50`, s.token)).body.items.map((p: { id: string }) => p.id);
      expect(await ids(reader)).toEqual([open]);
      expect((await ids(follower)).sort()).toEqual([open, followersOnly].sort());
      expect(await ids(outsider)).toEqual([]);
      await call('POST', `${V1}/users/${author.id}/block`, reader.token);
      expect(await ids(reader)).toEqual([]);
      const hit = (await call('GET', `${V1}/search/posts?q=${t}`, follower.token)).body.items[0];
      expect(hit.snippet).toContain(t);
      expect(hit.author.id).toBe(author.id);
    });

    it('finds upcoming, visible events only', async () => {
      const t = tag();
      const org = await h.student();
      const mate = await h.student();
      const outsider = await h.student('other.example', 'other');
      const hour = (n: number) => new Date(Date.now() + n * 3600_000).toISOString();
      const mk = async (title: string, over: Record<string, unknown> = {}) =>
        (await call('POST', `${V1}/events`, org.token, { title, category: 'other', venueName: 'Hall', startsAt: hour(24), endsAt: hour(26), ...over })).body.id as string;
      const campus = await mk(`${t} campus`);
      const pub = await mk(`${t} public`, { visibility: 'public' });
      const cancelled = await mk(`${t} cancelled`);
      await call('POST', `${V1}/events/${cancelled}/cancel`, org.token);
      const ids = async (s: Student) => (await call('GET', `${V1}/search/events?q=${t}&limit=50`, s.token)).body.items.map((e: { id: string }) => e.id);
      expect((await ids(mate)).sort()).toEqual([campus, pub].sort());
      expect(await ids(outsider)).toEqual([pub]);
    });

    it('combined search returns each kind', async () => {
      const t = tag();
      const me = await h.student();
      const other = await h.student();
      await rename(other, `${t} Combo`);
      await post(other.token, `${t} combined post`);
      const res = await call('GET', `${V1}/search?q=${t}`, me.token);
      expect(res.status).toBe(200);
      expect(res.body.users).toHaveLength(1);
      expect(res.body.posts).toHaveLength(1);
      expect(res.body.events).toEqual([]);
    });

    it('validates queries, treats wildcards literally, resists injection and is rate limited', async () => {
      const me = await h.student();
      expect((await call('GET', `${V1}/search/users`, me.token)).status).toBe(400);
      expect((await call('GET', `${V1}/search/users?q=a`, me.token)).status).toBe(400);
      expect((await call('GET', `${V1}/search/users?q=${'x'.repeat(81)}`, me.token)).status).toBe(400);
      expect((await call('GET', `${V1}/search/users?q=ab&cursor=zzz`, me.token)).status).toBe(400);
      expect((await call('GET', `${V1}/search/posts?q=ab&limit=500`, me.token)).status).toBe(400);
      expect((await call('GET', `${V1}/search/users?q=ab`)).status).toBe(401);
      await post(me.token, 'plain text here');
      for (const q of ['%%', '__', '\\\\']) {
        const res = await call('GET', `${V1}/search/posts?q=${encodeURIComponent(q)}`, me.token);
        expect(res.status).toBe(200);
        expect(res.body.items).toEqual([]); // wildcards are not wildcards
      }
      const inj = await call('GET', `${V1}/search?q=${encodeURIComponent("'; drop table users; --")}`, me.token);
      expect(inj.status).toBe(200);
      expect((await pool.query('select count(*) from users')).rows[0].count).not.toBe('0');
      let limited = 0;
      for (let i = 0; i < 70; i++) if ((await call('GET', `${V1}/search/users?q=ab`, me.token)).status === 429) limited++;
      expect(limited).toBeGreaterThan(0);
    });
  });
});
