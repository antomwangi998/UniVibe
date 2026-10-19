import { createRemoteJWKSet, jwtVerify, type JWTPayload, type JWTVerifyGetKey } from 'jose';

/** Google's public signing keys. jose caches them and honours Google's cache headers. */
export const GOOGLE_JWKS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
const GOOGLE_ISSUERS = ['https://accounts.google.com', 'accounts.google.com'];

/** What a Google ID token proved, after the signature, issuer, audience and expiry were checked. */
export interface GoogleIdentity {
  /** Google's stable, never-reused account id. The only value used to recognise a returning person. */
  subject: string;
  email: string;
  /** Always true here: tokens whose email is not verified are rejected. */
  emailVerified: true;
  name: string;
  /** The nonce claim, which the caller must match against a nonce this server issued. */
  nonce: string | null;
}

export type GoogleVerifier = (idToken: string) => Promise<GoogleIdentity>;

/** Any reason a token cannot be trusted. The message is for logs only and is never sent to the app. */
export class GoogleTokenError extends Error {}

/**
 * Builds the verifier. [clientIds] are the OAuth *web* client ids this server accepts as the token audience: the id the
 * Android app passes as its "server client id". [keys] is injectable so tests can sign tokens with their own key.
 */
export function createGoogleVerifier(clientIds: string[], keys?: JWTVerifyGetKey): GoogleVerifier {
  if (clientIds.length === 0) throw new Error('At least one Google client id is required.');
  const getKey = keys ?? createRemoteJWKSet(new URL(GOOGLE_JWKS_URL));
  return async (idToken) => {
    let payload: JWTPayload;
    try {
      ({ payload } = await jwtVerify(idToken, getKey, {
        issuer: GOOGLE_ISSUERS,
        audience: clientIds,
        algorithms: ['RS256'],
        clockTolerance: 10,
      }));
    } catch (e) {
      throw new GoogleTokenError(e instanceof Error ? e.message : 'token rejected');
    }
    const email = typeof payload.email === 'string' ? payload.email.trim().toLowerCase() : '';
    const verified = payload.email_verified === true || payload.email_verified === 'true';
    if (typeof payload.sub !== 'string' || payload.sub.length === 0 || payload.sub.length > 255) throw new GoogleTokenError('missing subject');
    if (!email || email.length > 254) throw new GoogleTokenError('missing email');
    if (!verified) throw new GoogleTokenError('email not verified by Google');
    const name = typeof payload.name === 'string' ? payload.name.trim().slice(0, 80) : '';
    return {
      subject: payload.sub,
      email,
      emailVerified: true,
      name,
      nonce: typeof payload.nonce === 'string' ? payload.nonce : null,
    };
  };
}

/** Reads GOOGLE_WEB_CLIENT_ID, which may hold several comma-separated ids (for example a staging and a production client). */
export function googleClientIds(value: string | undefined): string[] {
  return (value ?? '').split(',').map((s) => s.trim()).filter(Boolean);
}
