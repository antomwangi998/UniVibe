-- Typing indicators become a server-enforced privacy setting. With it off, other people are never told this person is typing.
alter table privacy_settings add column typing_indicators boolean not null default true;
