import type { FastifyInstance } from 'fastify';
import { ADMIN_CSS } from './style';
import { ADMIN_JS } from './script';

const HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow"><title>UniVibe admin</title>
<link rel="stylesheet" href="/admin/app.css"></head>
<body><div id="app"></div><script src="/admin/app.js"></script></body></html>`;

/**
 * The admin dashboard: one page, one script, one stylesheet, no libraries and no inline code, so a strict
 * content-security policy can forbid everything else. The page holds no data; it signs in with a normal account and
 * calls the API, and the API decides what that account may see.
 */
export function adminPageRoutes() {
  return async (app: FastifyInstance) => {
    const headers = (type: string) => ({
      'content-type': type,
      'cache-control': 'no-store',
      'content-security-policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
      'x-frame-options': 'DENY',
      'referrer-policy': 'no-referrer',
    });
    app.get('/admin', async (_req, reply) => reply.headers(headers('text/html; charset=utf-8')).send(HTML));
    app.get('/admin/app.js', async (_req, reply) => reply.headers(headers('application/javascript; charset=utf-8')).send(ADMIN_JS));
    app.get('/admin/app.css', async (_req, reply) => reply.headers(headers('text/css; charset=utf-8')).send(ADMIN_CSS));
  };
}
