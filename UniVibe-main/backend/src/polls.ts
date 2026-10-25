import { z } from 'zod';

/**
 * The one set of poll rules, shared by feed posts and community channel messages so the two can never drift apart:
 * 2 to 4 distinct options, single choice, one vote per student that cannot be changed, anonymous counts, and a closing
 * time at most 3 days away. The vote lives in its own table per host (poll_votes for posts, community_channel_poll_votes
 * for channel messages); everything about what a poll looks like on the wire lives here.
 */
export const POLL_MAX_HOURS = 72;

export const pollSchema = z.object({
  options: z
    .array(z.string().trim().min(1, 'Poll options cannot be empty.').max(40, 'Keep poll options under 40 characters.'))
    .min(2, 'A poll needs at least 2 options.')
    .max(4, 'A poll can have at most 4 options.')
    .refine((o) => new Set(o.map((s) => s.toLowerCase())).size === o.length, 'Poll options must be different.'),
  /** How long voting stays open. Never more than 3 days. */
  durationHours: z.number().int().min(1, 'A poll must run for at least an hour.').max(POLL_MAX_HOURS, 'A poll can run for 3 days at most.').default(24),
});

export interface PollView {
  options: { text: string; votes: number }[];
  /** The option this viewer chose, or null. Always null in viewer-neutral (broadcast) copies. */
  myVote: number | null;
  /** Seconds left, not a date, so a wrong clock on the phone cannot keep a poll open or end it early. */
  secondsLeft: number;
  ended: boolean;
}

export function pollView(options: string[], secondsLeft: number | null, myVote: number | null, counts: Record<string, number> | null): PollView {
  const left = secondsLeft ?? 0;
  return {
    options: options.map((text, i) => ({ text, votes: counts?.[String(i)] ?? 0 })),
    myVote: myVote ?? null,
    secondsLeft: left,
    ended: left <= 0,
  };
}
