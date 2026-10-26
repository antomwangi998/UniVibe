import { describe, expect, it } from 'vitest';
import { TOKEN_TTL_MS, issueToken, verifyToken, viewerKey } from '../src/modules/monetization/sponsored_token';

const S = 's'.repeat(40);
const CAMP = '11111111-1111-4111-8111-111111111111';

describe('sponsored tokens', () => {
  it('verify for the person they were issued to', () => {
    const { token, nonce } = issueToken(S, 'user-a', CAMP, 'home_feed');
    const c = verifyToken(S, 'user-a', token);
    expect(c?.c).toBe(CAMP);
    expect(c?.p).toBe('home_feed');
    expect(c?.n).toBe(nonce);
  });
  it('are useless to anyone else', () => {
    const { token } = issueToken(S, 'user-a', CAMP, 'home_feed');
    expect(verifyToken(S, 'user-b', token)).toBeNull();
  });
  it('reject a different secret, tampering, junk and age', () => {
    const now = Date.now();
    const { token } = issueToken(S, 'user-a', CAMP, 'home_feed', now);
    expect(verifyToken('t'.repeat(40), 'user-a', token, now)).toBeNull();
    const [body, sig] = token.split('.');
    const forged = Buffer.from(JSON.stringify({ c: '22222222-2222-4222-8222-222222222222', p: 'home_feed', n: 'x'.repeat(24), t: now })).toString('base64url');
    expect(verifyToken(S, 'user-a', `${forged}.${sig}`, now)).toBeNull();
    for (const junk of ['', 'abc', 'a.b.c', `${body}.`, '.x', 'x'.repeat(700)]) expect(verifyToken(S, 'user-a', junk, now)).toBeNull();
    expect(verifyToken(S, 'user-a', token, now + TOKEN_TTL_MS + 1)).toBeNull();
    expect(verifyToken(S, 'user-a', token, now + TOKEN_TTL_MS - 1)).not.toBeNull();
    expect(verifyToken(S, 'user-a', token, now - 120_000)).toBeNull(); // issued "in the future"
  });
  it('every token has its own nonce', () => {
    expect(issueToken(S, 'u', CAMP, 'home_feed').nonce).not.toBe(issueToken(S, 'u', CAMP, 'home_feed').nonce);
  });
  it('viewer keys are stable, keyed and do not contain the id', () => {
    expect(viewerKey(S, 'user-a')).toBe(viewerKey(S, 'user-a'));
    expect(viewerKey(S, 'user-a')).not.toBe(viewerKey(S, 'user-b'));
    expect(viewerKey(S, 'user-a')).not.toBe(viewerKey('t'.repeat(40), 'user-a'));
    expect(viewerKey(S, 'user-a')).not.toContain('user-a');
    expect(viewerKey(S, 'user-a')).toHaveLength(32);
  });
});
