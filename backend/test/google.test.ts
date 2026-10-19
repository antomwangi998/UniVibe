import { SignJWT, createLocalJWKSet, exportJWK, generateKeyPair } from 'jose';
import { beforeAll, describe, expect, it } from 'vitest';
import { GoogleTokenError, createGoogleVerifier, googleClientIds } from '../src/google';

const CLIENT = 'web-client.apps.googleusercontent.com';
let privateKey: CryptoKey;
let otherKey: CryptoKey;
let verify: ReturnType<typeof createGoogleVerifier>;

type Over = { aud?: string; iss?: string; exp?: string | number; claims?: Record<string, unknown>; key?: CryptoKey; sub?: string | null };
async function token(o: Over = {}) {
  const jwt = new SignJWT({ email: 'Ada@Example.com', email_verified: true, name: 'Ada Lovelace', nonce: 'n'.repeat(20), ...o.claims })
    .setProtectedHeader({ alg: 'RS256', kid: 'k1' })
    .setIssuer(o.iss ?? 'https://accounts.google.com')
    .setAudience(o.aud ?? CLIENT)
    .setIssuedAt(typeof o.exp === 'number' ? o.exp - 3600 : undefined)
    .setExpirationTime(o.exp ?? '1h');
  if (o.sub !== null) jwt.setSubject(o.sub ?? '1234567890');
  return jwt.sign(o.key ?? privateKey);
}

beforeAll(async () => {
  const pair = await generateKeyPair('RS256');
  privateKey = pair.privateKey;
  otherKey = (await generateKeyPair('RS256')).privateKey;
  const jwk = { ...(await exportJWK(pair.publicKey)), kid: 'k1', alg: 'RS256', use: 'sig' };
  verify = createGoogleVerifier([CLIENT], createLocalJWKSet({ keys: [jwk] }));
});

describe('Google ID token verification', () => {
  it('accepts a valid token and returns only verified claims', async () => {
    const id = await verify(await token());
    expect(id).toMatchObject({ subject: '1234567890', email: 'ada@example.com', name: 'Ada Lovelace', emailVerified: true });
    expect(id.nonce).toBe('n'.repeat(20));
  });
  it('accepts the bare "accounts.google.com" issuer', async () => {
    await expect(verify(await token({ iss: 'accounts.google.com' }))).resolves.toBeTruthy();
  });
  it('rejects the wrong audience', async () => {
    await expect(verify(await token({ aud: 'someone-elses-client' }))).rejects.toBeInstanceOf(GoogleTokenError);
  });
  it('rejects the wrong issuer', async () => {
    await expect(verify(await token({ iss: 'https://evil.example' }))).rejects.toBeInstanceOf(GoogleTokenError);
  });
  it('rejects an expired token', async () => {
    await expect(verify(await token({ exp: Math.floor(Date.now() / 1000) - 600 }))).rejects.toBeInstanceOf(GoogleTokenError);
  });
  it('rejects a token signed by another key', async () => {
    await expect(verify(await token({ key: otherKey }))).rejects.toBeInstanceOf(GoogleTokenError);
  });
  it('rejects an unsigned (alg none) token', async () => {
    const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
    const forged = `${b64({ alg: 'none' })}.${b64({ iss: 'https://accounts.google.com', aud: CLIENT, sub: '1', email: 'a@b.co', email_verified: true, exp: Math.floor(Date.now() / 1000) + 600 })}.`;
    await expect(verify(forged)).rejects.toBeInstanceOf(GoogleTokenError);
  });
  it('rejects an unverified email, a missing email and a missing subject', async () => {
    await expect(verify(await token({ claims: { email_verified: false } }))).rejects.toBeInstanceOf(GoogleTokenError);
    await expect(verify(await token({ claims: { email: undefined } }))).rejects.toBeInstanceOf(GoogleTokenError);
    await expect(verify(await token({ sub: null }))).rejects.toBeInstanceOf(GoogleTokenError);
  });
  it('rejects garbage', async () => {
    await expect(verify('not-a-jwt')).rejects.toBeInstanceOf(GoogleTokenError);
  });
  it('parses a list of client ids', () => {
    expect(googleClientIds(' a , b ,, ')).toEqual(['a', 'b']);
    expect(googleClientIds(undefined)).toEqual([]);
  });
});
