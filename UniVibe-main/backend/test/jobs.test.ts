import { generateKeyPair, exportPKCS8 } from 'jose';
import { describe, expect, it } from 'vitest';
import { backoffSeconds } from '../src/jobs';
import { createFcmSender, parseServiceAccount } from '../src/push';

describe('backoffSeconds', () => {
  it('doubles each attempt and stays within half to full of the base (equal jitter)', () => {
    expect(backoffSeconds(1, () => 0)).toBe(3);   // base 5 -> 2.5 -> rounds to 3
    expect(backoffSeconds(1, () => 1)).toBe(5);
    expect(backoffSeconds(2, () => 1)).toBe(10);
    expect(backoffSeconds(3, () => 1)).toBe(20);
  });
  it('is capped at 15 minutes', () => {
    expect(backoffSeconds(30, () => 1)).toBe(900);
    expect(backoffSeconds(30, () => 0)).toBe(450);
  });
});

describe('FCM sender', () => {
  async function sender(handler: (url: string, init: RequestInit) => Response) {
    const { privateKey } = await generateKeyPair('RS256', { extractable: true });
    const json = JSON.stringify({ project_id: 'proj', client_email: 'svc@proj.iam.gserviceaccount.com', private_key: await exportPKCS8(privateKey) });
    const calls: { url: string; body: string }[] = [];
    const fake = (async (url: string, init: RequestInit) => {
      calls.push({ url, body: String(init.body) });
      if (url.includes('oauth2.googleapis.com')) return new Response(JSON.stringify({ access_token: 'at', expires_in: 3600 }), { status: 200 });
      return handler(url, init);
    }) as unknown as typeof fetch;
    return { s: createFcmSender(json, fake), calls, json };
  }
  const fcmError = (status: number, errorCode: string, message = 'x') =>
    new Response(JSON.stringify({ error: { status: 'X', message, details: [{ errorCode }] } }), { status });

  it('sends a data-only message and never puts the service account in it', async () => {
    const { s, calls } = await sender(() => new Response('{}', { status: 200 }));
    expect(await s.send('t'.repeat(30), { data: { kind: 'message' }, collapseKey: 'c:1' })).toEqual({ ok: true });
    const sent = JSON.parse(calls[calls.length - 1].body).message;
    expect(sent.data).toEqual({ kind: 'message' });
    expect(sent.notification).toBeUndefined();
    expect(JSON.stringify(sent)).not.toContain('private_key');
  });
  it('marks dead tokens invalid and does not retry them', async () => {
    const { s } = await sender(() => fcmError(404, 'UNREGISTERED'));
    expect(await s.send('t'.repeat(30), { data: {} })).toMatchObject({ ok: false, invalidToken: true, retryable: false });
  });
  it('treats 429 and 5xx as retryable', async () => {
    for (const status of [429, 500, 503]) {
      const { s } = await sender(() => fcmError(status, 'UNAVAILABLE'));
      expect(await s.send('t'.repeat(30), { data: {} })).toMatchObject({ ok: false, retryable: true, invalidToken: false });
    }
  });
  it('treats other client errors as permanent for the message, not the token', async () => {
    const { s } = await sender(() => fcmError(400, 'INVALID_ARGUMENT', 'bad payload'));
    expect(await s.send('t'.repeat(30), { data: {} })).toMatchObject({ ok: false, retryable: false, invalidToken: false });
  });
  it('accepts the service account as base64 and rejects incomplete ones', async () => {
    const { json } = await sender(() => new Response('{}'));
    expect(parseServiceAccount(Buffer.from(json).toString('base64')).project_id).toBe('proj');
    expect(() => parseServiceAccount('{"project_id":"p"}')).toThrow();
  });
});
