import { createHmac, randomBytes, timingSafeEqual } from 'crypto';

/**
 * Each sponsored card the server hands out carries a signed token. Impressions and clicks are accepted only with a
 * token the server issued, to the same person, recently. That blocks invented campaign ids, replays and counting
 * for somebody else. The token holds no personal data a reader could use: the student id is inside the signature
 * check, not the payload.
 */
export const TOKEN_TTL_MS = 30 * 60_000;

export interface TokenClaims { c: string; p: string; n: string; t: number }

const b64 = (b: Buffer) => b.toString('base64url');
const mac = (secret: string, data: string, userId: string) => createHmac('sha256', secret).update(`token:${userId}:${data}`).digest();

export function issueToken(secret: string, userId: string, campaignId: string, placement: string, now = Date.now()): { token: string; nonce: string } {
  const nonce = randomBytes(12).toString('hex');
  const body = b64(Buffer.from(JSON.stringify({ c: campaignId, p: placement, n: nonce, t: now } satisfies TokenClaims)));
  return { token: `${body}.${b64(mac(secret, body, userId))}`, nonce };
}

/** Returns the claims, or null for anything forged, expired, malformed or issued to someone else. */
export function verifyToken(secret: string, userId: string, token: string, now = Date.now()): TokenClaims | null {
  if (typeof token !== 'string' || token.length > 600) return null;
  const [body, sig, extra] = token.split('.');
  if (!body || !sig || extra !== undefined) return null;
  let given: Buffer;
  try { given = Buffer.from(sig, 'base64url'); } catch { return null; }
  const want = mac(secret, body, userId);
  if (given.length !== want.length || !timingSafeEqual(given, want)) return null;
  try {
    const c = JSON.parse(Buffer.from(body, 'base64url').toString('utf8')) as TokenClaims;
    if (typeof c.c !== 'string' || typeof c.p !== 'string' || typeof c.n !== 'string' || typeof c.t !== 'number') return null;
    if (now - c.t > TOKEN_TTL_MS || c.t > now + 60_000) return null;
    return c;
  } catch { return null; }
}

/** A keyed hash of the student id. Stable (so caps work), not reversible without the server secret. */
export const viewerKey = (secret: string, userId: string): string =>
  createHmac('sha256', secret).update(`viewer:${userId}`).digest('hex').slice(0, 32);
