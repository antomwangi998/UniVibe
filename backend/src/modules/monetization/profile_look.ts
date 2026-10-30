/**
 * Premium profile looks. Pure rules (no database, no HTTP) so every one is testable alone.
 * Theme, accent and badge need `premium.profile_themes`; the frame needs `premium.profile_frames`.
 */
export const THEMES = ['aurora', 'ember', 'tide', 'noir', 'bloom', 'gold'] as const;
export const FRAMES = ['ring', 'double', 'dashed', 'halo'] as const;
export const BADGES = ['spark', 'book', 'moon', 'coffee', 'flame', 'bolt'] as const;

export interface Look { theme: string | null; accent: string | null; frame: string | null; badge: string | null }
export const EMPTY_LOOK: Look = { theme: null, accent: null, frame: null, badge: null };

const KEY_THEMES = 'premium.profile_themes';
const KEY_FRAMES = 'premium.profile_frames';

const oneOf = (list: readonly string[], v: unknown): string | null | undefined =>
  v === null || v === undefined ? null : typeof v === 'string' && list.includes(v) ? v : undefined;

/** Checks a request body. Anything not on the fixed lists is refused; a hex colour is normalised to #RRGGBB upper case. */
export function validateLook(input: unknown): { ok: true; look: Look } | { ok: false; error: string } {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return { ok: false, error: 'Send an object.' };
  const o = input as Record<string, unknown>;
  const extra = Object.keys(o).filter((k) => !['theme', 'accent', 'frame', 'badge'].includes(k));
  if (extra.length) return { ok: false, error: `Unknown field: ${extra[0].slice(0, 20)}.` };
  const theme = oneOf(THEMES, o.theme);
  const frame = oneOf(FRAMES, o.frame);
  const badge = oneOf(BADGES, o.badge);
  if (theme === undefined) return { ok: false, error: 'That theme does not exist.' };
  if (frame === undefined) return { ok: false, error: 'That frame does not exist.' };
  if (badge === undefined) return { ok: false, error: 'That badge does not exist.' };
  let accent: string | null = null;
  if (o.accent !== null && o.accent !== undefined) {
    if (typeof o.accent !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(o.accent)) return { ok: false, error: 'Accent must look like #1A9E8F.' };
    accent = o.accent.toUpperCase();
  }
  return { ok: true, look: { theme, accent, frame, badge } };
}

/** Entitlement keys a look needs. Clearing a field never needs one. */
export function keysNeeded(look: Look): string[] {
  const out: string[] = [];
  if (look.theme || look.accent || look.badge) out.push(KEY_THEMES);
  if (look.frame) out.push(KEY_FRAMES);
  return out;
}

/** What other people may see: only the parts whose entitlement is live right now. Null when nothing is left. */
export function effectiveLook(saved: Look | null, keys: readonly string[]): Look | null {
  if (!saved) return null;
  const themes = keys.includes(KEY_THEMES);
  const frames = keys.includes(KEY_FRAMES);
  const look: Look = {
    theme: themes ? saved.theme : null, accent: themes ? saved.accent : null, badge: themes ? saved.badge : null,
    frame: frames ? saved.frame : null,
  };
  return look.theme || look.accent || look.badge || look.frame ? look : null;
}
