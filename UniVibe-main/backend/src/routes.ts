import type { FastifyInstance } from 'fastify';
import type { Ctx } from './context';
import { discoverRoutes } from './modules/discover';
import { eventRoutes } from './modules/events';
import { chatExtraRoutes } from './modules/chat_extras';
import { messagingRoutes } from './modules/messaging';
import { moderationRoutes } from './modules/moderation';
import { notificationRoutes } from './modules/notifications';
import { privacyRoutes } from './modules/privacy';
import { searchRoutes } from './modules/search';
import { wsRoutes } from './modules/ws';
import { Hub } from './realtime';

/**
 * The new modules, served under /api/v1. Posts, comments, follows, profiles and media keep their existing
 * unversioned routes (the Android app already uses them); they are extended in place, not duplicated here.
 */
export function apiV1(ctx: Ctx, hub: Hub = new Hub()) {
  return async (app: FastifyInstance) => {
    await app.register(privacyRoutes(ctx));
    await app.register(discoverRoutes(ctx));
    await app.register(messagingRoutes(ctx, hub));
    await app.register(chatExtraRoutes(ctx, hub));
    await app.register(wsRoutes(ctx, hub));
    await app.register(eventRoutes(ctx));
    await app.register(moderationRoutes(ctx));
    await app.register(searchRoutes(ctx));
    await app.register(notificationRoutes(ctx, hub));
  };
}
