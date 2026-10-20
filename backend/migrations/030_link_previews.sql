-- One preview card per message: the title and description of the first web address in it, read by the server (never by the phones).
-- Kept only while the message exists; the app shows it only while the message's first address is still the one stored here.
create table message_link_previews (
  message_id uuid primary key references messages(id) on delete cascade,
  url text not null check (char_length(url) <= 2048),
  host text not null check (char_length(host) <= 253),
  title text check (title is null or char_length(title) <= 121),
  description text check (description is null or char_length(description) <= 201),
  site_name text check (site_name is null or char_length(site_name) <= 61),
  created_at timestamptz not null default now(),
  check (title is not null or description is not null)
);
