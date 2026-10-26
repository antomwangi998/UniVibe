/** Money helpers. Amounts are integer minor units (KES cents). Nothing here uses floating point for stored values. */

export const CURRENCY = 'KES';

/** 150000 -> "KES 1,500.00". Display only. */
export function formatMoney(minor: number | bigint, currency = CURRENCY): string {
  const n = typeof minor === 'bigint' ? minor : BigInt(Math.trunc(minor));
  const neg = n < 0n;
  const abs = neg ? -n : n;
  const whole = (abs / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const cents = (abs % 100n).toString().padStart(2, '0');
  return `${neg ? '-' : ''}${currency} ${whole}.${cents}`;
}

/** "1500" or "1500.50" -> 150050. Rejects anything that is not a plain amount with at most two decimals. */
export function parseMajor(input: string): number | null {
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(input.trim())) return null;
  const [w, c = ''] = input.trim().split('.');
  return Number(w) * 100 + Number(c.padEnd(2, '0'));
}

/** M-Pesa receipt codes are 10 letters and digits. Accept 8-20 to survive format changes, upper-cased. */
export function normalizeReceipt(input: string): string | null {
  const v = input.trim().toUpperCase().replace(/\s+/g, '');
  return /^[A-Z0-9]{8,20}$/.test(v) ? v : null;
}

/** UV-XXXXXXXX with an alphabet that avoids look-alikes (no 0/O, 1/I). */
export function newOrderReference(rand: (n: number) => number): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let s = '';
  for (let i = 0; i < 8; i++) s += alphabet[rand(alphabet.length)];
  return `UV-${s}`;
}
