-- Development placeholder that matches the app's mock. Add your real university with:
--   insert into universities (id, name, email_domains) values ('your-id', 'Your University', array['students.youruni.ac.ke']);
insert into universities (id, name, email_domains)
values ('lakeside', 'Lakeside University', array['lakeside.example'])
on conflict (id) do nothing;
