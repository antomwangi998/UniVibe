import { describe, expect, it } from 'vitest';
import { type Download, type Downloader, fetchLinkImage, fetchLinkPreview, firstLink, isBlockedAddress, parsePreview, parsePublicUrl, sniffImageType, type Page, type PageFetcher } from '../src/linkpreview';

describe('firstLink (must agree with the app)', () => {
  it('finds the first address and adds https to a bare www. one', () => {
    expect(firstLink('see https://univibe.app/events and https://b.com')).toBe('https://univibe.app/events');
    expect(firstLink('try www.example.com now')).toBe('https://www.example.com');
  });
  it('leaves sentence punctuation out but keeps balanced brackets', () => {
    expect(firstLink('go to https://a.com/x.')).toBe('https://a.com/x');
    expect(firstLink('(https://a.com/x)')).toBe('https://a.com/x');
    expect(firstLink('https://en.wikipedia.org/wiki/Kotlin_(language)')).toBe('https://en.wikipedia.org/wiki/Kotlin_(language)');
  });
  it('ignores other schemes, file names and empty addresses', () => {
    expect(firstLink('javascript:alert(1) file:///etc/passwd readme.md')).toBeNull();
    expect(firstLink('https:// and www. alone')).toBeNull();
    expect(firstLink(null)).toBeNull();
    expect(firstLink('')).toBeNull();
  });
  it('skips a link that is too long to keep', () => {
    expect(firstLink(`https://a.com/${'x'.repeat(2100)}`)).toBeNull();
  });
});

describe('isBlockedAddress', () => {
  it('blocks private, loopback, link-local and cloud-metadata addresses', () => {
    for (const ip of ['127.0.0.1', '10.1.2.3', '192.168.0.1', '172.16.0.1', '172.31.255.255', '169.254.169.254', '100.64.0.1', '0.0.0.0', '224.0.0.1', '255.255.255.255']) {
      expect(isBlockedAddress(ip)).toBe(true);
    }
    for (const ip of ['::1', '::', 'fe80::1', 'fc00::1', 'fd12:3456::1', '::ffff:127.0.0.1', '::ffff:7f00:1', '::ffff:10.0.0.1', '64:ff9b::7f00:1']) {
      expect(isBlockedAddress(ip)).toBe(true);
    }
  });
  it('allows ordinary public addresses', () => {
    for (const ip of ['8.8.8.8', '1.1.1.1', '172.32.0.1', '172.15.255.255', '2606:4700:4700::1111', '2a00:1450:4001::200e']) {
      expect(isBlockedAddress(ip)).toBe(false);
    }
  });
  it('treats anything that is not an address as blocked', () => {
    expect(isBlockedAddress('example.com')).toBe(true);
    expect(isBlockedAddress('')).toBe(true);
  });
});

describe('parsePublicUrl', () => {
  it('accepts an ordinary public address', () => {
    expect(parsePublicUrl('https://example.com/a?b=1')?.hostname).toBe('example.com');
    expect(parsePublicUrl('http://www.example.co.ke:80/')?.hostname).toBe('www.example.co.ke');
  });
  it('rejects other schemes, passwords and unusual ports', () => {
    for (const u of ['ftp://example.com/', 'file:///etc/passwd', 'javascript:alert(1)', 'https://user:pw@example.com/', 'https://example.com:8080/', 'http://example.com:22/']) {
      expect(parsePublicUrl(u)).toBeNull();
    }
  });
  it('rejects IP addresses in every spelling, and internal-looking names', () => {
    for (const u of ['http://127.0.0.1/', 'http://2130706433/', 'http://0x7f.1/', 'http://017700000001/', 'http://[::1]/', 'http://[::ffff:127.0.0.1]/', 'http://8.8.8.8/',
      'http://localhost/', 'http://intranet/', 'http://printer.local/', 'http://db.internal/', 'http://foo.localhost/']) {
      expect(parsePublicUrl(u)).toBeNull();
    }
  });
});

describe('parsePreview', () => {
  it('prefers Open Graph tags and decodes entities', () => {
    const html = `<html><head><title>Plain title</title>
      <meta property="og:title" content="Campus &amp; Co &#8211; Events">
      <meta property='og:description' content='Join us &quot;this&quot; Friday'>
      <meta property="og:site_name" content="UniVibe"></head></html>`;
    expect(parsePreview(html, 'univibe.app')).toEqual({ host: 'univibe.app', title: 'Campus & Co – Events', description: 'Join us "this" Friday', siteName: 'UniVibe', imageUrl: null });
  });
  it('falls back to the title tag and the plain description', () => {
    const html = '<title>\n  Hello   world \n</title><meta name="description" content="A page">';
    expect(parsePreview(html, 'a.com')).toEqual({ host: 'a.com', title: 'Hello world', description: 'A page', siteName: null, imageUrl: null });
  });
  it('gives nothing when the page has no title or description', () => {
    expect(parsePreview('<html><body>hi</body></html>', 'a.com')).toBeNull();
    expect(parsePreview('<title>   </title>', 'a.com')).toBeNull();
  });
  it('shortens long text and removes control characters', () => {
    const p = parsePreview(`<title>${'a'.repeat(300)}\u0000\u202e</title>`, 'a.com');
    expect(p?.title?.length).toBe(120);
    expect(p?.title?.endsWith('…')).toBe(true);
  });
  it('does not take long on hostile input', () => {
    const started = Date.now();
    parsePreview('<meta'.repeat(25_000), 'a.com');
    parsePreview('<title'.repeat(25_000), 'a.com');
    expect(Date.now() - started < 3000).toBe(true);
  });
});

describe('fetchLinkPreview', () => {
  const html = (title: string): Page => ({ status: 200, contentType: 'text/html', body: `<title>${title}</title>` });
  const fake = (pages: Record<string, Page>, seen: string[] = []): PageFetcher => async (url) => {
    seen.push(url.href);
    const p = pages[url.href];
    if (!p) throw new Error('no such page');
    return p;
  };

  it('reads a page', async () => {
    expect(await fetchLinkPreview('https://www.example.com/x', fake({ 'https://www.example.com/x': html('Hi') }))).toEqual({ host: 'example.com', title: 'Hi', description: null, siteName: null, imageUrl: null });
  });
  it('follows a redirect, checking each step', async () => {
    const seen: string[] = [];
    const pages = { 'https://a.com/': { status: 301, location: '/b', contentType: '', body: '' }, 'https://a.com/b': html('B') };
    expect((await fetchLinkPreview('https://a.com/', fake(pages, seen)))?.title).toBe('B');
    expect(seen).toEqual(['https://a.com/', 'https://a.com/b']);
  });
  it('refuses a redirect into the private network', async () => {
    for (const target of ['http://127.0.0.1/admin', 'http://169.254.169.254/latest/meta-data/', 'http://localhost:8080/', 'file:///etc/passwd', 'http://[::1]/']) {
      const seen: string[] = [];
      const pages = { 'https://a.com/': { status: 302, location: target, contentType: '', body: '' } };
      expect(await fetchLinkPreview('https://a.com/', fake(pages, seen))).toBeNull();
      expect(seen).toEqual(['https://a.com/']);
    }
  });
  it('gives up after three redirects', async () => {
    const loop: PageFetcher = async (url) => ({ status: 302, location: `${url.href}x`, contentType: '', body: '' });
    expect(await fetchLinkPreview('https://a.com/', loop)).toBeNull();
  });
  it('gives nothing for errors, other statuses and failed fetches', async () => {
    expect(await fetchLinkPreview('https://a.com/', fake({ 'https://a.com/': { status: 404, contentType: 'text/html', body: '<title>x</title>' } }))).toBeNull();
    expect(await fetchLinkPreview('https://a.com/', fake({}))).toBeNull();
  });
  it('never fetches an address that is not allowed', async () => {
    const seen: string[] = [];
    expect(await fetchLinkPreview('http://localhost/', fake({}, seen))).toBeNull();
    expect(await fetchLinkPreview('http://10.0.0.5/', fake({}, seen))).toBeNull();
    expect(seen).toEqual([]);
  });
});

describe('the picture on a link card', () => {
  const page = (img: string) => `<title>T</title><meta property="og:image" content="${img}">`;
  const base = new URL('https://news.example.com/a/b');

  it('takes the Open Graph picture, resolved against the page when it is relative', () => {
    expect(parsePreview(page('https://cdn.example.com/p.jpg'), 'h', base)?.imageUrl).toBe('https://cdn.example.com/p.jpg');
    expect(parsePreview(page('/img/p.png'), 'h', base)?.imageUrl).toBe('https://news.example.com/img/p.png');
    expect(parsePreview(page('//cdn.example.com/p.png?a=1&amp;b=2'), 'h', base)?.imageUrl).toBe('https://cdn.example.com/p.png?a=1&b=2');
  });
  it('falls back to the Twitter card picture', () => {
    expect(parsePreview('<title>T</title><meta name="twitter:image" content="https://c.example.com/t.jpg">', 'h', base)?.imageUrl).toBe('https://c.example.com/t.jpg');
  });
  it('drops a picture the server may not fetch', () => {
    for (const img of ['http://127.0.0.1/x.png', 'http://169.254.169.254/latest', 'http://localhost/x.png', 'data:image/png;base64,AAAA', 'javascript:alert(1)', 'file:///etc/passwd', 'https://cdn.example.com:8443/p.jpg', 'https://u:p@cdn.example.com/p.jpg']) {
      expect(parsePreview(page(img), 'h', base)?.imageUrl).toBeNull();
    }
  });
  it('has no picture when the page names none, and a relative one without a page to resolve it against is dropped', () => {
    expect(parsePreview('<title>T</title>', 'h', base)?.imageUrl).toBeNull();
    expect(parsePreview(page('/img/p.png'), 'h')?.imageUrl).toBeNull();
  });
});

describe('sniffImageType', () => {
  const bytes = (...n: number[]) => new Uint8Array(n);
  it('recognises jpeg, png and webp by their first bytes', () => {
    expect(sniffImageType(bytes(0xff, 0xd8, 0xff, 0xe0, 0, 0))).toBe('image/jpeg');
    expect(sniffImageType(bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0))).toBe('image/png');
    expect(sniffImageType(new TextEncoder().encode('RIFF\u0000\u0000\u0000\u0000WEBPVP8 '))).toBe('image/webp');
  });
  it('rejects everything else, whatever a site calls it', () => {
    expect(sniffImageType(new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg"></svg>'))).toBeNull();
    expect(sniffImageType(new TextEncoder().encode('GIF89a......'))).toBeNull();
    expect(sniffImageType(new TextEncoder().encode('<html><title>x</title></html>'))).toBeNull();
    expect(sniffImageType(new Uint8Array())).toBeNull();
  });
});

describe('fetchLinkImage', () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);
  const got = (over: Partial<Download> = {}): Download => ({ status: 200, contentType: 'image/jpeg', bytes: jpeg, truncated: false, ...over });
  const fake = (pages: Record<string, Download>, seen: string[] = []): Downloader => async (url) => {
    seen.push(url.href);
    const p = pages[url.href];
    if (!p) throw new Error('no such file');
    return p;
  };

  it('returns a whole, real image with the type found from its bytes', async () => {
    const r = await fetchLinkImage('https://c.example.com/p.jpg', fake({ 'https://c.example.com/p.jpg': got({ contentType: 'text/plain' }) }));
    expect(r?.contentType).toBe('image/jpeg');
    expect(r?.bytes.length).toBe(7);
  });
  it('refuses a file that is not an image, however it is labelled', async () => {
    const html = new TextEncoder().encode('<html>not an image</html>');
    expect(await fetchLinkImage('https://c.example.com/p.jpg', fake({ 'https://c.example.com/p.jpg': got({ bytes: html }) }))).toBeNull();
  });
  it('refuses a file that was too big, or empty, or not found', async () => {
    expect(await fetchLinkImage('https://c.example.com/p.jpg', fake({ 'https://c.example.com/p.jpg': got({ truncated: true }) }))).toBeNull();
    expect(await fetchLinkImage('https://c.example.com/p.jpg', fake({ 'https://c.example.com/p.jpg': got({ bytes: new Uint8Array() }) }))).toBeNull();
    expect(await fetchLinkImage('https://c.example.com/p.jpg', fake({ 'https://c.example.com/p.jpg': got({ status: 404 }) }))).toBeNull();
    expect(await fetchLinkImage('https://c.example.com/p.jpg', fake({}))).toBeNull();
  });
  it('follows a redirect but never into the private network', async () => {
    const seen: string[] = [];
    const ok = { 'https://c.example.com/a': got({ status: 302, location: 'https://c.example.com/b', bytes: new Uint8Array() }), 'https://c.example.com/b': got() };
    expect((await fetchLinkImage('https://c.example.com/a', fake(ok, seen)))?.contentType).toBe('image/jpeg');
    expect(seen).toEqual(['https://c.example.com/a', 'https://c.example.com/b']);
    for (const target of ['http://127.0.0.1/x', 'http://169.254.169.254/x', 'http://[::1]/x', 'http://localhost/x']) {
      const s2: string[] = [];
      const bad = { 'https://c.example.com/a': got({ status: 302, location: target, bytes: new Uint8Array() }) };
      expect(await fetchLinkImage('https://c.example.com/a', fake(bad, s2))).toBeNull();
      expect(s2).toEqual(['https://c.example.com/a']);
    }
  });
  it('never fetches an address that is not allowed in the first place', async () => {
    const seen: string[] = [];
    expect(await fetchLinkImage('http://127.0.0.1/p.jpg', fake({}, seen))).toBeNull();
    expect(seen).toEqual([]);
  });
});
