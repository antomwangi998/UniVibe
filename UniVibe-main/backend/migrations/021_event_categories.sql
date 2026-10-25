-- The app offers Career and Party events; the server now accepts them too.
alter table events drop constraint events_category_check;
alter table events add constraint events_category_check check (category in
  ('academic', 'workshops', 'sports', 'social', 'clubs', 'technology', 'entrepreneurship', 'arts', 'other', 'career', 'party'));
