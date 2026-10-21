-- Documents (PDF, Word, ...) shared in chats. file_name is the cleaned name shown to people; the stored object has its own random name.
alter table media drop constraint media_kind_check;
alter table media add constraint media_kind_check check (kind in ('image', 'video', 'audio', 'document'));
alter table media add column file_name text check (file_name is null or char_length(file_name) <= 130);
alter table media add constraint media_document_has_name check ((kind = 'document') = (file_name is not null));

-- Files that must be removed from storage. A row is added whenever a link card with a picture is deleted, however it was
-- deleted (unsent, edited, or removed along with its conversation or account); a background job then removes the files.
create table storage_garbage (
  object_key text primary key,
  queued_at timestamptz not null default now()
);

create function queue_link_preview_image() returns trigger language plpgsql as $$
begin
  if old.image_key is not null then
    insert into storage_garbage (object_key) values (old.image_key) on conflict do nothing;
  end if;
  return old;
end $$;

create trigger link_preview_image_garbage after delete on message_link_previews
  for each row execute function queue_link_preview_image();

-- When a card's picture is replaced, the old one is garbage too.
create function queue_replaced_link_preview_image() returns trigger language plpgsql as $$
begin
  if old.image_key is not null and old.image_key is distinct from new.image_key then
    insert into storage_garbage (object_key) values (old.image_key) on conflict do nothing;
  end if;
  return new;
end $$;

create trigger link_preview_image_replaced after update of image_key on message_link_previews
  for each row execute function queue_replaced_link_preview_image();
