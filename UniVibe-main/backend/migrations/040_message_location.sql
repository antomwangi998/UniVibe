-- A shared place in a chat message ("send current location"). Both numbers or neither. The server stores them
-- already rounded (about 10 m), so an exact position is never kept.
alter table messages
  add column location_lat double precision check (location_lat is null or location_lat between -90 and 90),
  add column location_lng double precision check (location_lng is null or location_lng between -180 and 180),
  add column location_label text check (location_label is null or char_length(location_label) <= 80),
  add constraint messages_location_pair check ((location_lat is null) = (location_lng is null));
