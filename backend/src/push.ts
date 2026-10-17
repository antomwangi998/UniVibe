import { SignJWT, importPKCS8 } from 'jose';

export type PushResult =
  | { ok: true }
  | { ok: false; retryable: boolean; invalidToken: boolean; reason: string };

export interface PushMessage {
  /** FCM data payload: string values only. Ids and generic labels, never tokens or message text. */
  data: Record<string, string>;
  /** Pending messages with the same key replace each other on the device. */
  collapseKey?: string;
}

export interface PushSender {
  readonly enabled: boolean;
  send(token: string, message: PushMessage): Promise<PushResult>;
}

/** Used when no Firebase credentials are configured: push is skipped and everything else keeps working. */
export const disabledPushSender: PushSender = {
  enabled: false,
  send: async () => ({ ok: false, retryable: false, invalidToken: false, reason: 'push_not_configured' }),
};

interface ServiceAccount { project_id: string; client_email: string; private_key: string }

/** Accepts the service-account JSON as is, or base64-encoded (easier to paste into a dashboard). */
export function parseServiceAccount(raw: string): ServiceAccount {
  // Dashboards sometimes keep wrapping quotes or line breaks from a paste; drop them.
  const trimmed = raw.trim().replace(/^(['"])([\s\S]*)\1$/, '$2').trim();
  const text = trimmed.startsWith('{') ? trimmed : Buffer.from(trimmed.replace(/\s+/g, ''), 'base64').toString('utf8');
  let parsed: Partial<ServiceAccount>;
  try {
    parsed = JSON.parse(text) as Partial<ServiceAccount>;
  } catch {
    // Deliberately not echoing the value or the parser's position: it is a secret.
    throw new Error('the value is not valid JSON (it was probably cut off or altered when pasted)');
  }
  if (!parsed.project_id || !parsed.client_email || !parsed.private_key) {
    throw new Error('FCM_SERVICE_ACCOUNT_JSON must contain project_id, client_email and private_key');
  }
  return { project_id: parsed.project_id, client_email: parsed.client_email, private_key: parsed.private_key };
}

type FetchFn = typeof fetch;

/**
 * Firebase Cloud Messaging through the HTTP v1 API, signed with the service account (RS256 via jose).
 * Delivery is best effort: "ok" means FCM accepted the request, not that a phone showed anything.
 */
export function createFcmSender(rawServiceAccount: string, fetchFn: FetchFn = fetch): PushSender {
  const account = parseServiceAccount(rawServiceAccount);
  let cached: { token: string; expiresAt: number } | null = null;

  async function accessToken(force = false): Promise<string> {
    if (!force && cached && cached.expiresAt > Date.now() + 60_000) return cached.token;
    const key = await importPKCS8(account.private_key, 'RS256');
    const assertion = await new SignJWT({ scope: 'https://www.googleapis.com/auth/firebase.messaging' })
      .setProtectedHeader({ alg: 'RS256', typ: 'JWT' })
      .setIssuer(account.client_email).setSubject(account.client_email)
      .setAudience('https://oauth2.googleapis.com/token')
      .setIssuedAt().setExpirationTime('1h').sign(key);
    const res = await fetchFn('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }),
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) throw new Error(`google token endpoint answered ${res.status}`);
    const body = (await res.json()) as { access_token: string; expires_in: number };
    cached = { token: body.access_token, expiresAt: Date.now() + body.expires_in * 1000 };
    return cached.token;
  }

  async function post(token: string, message: PushMessage, bearer: string): Promise<Response> {
    return fetchFn(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${bearer}` },
      body: JSON.stringify({
        message: {
          token,
          data: message.data,
          android: { priority: 'HIGH', ttl: '3600s', ...(message.collapseKey ? { collapse_key: message.collapseKey } : {}) },
        },
      }),
      signal: AbortSignal.timeout(10_000),
    });
  }

  return {
    enabled: true,
    async send(token, message) {
      try {
        let res = await post(token, message, await accessToken());
        if (res.status === 401) res = await post(token, message, await accessToken(true)); // the cached token may have been revoked
        if (res.ok) return { ok: true };
        const body = (await res.json().catch(() => ({}))) as { error?: { status?: string; message?: string; details?: { errorCode?: string }[] } };
        const code = body.error?.details?.find((d) => d.errorCode)?.errorCode ?? body.error?.status ?? '';
        // The token is dead or was issued for another project: stop using it.
        if (res.status === 404 || code === 'UNREGISTERED' || code === 'SENDER_ID_MISMATCH') {
          return { ok: false, retryable: false, invalidToken: true, reason: code || 'unregistered' };
        }
        if (res.status === 400 && /registration token/i.test(body.error?.message ?? '')) {
          return { ok: false, retryable: false, invalidToken: true, reason: 'invalid_token' };
        }
        if (res.status === 429 || res.status >= 500 || res.status === 401) {
          return { ok: false, retryable: true, invalidToken: false, reason: code || `http_${res.status}` };
        }
        return { ok: false, retryable: false, invalidToken: false, reason: code || `http_${res.status}` };
      } catch (e) {
        // Timeouts and network errors are transient.
        return { ok: false, retryable: true, invalidToken: false, reason: e instanceof Error ? e.name : 'network_error' };
      }
    },
  };
}
