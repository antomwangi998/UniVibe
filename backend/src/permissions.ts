/**
 * What each staff role may do. This is the one place that says so: every guard on the server asks `can(role, cap)`,
 * and the dashboard learns its menu from the same list, so the two cannot drift apart. The API is the boundary;
 * hiding a link in the page is only courtesy.
 *
 * Roles live in users.role and are read from the database on every request, so a demotion takes effect at once.
 * To give a new kind of staff a narrower set, add a role to the migration check and a row here.
 */
export type Role = 'user' | 'moderator' | 'admin';

export const CAPABILITIES = [
  'reports.review',   // the reports queue and its actions
  'reports.assign',   // give a report to someone else, or take one over
  'overview.view',
  'analytics.view',
  'users.view',       // search people, open an account
  'users.suspend',    // suspend or lift a suspension from the dashboard
  'users.note',       // leave a private staff note on an account
  'communities.view',
  'communities.suspend',
  'content.view',     // search posts and comments
  'content.moderate', // remove or restore a post or comment without waiting for a report
  'events.view',
  'system.view',      // jobs, devices, uploads, server status
  'audit.view',
  // Monetization. Admin-only for now; the split lets a finance or ads role be added later with a row in GRANTS.
  'campaigns.review', // approve, reject, pause and cancel advertiser campaigns
  'advertisers.view', // advertiser and business directory, business review
  'packages.manage',  // packages, prices and the payment destination
  'payments.view',    // payment and refund records
  'payments.confirm', // confirm a manual payment against the M-Pesa statement
  'refunds.approve',  // approve or reject refunds and record the payout
  'finance.view',     // revenue, costs and financial reports
  'finance.export',   // CSV exports of financial data
] as const;
export type Capability = (typeof CAPABILITIES)[number];

const GRANTS: Record<Role, readonly Capability[]> = {
  user: [],
  moderator: ['reports.review'],
  admin: CAPABILITIES,
};

export const can = (role: string | null | undefined, cap: Capability): boolean =>
  !!role && Object.prototype.hasOwnProperty.call(GRANTS, role) && GRANTS[role as Role].includes(cap);

export const capabilitiesOf = (role: string | null | undefined): Capability[] => CAPABILITIES.filter((c) => can(role, c));

/** Staff can open the dashboard at all. */
export const isStaff = (role: string | null | undefined): boolean => capabilitiesOf(role).length > 0;
