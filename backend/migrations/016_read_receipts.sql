-- Read receipts become a server-enforced privacy setting. When a person turns them off, other people are no longer
-- told (live or in conversation data) how far that person has read. Reading still works normally for them.
alter table privacy_settings add column read_receipts boolean not null default true;
