-- Monetization hardening: promotion notices, partial (overpayment) refunds, daily roll-up of ad events.

-- A new notification type for advertiser and staff notices about payments, campaigns and refunds.
alter table notifications drop constraint notifications_type_check;
alter table notifications add constraint notifications_type_check check (type in (
  'follow', 'like', 'comment', 'reply', 'message', 'group_invite', 'verification', 'event_update',
  'community_join_request', 'community_join_approved', 'community_join_declined', 'community_invitation',
  'community_role_changed', 'community_member_removed', 'community_announcement', 'story_reaction',
  'community_channel_announcement', 'promotion'));

-- 'overpayment' refunds hand back only the excess of a confirmed payment; the campaign itself carries on.
alter table refunds add column kind text not null default 'full' check (kind in ('full', 'overpayment'));
drop index refunds_one_open;
create unique index refunds_one_open on refunds (payment_id, kind) where status in ('requested', 'approved');

-- Day-by-day totals per campaign and placement, so raw events can be deleted after 90 days.
-- reach = distinct pseudonymous viewers that day. Written by the roll-up job, never by a request.
create table campaign_daily (
  campaign_id uuid not null references campaigns(id) on delete cascade,
  day date not null,
  placement text not null check (placement in ('home_feed', 'discover_feed', 'discover_events', 'business_directory')),
  impressions int not null default 0 check (impressions >= 0),
  clicks int not null default 0 check (clicks >= 0),
  reach int not null default 0 check (reach >= 0),
  primary key (campaign_id, day, placement)
);
