import { describe, expect, it } from 'vitest';
import { BADGES, FRAMES, THEMES, effectiveLook, keysNeeded, validateLook, type Look } from '../src/modules/monetization/profile_look';

const L = (o: Partial<Look> = {}): Look => ({ theme: null, accent: null, frame: null, badge: null, ...o });
const T = 'premium.profile_themes';
const F = 'premium.profile_frames';

describe('validateLook', () => {
  it('accepts catalog values and upper-cases the accent', () => {
    const r = validateLook({ theme: 'aurora', accent: '#1a9e8f', frame: 'halo', badge: 'moon' });
    expect(r).toEqual({ ok: true, look: { theme: 'aurora', accent: '#1A9E8F', frame: 'halo', badge: 'moon' } });
  });
  it('an empty body clears everything', () => expect(validateLook({})).toEqual({ ok: true, look: L() }));
  it('refuses an unknown theme, frame or badge', () => {
    expect(validateLook({ theme: 'x' }).ok).toBe(false);
    expect(validateLook({ frame: 'gold' }).ok).toBe(false);
    expect(validateLook({ badge: 'verified' }).ok).toBe(false);
  });
  it('refuses bad colours and free text', () => {
    for (const a of ['red', '#12345', '#GGGGGG', 'javascript:1', '#1A9E8F; drop', 123]) expect(validateLook({ accent: a }).ok).toBe(false);
  });
  it('refuses unknown fields and non-objects', () => {
    expect(validateLook({ bio: 'hi' }).ok).toBe(false);
    expect(validateLook('x').ok).toBe(false);
    expect(validateLook([]).ok).toBe(false);
  });
  it('lists have the expected sizes', () => { expect(THEMES.length).toBe(6); expect(FRAMES.length).toBe(4); expect(BADGES.length).toBe(6); });
});

describe('keysNeeded', () => {
  it('needs nothing to clear', () => expect(keysNeeded(L())).toEqual([]));
  it('theme, accent and badge need profile_themes', () => {
    expect(keysNeeded(L({ theme: 'noir' }))).toEqual([T]);
    expect(keysNeeded(L({ accent: '#112233' }))).toEqual([T]);
    expect(keysNeeded(L({ badge: 'bolt' }))).toEqual([T]);
  });
  it('a frame needs profile_frames', () => expect(keysNeeded(L({ frame: 'ring' }))).toEqual([F]));
  it('both when both are used', () => expect(keysNeeded(L({ theme: 'gold', frame: 'dashed' }))).toEqual([T, F]));
});

describe('effectiveLook (what other students see)', () => {
  const saved = L({ theme: 'bloom', accent: '#AA3377', frame: 'double', badge: 'book' });
  it('nothing saved shows nothing', () => expect(effectiveLook(null, [T, F])).toBe(null));
  it('without Premium shows nothing, even though it stays saved', () => expect(effectiveLook(saved, [])).toBe(null));
  it('shows everything while both keys are live', () => expect(effectiveLook(saved, [T, F])).toEqual(saved));
  it('themes only drops the frame', () => expect(effectiveLook(saved, [T])).toEqual(L({ theme: 'bloom', accent: '#AA3377', badge: 'book' })));
  it('frames only drops the theme, accent and badge', () => expect(effectiveLook(saved, [F])).toEqual(L({ frame: 'double' })));
  it('an unrelated key shows nothing', () => expect(effectiveLook(saved, ['premium.chat_themes'])).toBe(null));
});
