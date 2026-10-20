import dns from 'node:dns';
import http from 'node:http';
import https from 'node:https';
import { BlockList, isIP, type LookupFunction } from 'node:net';

/**
 * Link previews: the server reads a web page once, keeps its title and description, and every reader sees the same card.
 * The server does the fetching (not the phones) so a reader's address is never shown to the site, and everything here is
 * written so that a message can never make the server reach into its own network:
 *  - only http(s), only ports 80 and 443, no passwords in the address, no raw IP addresses, no one-word host names;
 *  - the connection itself refuses private, loopback, link-local and other non-public addresses (checked at connect
 *    time, so a host name that changes its answer cannot slip through);
 *  - redirects are followed by hand, at most 3, and every hop goes through the same checks;
 *  - 4 seconds of silence or 6 seconds in total ends the fetch, and at most 128 KB of an HTML page (3 MB of an image) is read.
 * The page's picture is fetched under the same rules, checked by its first bytes (not its label), and re-encoded by the caller.
 * Any failure simply means "no preview".
 */

export interface LinkPreviewData {
  host: string; title: string | null; description: string | null; siteName: string | null;
  /** The page's picture (Open Graph / Twitter card) as a full, allowed address, or null. Not fetched yet. */
  imageUrl: string | null;
}

// ---- Finding the link in a message (the app uses the same rules to make links tappable) ----

const LINK_RE = /\b(?:https?:\/\/|www\.)[^\s<>"]+/gi;
const TRAILING = '.,!?;:\'"]}';
const MAX_URL_LENGTH = 2048;

const count = (s: string, ch: string) => s.split(ch).length - 1;

/** The first web address in [text], ready to open (a bare "www." address gets https://), or null. */
export function firstLink(text: string | null | undefined): string | null {
  if (!text) return null;
  for (const m of text.matchAll(LINK_RE)) {
    let end = m[0].length;
    while (end > 0) {
      const c = m[0][end - 1];
      const part = m[0].slice(0, end);
      if (TRAILING.includes(c) || (c === ')' && count(part, ')') > count(part, '('))) end -= 1; else break;
    }
    const shown = m[0].slice(0, end);
    if (!shown.replace(/^https?:\/\//i, '').replace(/^www\./i, '')) continue;
    const url = /^www\./i.test(shown) ? `https://${shown}` : shown;
    if (url.length <= MAX_URL_LENGTH) return url;
  }
  return null;
}

// ---- Which addresses the server may connect to ----

const blocked = new BlockList();
for (const [net, prefix] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16], ['172.16.0.0', 12],
  ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.88.99.0', 24], ['192.168.0.0', 16], ['198.18.0.0', 15],
  ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 4], ['240.0.0.0', 4],
] as const) blocked.addSubnet(net, prefix, 'ipv4');
for (const [net, prefix] of [
  ['64:ff9b::', 96], ['64:ff9b:1::', 48], ['100::', 64], ['2001::', 32], ['2001:db8::', 32],
  ['2002::', 16], ['fc00::', 7], ['fe80::', 10], ['fec0::', 10], ['ff00::', 8],
] as const) blocked.addSubnet(net, prefix, 'ipv6');

/** The 16 bytes of an IPv6 address (handles "::" and a dotted IPv4 tail), or null if it is not one. */
function ipv6Bytes(ip: string): number[] | null {
  let s = ip.toLowerCase();
  const zone = s.indexOf('%');
  if (zone >= 0) s = s.slice(0, zone);
  if (s.includes('.')) {
    const cut = s.lastIndexOf(':');
    const v4 = s.slice(cut + 1).split('.').map(Number);
    if (v4.length !== 4 || v4.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return null;
    s = `${s.slice(0, cut + 1)}${((v4[0] << 8) | v4[1]).toString(16)}:${((v4[2] << 8) | v4[3]).toString(16)}`;
  }
  const halves = s.split('::');
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(':') : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  const groups = halves.length === 2 ? [...head, ...Array<string>(Math.max(0, 8 - head.length - tail.length)).fill('0'), ...tail] : head;
  if (groups.length !== 8 || groups.some((g) => !/^[0-9a-f]{1,4}$/.test(g))) return null;
  return groups.flatMap((g) => { const n = parseInt(g, 16); return [n >> 8, n & 255]; });
}

/** True for anything that is not an ordinary public internet address (and for anything that is not an address at all). */
export function isBlockedAddress(ip: string): boolean {
  const family = isIP(ip);
  if (!family) return true;
  if (family === 4) return blocked.check(ip, 'ipv4');
  const b = ipv6Bytes(ip);
  if (!b) return true;
  // "::a.b.c.d" and "::ffff:a.b.c.d" are IPv4 addresses in disguise (and :: and ::1 start the same way): never a real destination.
  // (Node's block list would treat every IPv4 address as matching a rule for them, so they are recognised here by their bytes.)
  if (b.slice(0, 10).every((x) => x === 0) && ((b[10] === 0xff && b[11] === 0xff) || (b[10] === 0 && b[11] === 0))) return true;
  return blocked.check(ip, 'ipv6');
}

/** The address as a URL if the server may fetch it, otherwise null. */
export function parsePublicUrl(raw: string): URL | null {
  let u: URL;
  try { u = new URL(raw); } catch { return null; }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
  if (u.username || u.password) return null;
  if (u.port && u.port !== '80' && u.port !== '443') return null; // the default port reads as ''
  const host = u.hostname.toLowerCase().replace(/\.$/, '');
  // new URL() already turns 2130706433 and 0x7f.1 into 127.0.0.1, so one check catches every spelling of an IP address.
  if (!host || isIP(host.replace(/^\[|\]$/g, '')) !== 0) return null;
  if (!host.includes('.') || /\.(local|localhost|internal|lan|home|corp|intranet)$/.test(host)) return null;
  return u;
}

/** The connection's own address check. Runs when connecting, on the address that will actually be used. */
const guardedLookup: LookupFunction = (hostname, options, cb) => {
  dns.lookup(hostname, { all: true, verbatim: true }, (err, addresses) => {
    if (err) return cb(err, '', 4);
    if (!addresses.length || addresses.some((a) => isBlockedAddress(a.address))) return cb(new Error('blocked address'), '', 4);
    // Newer Node asks for every address at once (for happy eyeballs); older asks for one.
    if (options.all) (cb as unknown as (e: null, a: dns.LookupAddress[]) => void)(null, addresses);
    else cb(null, addresses[0].address, addresses[0].family);
  });
};

// ---- Fetching ----

export interface Page { status: number; location?: string; contentType: string; body: string }
export type PageFetcher = (url: URL) => Promise<Page>;
export interface Download { status: number; location?: string; contentType: string; bytes: Uint8Array; /** The file was bigger than the limit. */ truncated: boolean }
export type Downloader = (url: URL) => Promise<Download>;

const MAX_PAGE_BYTES = 128 * 1024;
export const MAX_IMAGE_BYTES = 3 * 1024 * 1024;
const IDLE_MS = 4000;
const TOTAL_MS = 6000;
const MAX_REDIRECTS = 3;

/** One guarded GET. Reads at most [maxBytes] of a 200 response whose type matches [accept]; anything else gives no body. */
function download(url: URL, accept: RegExp, acceptHeader: string, maxBytes: number): Promise<Download> {
  return new Promise<Download>((resolve, reject) => {
    // Node does not look up an address that is already an IP, so the connection guard below cannot see one: refuse it here.
    if (!parsePublicUrl(url.href)) return reject(new Error('address not allowed'));
    let done = false;
    const finish = (fn: () => void) => { if (!done) { done = true; clearTimeout(deadline); fn(); } };
    const lib = url.protocol === 'https:' ? https : http;
    const req = lib.request(url, {
      method: 'GET', lookup: guardedLookup, timeout: IDLE_MS,
      headers: { 'User-Agent': 'UniVibeLinkPreview/1.0', Accept: acceptHeader, 'Accept-Language': 'en' },
    }, (res) => {
      const status = res.statusCode ?? 0;
      const location = typeof res.headers.location === 'string' ? res.headers.location : undefined;
      const contentType = String(res.headers['content-type'] ?? '').toLowerCase();
      const empty = (truncated: boolean) => { res.destroy(); finish(() => resolve({ status, location, contentType, bytes: new Uint8Array(), truncated })); };
      if (status !== 200 || !accept.test(contentType)) return empty(false);
      if (Number(res.headers['content-length']) > maxBytes && maxBytes === MAX_IMAGE_BYTES) return empty(true);
      const chunks: Buffer[] = [];
      let size = 0;
      res.on('data', (c: Buffer) => { size += c.length; chunks.push(c); if (size >= maxBytes) res.destroy(); });
      const end = () => finish(() => resolve({ status, location, contentType, bytes: new Uint8Array(Buffer.concat(chunks).subarray(0, maxBytes)), truncated: size >= maxBytes }));
      res.on('end', end);
      res.on('close', end);
      res.on('error', end);
    });
    const deadline = setTimeout(() => req.destroy(new Error('took too long')), TOTAL_MS);
    req.on('timeout', () => req.destroy(new Error('no data')));
    req.on('error', (e) => finish(() => reject(e)));
    req.end();
  });
}

export const fetchPageSafely: PageFetcher = async (url) => {
  const r = await download(url, /^(text\/html|application\/xhtml\+xml)/, 'text/html,application/xhtml+xml', MAX_PAGE_BYTES);
  return { status: r.status, location: r.location, contentType: r.contentType, body: Buffer.from(r.bytes).toString('utf8') };
};

export const fetchImageSafely: Downloader = (url) =>
  download(url, /^(image\/|application\/octet-stream)/, 'image/webp,image/jpeg,image/png', MAX_IMAGE_BYTES);

/** Reads the preview for [raw], or null when there is nothing safe and useful to show. Never throws for ordinary failures. */
export async function fetchLinkPreview(raw: string, fetchPage: PageFetcher = fetchPageSafely): Promise<LinkPreviewData | null> {
  let url = parsePublicUrl(raw);
  if (!url) return null;
  const host = url.hostname.replace(/^www\./i, '');
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    let page: Page;
    try { page = await fetchPage(url); } catch { return null; }
    if (page.status >= 300 && page.status < 400 && page.location) {
      let next: URL;
      try { next = new URL(page.location, url); } catch { return null; }
      const checked = parsePublicUrl(next.href);
      if (!checked) return null;
      url = checked;
      continue;
    }
    if (page.status !== 200) return null;
    return parsePreview(page.body, host, url);
  }
  return null;
}

export type SniffedImage = 'image/jpeg' | 'image/png' | 'image/webp';

/** What a file really is, from its first bytes (a site's Content-Type label is not trusted). Only the three formats the server can process. */
export function sniffImageType(b: Uint8Array): SniffedImage | null {
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg';
  if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47 && b[4] === 0x0d && b[5] === 0x0a && b[6] === 0x1a && b[7] === 0x0a) return 'image/png';
  const ascii = (from: number, to: number) => String.fromCharCode(...b.subarray(from, to));
  if (b.length >= 12 && ascii(0, 4) === 'RIFF' && ascii(8, 12) === 'WEBP') return 'image/webp';
  return null;
}

/** Downloads the picture at [raw] (already an allowed address, but every redirect is checked again). Null unless it is a whole, real jpeg/png/webp. */
export async function fetchLinkImage(raw: string, fetchImage: Downloader = fetchImageSafely): Promise<{ bytes: Uint8Array; contentType: SniffedImage } | null> {
  let url = parsePublicUrl(raw);
  for (let hop = 0; url && hop <= MAX_REDIRECTS; hop++) {
    let got: Download;
    try { got = await fetchImage(url); } catch { return null; }
    if (got.status >= 300 && got.status < 400 && got.location) {
      let next: URL;
      try { next = new URL(got.location, url); } catch { return null; }
      url = parsePublicUrl(next.href);
      continue;
    }
    if (got.status !== 200 || got.truncated || got.bytes.length === 0) return null;
    const contentType = sniffImageType(got.bytes);
    return contentType ? { bytes: got.bytes, contentType } : null;
  }
  return null;
}

// ---- Reading the page ----

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };

function decodeEntities(s: string): string {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (whole, body: string) => {
    if (body[0] === '#') {
      const code = body[1].toLowerCase() === 'x' ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10);
      try { return String.fromCodePoint(code); } catch { return ''; }
    }
    return ENTITIES[body.toLowerCase()] ?? whole;
  });
}

function clean(s: string | undefined, max: number): string | null {
  if (!s) return null;
  // eslint-disable-next-line no-control-regex
  const text = decodeEntities(s).replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u202a-\u202e\u2066-\u2069]/g, ' ').replace(/\s+/g, ' ').trim();
  if (!text) return null;
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

const ATTR_RE = /([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))/g;

/** Title, description and site name from a page's HTML (Open Graph and Twitter tags first, then the plain ones). */
export function parsePreview(html: string, host: string, base?: URL): LinkPreviewData | null {
  const metas: Record<string, string> = {};
  // The length limits keep a hostile page (thousands of "<meta" with no ">") from costing real time.
  for (const tag of html.matchAll(/<meta\b[^>]{0,2000}>/gi)) {
    const attrs: Record<string, string> = {};
    for (const a of tag[0].matchAll(ATTR_RE)) attrs[a[1].toLowerCase()] = a[2] ?? a[3] ?? a[4] ?? '';
    const key = (attrs.property || attrs.name || '').toLowerCase();
    if (key && attrs.content !== undefined && !(key in metas)) metas[key] = attrs.content;
  }
  let titleTag: string | undefined;
  const open = html.search(/<title\b/i);
  if (open >= 0) {
    const from = html.indexOf('>', open);
    const to = from >= 0 ? html.toLowerCase().indexOf('</title>', from) : -1;
    if (from >= 0 && to > from) titleTag = html.slice(from + 1, to).slice(0, 1000);
  }
  const title = clean(metas['og:title'] ?? metas['twitter:title'] ?? titleTag, 120);
  const description = clean(metas['og:description'] ?? metas['twitter:description'] ?? metas.description, 200);
  const siteName = clean(metas['og:site_name'], 60);
  if (!title && !description) return null;
  // The picture: a full address, or one relative to the page; it must pass the same checks as any address the server fetches.
  const rawImage = metas['og:image:secure_url'] ?? metas['og:image'] ?? metas['og:image:url'] ?? metas['twitter:image'] ?? metas['twitter:image:src'];
  let imageUrl: string | null = null;
  if (rawImage && rawImage.length <= 2048) {
    try { imageUrl = parsePublicUrl(new URL(decodeEntities(rawImage).trim(), base).href)?.href ?? null; } catch { imageUrl = null; }
  }
  return { host, title, description, siteName, imageUrl };
}
