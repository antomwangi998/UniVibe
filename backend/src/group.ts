/**
 * Groups [rows] by their message_id, keeping the order the rows came in (for attachments: their position).
 *
 * [toItem] is async (it makes a signed link), and all of them run at once. The grouping happens only after every one has
 * finished, so a slow one can never overwrite the list a fast one just added to. (Grouping inside the async function
 * looks the same but is not: every call reads "no list yet" before any of them has written one, so with four photos in a
 * message only the last to finish was kept.)
 */
export async function groupInOrder<T extends { message_id: string }, R>(rows: T[], toItem: (row: T) => Promise<R>): Promise<Map<string, R[]>> {
  const items = await Promise.all(rows.map(toItem));
  const grouped = new Map<string, R[]>();
  rows.forEach((row, i) => {
    const list = grouped.get(row.message_id);
    if (list) list.push(items[i]); else grouped.set(row.message_id, [items[i]]);
  });
  return grouped;
}
