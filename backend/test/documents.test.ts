import { describe, expect, it } from 'vitest';
import { DOCUMENT_TYPES, cleanFileName, documentLooksRight } from '../src/documents';
import { MEDIA_LIMITS, looksLike } from '../src/media';

const bytes = (s: string) => new TextEncoder().encode(s);
const raw = (...n: number[]) => new Uint8Array(n);
const PDF = 'application/pdf';
const DOCX = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';

describe('what may be shared as a document', () => {
  it('includes ordinary study files and nothing that can run', () => {
    const exts = Object.values(DOCUMENT_TYPES);
    for (const e of ['pdf', 'docx', 'xlsx', 'pptx', 'txt', 'csv']) expect(exts).toContain(e);
    for (const e of ['apk', 'exe', 'sh', 'bat', 'jar', 'zip', 'rar', 'html', 'svg', 'js']) expect(exts.includes(e)).toBe(false);
    for (const t of ['application/vnd.android.package-archive', 'application/zip', 'text/html', 'image/svg+xml', 'application/x-msdownload', 'application/octet-stream']) {
      expect(t in DOCUMENT_TYPES).toBe(false);
    }
  });
  it('is part of the upload limits', () => {
    expect(MEDIA_LIMITS.document.types).toContain(PDF);
    expect(MEDIA_LIMITS.document.maxBytes).toBe(25 * 1024 * 1024);
  });
});

describe('documentLooksRight', () => {
  it('accepts files that start the way their type does', () => {
    expect(documentLooksRight(PDF, bytes('%PDF-1.7\n...'))).toBe(true);
    expect(documentLooksRight(DOCX, raw(0x50, 0x4b, 0x03, 0x04, 0x14, 0))).toBe(true);
    expect(documentLooksRight('application/msword', raw(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1))).toBe(true);
    expect(documentLooksRight('application/rtf', bytes('{\\rtf1\\ansi'))).toBe(true);
    expect(documentLooksRight('text/plain', bytes('Lecture notes\nweek 1 — intro é'))).toBe(true);
    expect(documentLooksRight('text/csv', bytes('name,score\r\nA,1\r\n'))).toBe(true);
  });
  it('refuses a wrong label', () => {
    expect(documentLooksRight(PDF, raw(0x50, 0x4b, 0x03, 0x04))).toBe(false); // a zip (or an app) called a PDF
    expect(documentLooksRight(DOCX, bytes('%PDF-1.7'))).toBe(false);
    expect(documentLooksRight(PDF, raw(0x4d, 0x5a, 0x90, 0))).toBe(false);      // a Windows program
    expect(documentLooksRight('text/plain', raw(0x7f, 0x45, 0x4c, 0x46, 0))).toBe(false); // a Linux program
    expect(documentLooksRight('text/plain', raw(0x41, 0x00, 0x42))).toBe(false);
    expect(documentLooksRight('text/plain', new Uint8Array())).toBe(false);
  });
  it('is false for every type that is not a document', () => {
    expect(documentLooksRight('application/vnd.android.package-archive', raw(0x50, 0x4b, 0x03, 0x04))).toBe(false);
    expect(documentLooksRight('image/png', raw(0x89, 0x50, 0x4e, 0x47))).toBe(false);
  });
  it('is what looksLike uses for documents, and photos still work', () => {
    expect(looksLike(PDF, bytes('%PDF-1.4'))).toBe(true);
    expect(looksLike(PDF, bytes('hello'))).toBe(false);
    expect(looksLike('application/vnd.android.package-archive', raw(0x50, 0x4b, 0x03, 0x04))).toBe(false);
    expect(looksLike('image/jpeg', raw(0xff, 0xd8, 0xff, 0xe0))).toBe(true);
  });
});

describe('cleanFileName', () => {
  it('keeps an ordinary name', () => {
    expect(cleanFileName('Lecture 3 notes.pdf', PDF)).toBe('Lecture 3 notes.pdf');
    expect(cleanFileName('Report.PDF', PDF)).toBe('Report.pdf');
    expect(cleanFileName('Résumé – final.docx', DOCX)).toBe('Résumé – final.docx');
  });
  it('removes folders and characters that cause trouble', () => {
    expect(cleanFileName('../../etc/passwd.pdf', PDF)).toBe('passwd.pdf');
    expect(cleanFileName('C:\\Users\\me\\notes.pdf', PDF)).toBe('notes.pdf');
    expect(cleanFileName('a<b>:"c|d?e*.pdf', PDF)).toBe('a_b___c_d_e_.pdf');
    expect(cleanFileName('...hidden.pdf', PDF)).toBe('hidden.pdf');
    expect(cleanFileName('line\nbreak\u0000.pdf', PDF)).toBe('linebreak.pdf');
  });
  it('cannot be made to read as a different extension', () => {
    // U+202E reverses what follows: "invoice\u202Efdp.exe" would show as "invoiceexe.pdf".
    const name = cleanFileName('invoice\u202Efdp.exe', PDF);
    expect(name.includes('\u202e')).toBe(false);
    expect(name.endsWith('.pdf')).toBe(true);
    expect(cleanFileName('invoice.exe', PDF)).toBe('invoice.exe.pdf');
    expect(cleanFileName('payload.apk', DOCX)).toBe('payload.apk.docx');
  });
  it('always has a name and a real extension, and stays short', () => {
    expect(cleanFileName('', PDF)).toBe('document.pdf');
    expect(cleanFileName('   ', PDF)).toBe('document.pdf');
    expect(cleanFileName('.pdf', PDF)).toBe('document.pdf');
    const long = cleanFileName(`${'x'.repeat(500)}.pdf`, PDF);
    expect(long.length).toBe(120);
    expect(long.endsWith('.pdf')).toBe(true);
  });
});
