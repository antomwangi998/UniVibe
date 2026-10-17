import websocket from '@fastify/websocket';
import type { FastifyInstance } from 'fastify';
import type { WebSocket } from 'ws';
import { z } from 'zod';
import type { AuthUser, Ctx } from '../context';
import { HttpError } from '../errors';
import type { Hub } from '../realtime';
import { markRead, sendMessage, typingSignal } from './messaging';

const MAX_FRAME_BYTES = 16 * 1024;
const AUTH_TIMEOUT_MS = 5000;
const HEARTBEAT_MS = 30000;

const inbound = z.discriminatedUnion('type', [
  z.object({ type: z.literal('auth'), token: z.string().min(10).max(2000) }),
  z.object({ type: z.literal('message:send'), conversationId: z.string().uuid(), content: z.string().optional(), clientId: z.string().optional(), replyToId: z.string().optional(), mediaIds: z.array(z.string()).optional() }),
  z.object({ type: z.literal('message:read'), conversationId: z.string().uuid(), upToSeq: z.number().int().nonnegative().optional() }),
  z.object({ type: z.literal('typing:start'), conversationId: z.string().uuid() }),
  z.object({ type: z.literal('typing:stop'), conversationId: z.string().uuid() }),
  z.object({ type: z.literal('ping') }),
]);

/**
 * GET /api/v1/ws. The first frame must be {type:"auth", token}; the token is not accepted in the URL, which would
 * end up in server logs. The token is re-checked on every operation, so an expired or suspended session stops working
 * (the socket is closed and the client reconnects with a fresh token).
 */
export function wsRoutes(ctx: Ctx, hub: Hub) {
  return async (app: FastifyInstance) => {
    await app.register(websocket, { options: { maxPayload: MAX_FRAME_BYTES } });

    const coMembers = async (userId: string): Promise<string[]> =>
      (await ctx.pool.query<{ user_id: string }>(
        `select distinct m2.user_id from conversation_members m1 join conversation_members m2 on m2.conversation_id = m1.conversation_id
          where m1.user_id = $1 and m2.user_id <> $1`, [userId])).rows.map((r) => r.user_id);

    const announcePresence = async (userId: string, online: boolean) => {
      // Only people who allow it appear online, and only to people they share a conversation with.
      const allowed = (await ctx.pool.query('select coalesce((select show_online from privacy_settings where user_id = $1), true) as ok', [userId])).rows[0].ok;
      if (allowed) hub.sendMany(await coMembers(userId), { type: 'presence:update', userId, online });
    };

    const alive = new WeakMap<WebSocket, boolean>();
    const beat = setInterval(() => {
      // Connections that miss a heartbeat are dropped, which is what clears stale "online" and typing state.
      for (const ws of app.websocketServer.clients) {
        if (alive.get(ws) === false) { ws.terminate(); continue; }
        alive.set(ws, false);
        ws.ping();
      }
    }, HEARTBEAT_MS);
    beat.unref();
    app.addHook('onClose', async () => { clearInterval(beat); hub.closeAll(); });

    app.get('/ws', { websocket: true }, (socket) => {
      let user: AuthUser | null = null;
      let token = '';
      alive.set(socket, true);
      socket.on('pong', () => alive.set(socket, true));
      const send = (obj: unknown) => { if (socket.readyState === socket.OPEN) socket.send(JSON.stringify(obj)); };
      const authTimer = setTimeout(() => { if (!user) socket.close(4401, 'auth required'); }, AUTH_TIMEOUT_MS);

      socket.on('message', async (raw: Buffer) => {
        let frame: z.infer<typeof inbound>;
        try {
          frame = inbound.parse(JSON.parse(raw.toString('utf8')));
        } catch {
          return send({ type: 'error', code: 'bad_request' });
        }
        try {
          if (frame.type === 'auth') {
            if (user) return;
            user = await ctx.authToken(frame.token);
            token = frame.token;
            clearTimeout(authTimer);
            if (hub.add(user.id, socket)) void announcePresence(user.id, true);
            return send({ type: 'auth:ok', userId: user.id });
          }
          if (!user) return socket.close(4401, 'auth required');
          user = await ctx.authToken(token); // expired, revoked or suspended sessions end here
          ctx.limit(`ws:${user.id}`, 200, 60);
          switch (frame.type) {
            case 'ping': return send({ type: 'pong' });
            case 'message:send': {
              const { conversationId, ...rest } = frame;
              const { type: _t, ...input } = rest;
              const { message } = await sendMessage(ctx, hub, user, conversationId, input);
              return send({ type: 'message:ack', clientId: frame.clientId ?? null, message });
            }
            case 'message:read': return void (await markRead(ctx, hub, user, frame.conversationId, frame.upToSeq));
            case 'typing:start': return void (await typingSignal(ctx, hub, user, frame.conversationId, true));
            case 'typing:stop': return void (await typingSignal(ctx, hub, user, frame.conversationId, false));
          }
        } catch (e) {
          if (e instanceof HttpError) {
            send({ type: 'error', code: e.code, status: e.status, clientId: (frame as { clientId?: string }).clientId ?? null });
            if (e.status === 401 || e.status === 403 && e.code === 'account_suspended') socket.close(4401, 'unauthorized');
            return;
          }
          if (e instanceof z.ZodError) return send({ type: 'error', code: 'validation' });
          app.log.error({ err: e }, 'websocket handler failed');
          send({ type: 'error', code: 'server_error' });
        }
      });

      socket.on('close', () => {
        clearTimeout(authTimer);
        if (!user) return;
        const me = user;
        // Typing stops with the connection, but the user only goes offline when their last device disconnects.
        hub.clearTypingFor(me.id, (conversationId) => {
          void ctx.pool.query('select user_id from conversation_members where conversation_id = $1 and user_id <> $2', [conversationId, me.id])
            .then((r) => hub.sendMany(r.rows.map((x: { user_id: string }) => x.user_id), { type: 'typing:update', conversationId, userId: me.id, typing: false }))
            .catch(() => undefined);
        });
        if (hub.remove(me.id, socket)) void announcePresence(me.id, false).catch(() => undefined);
      });
    });
  };
}
