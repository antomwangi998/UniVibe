-- One preview card per channel message: the title, description and picture of the first web address in it, read by the
-- server (never by the phones). Same rules as message_link_previews (030, 031, 034), for community channel messages.
-- Kept only while the message exists; the app shows it only while the message's first address is still the one stored here.
create table community_channel_link_previews (
  message_id uuid primary key references community_channel_messages(id) on delete cascade,
  url text not null check (char_length(url) <= 2048),
  host text not null check (char_length(host) <= 253),
  title text check (title is null or char_length(title) <= 121),
  description text check (description is null or char_length(description) <= 201),
  site_name text check (site_name is null or char_length(site_name) <= 61),
  image_key text check (image_key is null or char_length(image_key) <= 200),
  image_width integer check (image_width is null or image_width between 1 and 4096),
  image_height integer check (image_height is null or image_height between 1 and 4096),
  created_at timestamptz not null default now(),
  check (title is not null or description is not null),
  constraint community_channel_link_previews_image_complete check ((image_key is null) = (image_width is null) and (image_key is null) = (image_height is null))
);

-- A card's picture is private storage: when the card goes (edited out, message deleted, channel deleted, however it
-- happens) or its picture is replaced, the file is queued for deletion.
create function queue_channel_link_preview_image() returns trigger language plpgsql as $$
begin
  if old.image_key is not null then
    insert into storage_garbage (object_key) values (old.image_key) on conflict do nothing;
  end if;
  return old;
end $$;

create trigger channel_link_preview_image_garbage after delete on community_channel_link_previews
  for each row execute function queue_channel_link_preview_image();

create function queue_replaced_channel_link_preview_image() returns trigger language plpgsql as $$
begin
  if old.image_key is not null and old.image_key is distinct from new.image_key then
    insert into storage_garbage (object_key) values (old.image_key) on conflict do nothing;
  end if;
  return new;
end $$;

create trigger channel_link_preview_image_replaced after update of image_key on community_channel_link_previews
  for each row execute function queue_replaced_channel_link_preview_image();
