import vm from 'node:vm';
import { beforeAll, describe, expect, it } from 'vitest';
import { ADMIN_JS } from '../src/admin_ui/script';

// Runs the real dashboard script against a small fake page and a fake API, then opens every Monetization page.
// It proves the script compiles, signs in, draws each page from the API's data, and sends the right request when a
// button is pressed. It does not test layout or styling: look at the page in a browser for that.
class El {
  constructor(tag: string) { this.tag = tag; this.children = []; this.attrs = {}; this.listeners = {}; this.className = ''; this.style = {}; this.value = ''; this.checked = false; this.disabled = false; this.hidden = false; this.text = ''; }
  appendChild(c) { this.children.push(c); return c; }
  removeChild(c) { this.children = this.children.filter((x) => x !== c); }
  get firstChild() { return this.children[0] || null; }
  set textContent(v) { this.children = []; this.text = String(v); }
  get textContent() { return this.text + this.children.map((c) => c.textContent).join(' '); }
  setAttribute(k, v) { this.attrs[k] = String(v); if (k === 'class') this.className = String(v); }
  getAttribute(k) { return this.attrs[k] ?? null; }
  removeAttribute(k) { delete this.attrs[k]; }
  addEventListener(t, f) { (this.listeners[t] ||= []).push(f); }
  get classList() { return { toggle() {}, add() {}, remove() {} }; }
  querySelectorAll() { return []; } querySelector() { return null; } focus() {}
  click() { (this.listeners.click || []).forEach((f) => f({ preventDefault() {} })); }
}
const walk = (e, fn) => { fn(e); (e.children || []).forEach((c) => walk(c, fn)); };
const root = new El('div');
const doc = { getElementById: () => root, createElement: (t) => new El(t), createElementNS: (_n, t) => new El(t), createTextNode: (s) => { const e = new El('#text'); e.text = String(s); return e; }, addEventListener() {}, title: '' };
const hash = { v: '' };
const winL = {};
const win = { addEventListener: (t, f) => { (winL[t] ||= []).push(f); }, confirm: () => true, scrollTo() {}, location: { get hash() { return hash.v; }, set hash(x) { hash.v = x; } } };
const calls = [];
const CAPS = ['overview.view', 'analytics.view', 'system.view', 'reports.review', 'users.view', 'communities.view', 'content.view', 'events.view', 'audit.view', 'campaigns.review', 'advertisers.view', 'packages.manage', 'payments.view', 'payments.confirm', 'refunds.approve', 'finance.view', 'finance.export'];
const ID = '11111111-1111-4111-8111-111111111111';
const camp = { id: ID, kind: 'event_promotion', headline: 'Hack night', status: 'pending_review', review_status: 'pending', starts_at: '2026-11-01T00:00:00Z', ends_at: '2026-11-08T00:00:00Z', delivered_impressions: '10', delivered_clicks: '2', order_status: 'paid', amount_minor: undefined, amount: 'KES 500.00', advertiser_handle: 'amina' };
const routes = {
  '/auth/refresh': { accessToken: 'a', refreshToken: 'r' }, '/me': { fullName: 'Owner' },
  '/moderation/access': { capabilities: CAPS, role: 'admin', id: 'u1', universityId: 'uni' },
  '/admin/overview': { reports: { open: 0 }, totals: {}, days: { signups: [], posts: [], messages: [], events: [], reports: [] } },
  '/admin/monetization/overview': { currency: 'KES', payments: { receivedMinor: 150050, refundedMinor: 5000, netCollectedMinor: 145050, awaitingConfirmation: 2, failedLast30Days: 1 }, refunds: { open: 1, openMinor: 5000, paidOutMinor: 5000 }, revenue: { recognisedMinor: 70000, deferredMinor: 80050, paidOrdersMinor: 150050, byKind: [{ kind: 'event_promotion', paidMinor: 150050, recognisedMinor: 70000 }] }, campaigns: { activePaid: 3, pendingReview: 2, withOpenAdReports: 0 }, byMonth: [{ month: '2026-11', receivedMinor: 150050, refundedMinor: 5000 }], costs: null, rules: { received: 'r', costs: 'Not tracked yet.' } },
  '/admin/monetization/campaigns': { items: [camp], nextCursor: null },
  ['/admin/monetization/campaigns/' + ID]: { campaign: { ...camp, review_note: '' }, history: [{ to_status: 'draft', actor_kind: 'advertiser', note: '', created_at: '2026-10-30T00:00:00Z', handle: 'amina' }] },
  ['/admin/monetization/campaigns/' + ID + '/analytics']: { campaign: { status: 'active' }, range: { from: '2026-11-01', to: '2026-11-03' }, totals: { impressions: 10, clicks: 2, ctr: 0.2, reach: 8 }, conversions: { kind: 'rsvp_after_click', count: 1 }, daily: [{ day: '2026-11-01', impressions: 3 }, { day: '2026-11-02', impressions: 7 }], delivery: { running: true, reasonsNotRunning: [], heldForReview: false }, payment: { status: 'paid' }, definitions: { impression: 'x' }, integrity: { flags: [{ code: 'x', message: 'One student accounts for over a fifth of all clicks.' }], note: 'Signals only.' } },
  '/admin/monetization/payments': { items: [{ id: 'p1', status: 'awaiting_verification', receipt: 'QGH7ABC123', amountMinor: 50000, amount: 'KES 500.00', receivedMinor: null, reconciliation: 'unreconciled', orderReference: 'UV-ABCD2345', advertiser: 'amina', campaign: 'Hack night', submittedAt: '2026-11-02T00:00:00Z' }], nextCursor: null },
  '/admin/monetization/refunds': { items: [{ id: 'r1', status: 'approved', kind: 'overpayment', amount: 'KES 50.00', order_reference: 'UV-ABCD2345', provider_reference: 'QGH7ABC123', reason: 'Overpayment', created_at: '2026-11-02T00:00:00Z' }], nextCursor: null },
  '/admin/monetization/packages': { items: [{ id: 'k1', code: 'event-week', kind: 'event_promotion', name: 'Event boost', description: '', priceMinor: 50000, durationDays: 7, placements: ['home_feed'], impressionCap: null, frequencyCapPerDay: 3, active: true }] },
  '/admin/monetization/payment-settings': { enabled: false, method: 'till', number: '', accountName: '', instructions: '' },
  '/admin/monetization/businesses': { items: [{ id: 'b1', name: 'Mama Pizza', category: 'food', status: 'pending_review', verification: 'unverified', review_note: '', created_at: '2026-11-01T00:00:00Z', owner_handle: 'amina', campaigns: '2' }], nextCursor: null },
  '/admin/monetization/ad-reports': { items: [{ campaign_id: ID, headline: 'Hack night', status: 'active', advertiser: 'amina', open_reports: 3, reasons: ['scam'], oldest: '2026-11-01T00:00:00Z' }] },
};
const fetchLog = [];
const fake = async (url, opts = {}) => {
  const path = String(url).replace('/api/v1', '').split('?')[0];
  fetchLog.push((opts.method || 'GET') + ' ' + String(url) + (opts.body ? ' ' + opts.body : ''));
  const data = routes[path];
  return { ok: !!data, status: data ? 200 : 404, json: async () => data ?? { message: 'no route ' + path } };
};
const ctx = vm.createContext({ document: doc, window: win, location: win.location, history: { replaceState() {} }, sessionStorage: { getItem: () => 'rt', setItem() {}, removeItem() {} }, fetch: fake, setTimeout, clearTimeout, setInterval, clearInterval, console, Promise, Object, Array, Math, Date, Number, String, JSON, encodeURIComponent, decodeURIComponent, Error });
vm.runInContext(ADMIN_JS, ctx);

const tick = () => new Promise((r) => setTimeout(r, 20));
const text = () => { let t = ''; walk(root, (e) => { if (e.text) t += e.text + ' '; }); return t; };
const buttons = () => { const b: El[] = []; walk(root, (e) => { if (e.tag === 'button') b.push(e); }); return b; };
const go = async (h: string) => { hash.v = h; (winL.hashchange || []).forEach((f: () => void) => f()); for (let i = 0; i < 6; i++) await tick(); return text(); };

describe('admin Monetization pages', () => {
  beforeAll(async () => {
    const ctx = vm.createContext({ document: doc, window: win, location: win.location, history: { replaceState() {} }, sessionStorage: { getItem: () => 'rt', setItem() {}, removeItem() {} }, fetch: fake, setTimeout, clearTimeout, setInterval, clearInterval, console });
    vm.runInContext(ADMIN_JS, ctx);
    for (let i = 0; i < 6; i++) await tick();
  });

  it('adds a Monetization group to the navigation for staff who may use it', () => { expect(text().includes('Monetization')).toBe(true); });
  it('shows revenue with the rules and no invented costs', async () => {
    const t = await go('#/revenue');
    expect(t.includes('KES 1,500.50') && t.includes('Not tracked yet') && t.includes('Recognised')).toBe(true);
  });
  it('lists campaigns with review actions', async () => {
    const t = await go('#/campaigns');
    expect(t.includes('Hack night') && t.includes('amina')).toBe(true);
    expect(buttons().some((b) => b.textContent.includes('Approve'))).toBe(true);
  });
  it('shows campaign performance, CTR and engagement checks', async () => {
    const t = await go('#/campaigns/' + ID);
    expect(t.includes('Impressions') && t.includes('20.00%') && t.includes('One student accounts')).toBe(true);
  });
  it('confirms a payment by sending the amount in cents, never a price from the page', async () => {
    const t = await go('#/payments');
    expect(t.includes('QGH7ABC123') && t.includes('UV-ABCD2345')).toBe(true);
    const before = fetchLog.length;
    buttons().find((b) => b.textContent === 'Confirm payment')!.click();
    await tick(); await tick();
    const sent = fetchLog.slice(before).find((l) => l.includes('/payments/p1/confirm'));
    expect(sent).toBeTruthy();
    expect(sent!.includes('"receivedMinor":50000')).toBe(true);
  });
  it('shows refunds waiting for a payout reference', async () => {
    const t = await go('#/refunds?status=approved');
    expect(t.includes('excess handed back')).toBe(true);
    expect(buttons().some((b) => b.textContent.includes('Record payout'))).toBe(true);
  });
  it('shows the payment settings and packages', async () => {
    const t = await go('#/packages');
    expect(t.includes('Where advertisers pay') && t.includes('Event boost') && t.includes('KES 500.00') && t.includes('New package')).toBe(true);
  });
  it('shows business listings and reported ads', async () => {
    const t = await go('#/advertisers');
    expect(t.includes('Mama Pizza') && t.includes('Reported ads') && t.includes('3 open reports')).toBe(true);
  });
});
