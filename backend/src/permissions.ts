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
  'overview.view',
  'analytics.view',
  'users.view',       // search people, open an account
  'users.suspend',    // suspend or lift a suspension from the dashboard
  'system.view',      // jobs, devices, uploads, server status
  'audit.view',
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
