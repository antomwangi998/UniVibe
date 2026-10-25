-- Optional map position for an in-person event's venue. Both or neither; null means "no map".
alter table events
  add column latitude double precision check (latitude is null or latitude between -90 and 90),
  add column longitude double precision check (longitude is null or longitude between -180 and 180),
  add constraint events_coordinates_pair check ((latitude is null) = (longitude is null));
