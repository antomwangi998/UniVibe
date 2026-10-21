import { describe, expect, it } from 'vitest';
import { groupInOrder } from '../src/group';

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
type Row = { message_id: string; position: number };

describe('groupInOrder (attachments of a message)', () => {
  it('keeps all four photos of a message, in order, even when their links finish in the opposite order', async () => {
    const rows: Row[] = [0, 1, 2, 3].map((position) => ({ message_id: 'm1', position }));
    // The first link is the slowest, the last the fastest: the worst case for the old code.
    const grouped = await groupInOrder(rows, async (r) => { await wait((4 - r.position) * 15); return `photo-${r.position}`; });
    expect(grouped.get('m1')).toEqual(['photo-0', 'photo-1', 'photo-2', 'photo-3']);
  });

  it('keeps several messages apart', async () => {
    const rows: Row[] = [
      { message_id: 'a', position: 0 }, { message_id: 'a', position: 1 }, { message_id: 'b', position: 0 },
      { message_id: 'a', position: 2 }, { message_id: 'b', position: 1 },
    ];
    const grouped = await groupInOrder(rows, async (r) => { await wait(Math.random() * 20); return `${r.message_id}${r.position}`; });
    expect(grouped.get('a')).toEqual(['a0', 'a1', 'a2']);
    expect(grouped.get('b')).toEqual(['b0', 'b1']);
    expect(grouped.size).toBe(2);
  });

  it('is empty for no rows, and a single photo is still one', async () => {
    expect((await groupInOrder([], async () => 1)).size).toBe(0);
    expect((await groupInOrder([{ message_id: 'x', position: 0 }], async () => 'only')).get('x')).toEqual(['only']);
  });

  it('shows the mistake it replaces: grouping inside the async callback loses photos', async () => {
    const rows: Row[] = [0, 1, 2, 3].map((position) => ({ message_id: 'm1', position }));
    const broken = new Map<string, string[]>();
    await Promise.all(rows.map(async (r) => {
      const list = broken.get(r.message_id) ?? [];
      list.push(await (async () => { await wait(5); return `photo-${r.position}`; })());
      broken.set(r.message_id, list);
    }));
    expect(broken.get('m1')?.length).toBe(1); // only one survives: this is the bug that was in the server
  });
});
