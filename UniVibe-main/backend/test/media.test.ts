import { describe, expect, it } from 'vitest';
import { looksLike } from '../src/media';

const bytes = (...n: number[]) => Uint8Array.from(n);
const text = (s: string) => Uint8Array.from(Array.from(s).map((c) => c.charCodeAt(0)));

describe('looksLike (what a file really is, not what it says it is)', () => {
  it('accepts real headers', () => {
    expect(looksLike('image/jpeg', bytes(0xff, 0xd8, 0xff, 0xe0, 0, 0))).toBe(true);
    expect(looksLike('image/png', bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))).toBe(true);
    expect(looksLike('image/webp', Uint8Array.from([...text('RIFF'), 1, 2, 3, 4, ...text('WEBP')]))).toBe(true);
    expect(looksLike('video/mp4', Uint8Array.from([0, 0, 0, 0x20, ...text('ftypisom')]))).toBe(true);
    expect(looksLike('video/webm', bytes(0x1a, 0x45, 0xdf, 0xa3, 0))).toBe(true);
  });

  it('rejects labels that do not match the content', () => {
    expect(looksLike('image/jpeg', text('<html><script>alert(1)</script>'))).toBe(false);
    expect(looksLike('image/png', bytes(0xff, 0xd8, 0xff, 0xe0))).toBe(false);
    expect(looksLike('video/mp4', bytes(0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0))).toBe(false);
    expect(looksLike('image/jpeg', new Uint8Array())).toBe(false);
    // A PDF is a real document type now, so only a mislabelled one is rejected.
    expect(looksLike('application/pdf', text('<html>'))).toBe(false);
    expect(looksLike('image/jpeg', text('%PDF-1.7'))).toBe(false);
  });
});

describe('looksLike for voice messages', () => {
  it('accepts real audio headers and rejects a mislabelled file', () => {
    expect(looksLike('audio/mp4', Uint8Array.from([0, 0, 0, 0x1c, ...text('ftypM4A ')]))).toBe(true);
    expect(looksLike('audio/aac', bytes(0xff, 0xf1, 0x50, 0x80))).toBe(true);
    expect(looksLike('audio/ogg', text('OggS\0\u0002'))).toBe(true);
    expect(looksLike('audio/webm', bytes(0x1a, 0x45, 0xdf, 0xa3))).toBe(true);
    expect(looksLike('audio/mp4', bytes(0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0))).toBe(false); // a JPEG called audio
    expect(looksLike('audio/mpeg', bytes(0xff, 0xfb, 0, 0))).toBe(false); // not on the allowed list
  });
});
