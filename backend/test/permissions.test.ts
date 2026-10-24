import { describe, expect, it } from 'vitest';
import { CAPABILITIES, can, capabilitiesOf, isStaff } from '../src/permissions';

describe('staff capabilities', () => {
  it('administrators have every capability', () => {
    for (const c of CAPABILITIES) expect(can('admin', c)).toBe(true);
  });
  it('moderators can review reports and nothing else', () => {
    expect(capabilitiesOf('moderator')).toEqual(['reports.review']);
    expect(can('moderator', 'users.suspend')).toBe(false);
    expect(can('moderator', 'audit.view')).toBe(false);
  });
  it('students, unknown roles and missing roles have none', () => {
    for (const r of ['user', 'owner', 'ADMIN', '', 'constructor', '__proto__', null, undefined]) {
      expect(capabilitiesOf(r as string)).toEqual([]);
      expect(isStaff(r as string)).toBe(false);
    }
  });
});
