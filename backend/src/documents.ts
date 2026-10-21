/**
 * Documents people can share in a chat (a PDF of lecture notes, a Word file, a spreadsheet).
 *
 * The list is short on purpose and has no program or archive types: nothing that can run (apk, exe, scripts, jar), no
 * web pages or SVG (which can carry scripts), and no zip/rar (an app file is a zip underneath). Each file must also start
 * with the bytes its type is known by, so a wrong label is not enough to get a file in.
 */

export const DOCUMENT_MAX_BYTES = 25 * 1024 * 1024;

export const DOCUMENT_TYPES: Record<string, string> = {
  'application/pdf': 'pdf',
  'text/plain': 'txt',
  'text/csv': 'csv',
  'application/rtf': 'rtf',
  'application/msword': 'doc',
  'application/vnd.ms-excel': 'xls',
  'application/vnd.ms-powerpoint': 'ppt',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation': 'pptx',
  'application/vnd.oasis.opendocument.text': 'odt',
  'application/vnd.oasis.opendocument.spreadsheet': 'ods',
  'application/vnd.oasis.opendocument.presentation': 'odp',
};

const startsWith = (b: Uint8Array, sig: number[]) => b.length >= sig.length && sig.every((x, i) => b[i] === x);
const ZIP = [0x50, 0x4b, 0x03, 0x04];
const OLE = [0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1];

/** Does the start of the file look like [contentType] says? False for any type that is not a document type. */
export function documentLooksRight(contentType: string, b: Uint8Array): boolean {
  switch (contentType) {
    case 'application/pdf': return startsWith(b, [0x25, 0x50, 0x44, 0x46, 0x2d]); // %PDF-
    case 'application/rtf': return startsWith(b, [0x7b, 0x5c, 0x72, 0x74, 0x66]); // {\rtf
    case 'application/msword':
    case 'application/vnd.ms-excel':
    case 'application/vnd.ms-powerpoint': return startsWith(b, OLE);
    case 'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
    case 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':
    case 'application/vnd.openxmlformats-officedocument.presentationml.presentation':
    case 'application/vnd.oasis.opendocument.text':
    case 'application/vnd.oasis.opendocument.spreadsheet':
    case 'application/vnd.oasis.opendocument.presentation': return startsWith(b, ZIP);
    case 'text/plain':
    case 'text/csv':
      // Text: no control bytes (a binary file has them almost at once). Anything from 0x80 up may be part of UTF-8.
      return b.length > 0 && Array.from(b).every((x) => x === 0x09 || x === 0x0a || x === 0x0d || (x >= 0x20 && x !== 0x7f));
    default: return false;
  }
}

/**
 * A name that is safe to show and to save under: no folders, no control or direction-changing characters (which can
 * make "gpj.exe" read as "exe.jpg"), no characters Windows refuses, and always ending in the extension of its real type,
 * so "invoice.exe" labelled as a PDF is shown as "invoice.exe.pdf".
 */
export function cleanFileName(raw: string, contentType: string): string {
  const ext = DOCUMENT_TYPES[contentType] ?? 'bin';
  let name = (raw.split(/[\\/]/).pop() ?? '').normalize('NFC');
  // eslint-disable-next-line no-control-regex
  name = name.replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u202a-\u202e\u2066-\u2069\ufeff]/g, '')
    .replace(/[<>:"|?*]/g, '_').replace(/\s+/g, ' ').trim();
  const suffix = `.${ext}`;
  if (name.toLowerCase().endsWith(suffix)) name = name.slice(0, -suffix.length);
  // No hidden files (leading dots) and no trailing dots or spaces, which some systems drop or refuse.
  name = name.replace(/^[. ]+/, '').replace(/[. ]+$/, '');
  if (!name) name = 'document';
  // Keep the whole thing within 120 characters, never cutting the extension off.
  name = Array.from(name).slice(0, 120 - suffix.length).join('').trim() || 'document';
  return `${name}${suffix}`;
}
