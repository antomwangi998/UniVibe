import { describe, expect, it } from 'vitest';
import { fetchLinkPreview, firstLink, isBlockedAddress, parsePreview, parsePublicUrl, type Page, type PageFetcher } from '../src/linkpreview';

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
    expect(parsePreview(html, 'univibe.app')).toEqual({ host: 'univibe.app', title: 'Campus & Co – Events', description: 'Join us "this" Friday', siteName: 'UniVibe' });
  });
  it('falls back to the title tag and the plain description', () => {
    const html = '<title>\n  Hello   world \n</title><meta name="description" content="A page">';
    expect(parsePreview(html, 'a.com')).toEqual({ host: 'a.com', title: 'Hello world', description: 'A page', siteName: null });
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
    expect(await fetchLinkPreview('https://www.example.com/x', fake({ 'https://www.example.com/x': html('Hi') }))).toEqual({ host: 'example.com', title: 'Hi', description: null, siteName: null });
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
