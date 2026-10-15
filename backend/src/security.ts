import { createHash, createHmac, randomBytes, randomInt, timingSafeEqual } from 'crypto';
import * as argon2 from 'argon2';
import { SignJWT, jwtVerify } from 'jose';

export const ACCESS_TTL_SECONDS = 900; // 15 minutes
export const REFRESH_TTL_DAYS = 30;
export const CODE_TTL_MINUTES = 10;
export const MAX_CODE_ATTEMPTS = 5;
export const RESEND_COOLDOWN_SECONDS = 60;

const ISSUER = 'univibe';
const AUDIENCE = 'univibe-app';

// ---- passwords (Argon2id)
export function hashPassword(password: string): Promise<string> {
  return argon2.hash(password, { type: argon2.argon2id });
}

export async function verifyPassword(hash: string, password: string): Promise<boolean> {
  try {
    return await argon2.verify(hash, password);
  } catch {
    return false;
  }
}

let dummyHash: Promise<string> | null = null;
/** Spends the same time as a real check so "no such account" is not faster than "wrong password". */
export async function burnPasswordCheck(password: string): Promise<void> {
  dummyHash ??= hashPassword('univibe-timing-equaliser');
  await verifyPassword(await dummyHash, password);
}

// ---- 6-digit email codes (stored only as an HMAC)
export function newCode(): string {
  return randomInt(0, 1_000_000).toString().padStart(6, '0');
}

export function hashCode(code: string, userId: string, purpose: string, pepper: string): string {
  return createHmac('sha256', pepper).update(`${purpose}:${userId}:${code}`).digest('hex');
}

export function safeEqualHex(a: string, b: string): boolean {
  const x = Buffer.from(a, 'hex');
  const y = Buffer.from(b, 'hex');
  return x.length === y.length && x.length > 0 && timingSafeEqual(x, y);
}

// ---- refresh tokens (random, stored hashed)
export function newRefreshToken(): string {
  return randomBytes(32).toString('base64url');
}

export function hashToken(token: string): string {
  return createHash('sha256').update(token).digest('hex');
}

// ---- access tokens (short-lived JWT)
const keyOf = (secret: string) => new TextEncoder().encode(secret);

export function signAccessToken(userId: string, secret: string): Promise<string> {
  return new SignJWT({})
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(userId)
    .setIssuer(ISSUER)
    .setAudience(AUDIENCE)
    .setIssuedAt()
    .setExpirationTime(`${ACCESS_TTL_SECONDS}s`)
    .sign(keyOf(secret));
}

/** Returns the user id, or null for any invalid, expired or tampered token. */
export async function verifyAccessToken(token: string, secret: string): Promise<string | null> {
  try {
    const { payload } = await jwtVerify(token, keyOf(secret), {
      issuer: ISSUER,
      audience: AUDIENCE,
      algorithms: ['HS256'],
    });
    return typeof payload.sub === 'string' ? payload.sub : null;
  } catch {
    return null;
  }
}

export function emailMatchesDomains(email: string, domains: string[]): boolean {
  const domain = email.split('@').pop()?.toLowerCase() ?? '';
  return domains.some((d) => domain === d || domain.endsWith(`.${d}`));
}
