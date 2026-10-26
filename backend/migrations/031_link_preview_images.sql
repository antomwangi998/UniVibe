-- The picture on a link card: re-encoded and stored privately by the server (never hot-linked from the other site).
alter table message_link_previews
  add column image_key text check (image_key is null or char_length(image_key) <= 200),
  add column image_width integer check (image_width is null or image_width between 1 and 4096),
  add column image_height integer check (image_height is null or image_height between 1 and 4096),
  add constraint message_link_previews_image_complete check ((image_key is null) = (image_width is null) and (image_key is null) = (image_height is null));
