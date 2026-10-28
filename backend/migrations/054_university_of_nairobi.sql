-- University of Nairobi. Student email: name@students.uonbi.ac.ke (staff use @uonbi.ac.ke, so it is not listed here).
-- No campuses are listed yet, so sign-up does not ask for one. To offer a campus picker, insert rows into `campuses`
-- for university_id = 'uon' (see 049_multicampus.sql for the format).
insert into universities (id, name, email_domains, short_name)
values ('uon', 'University of Nairobi', array['students.uonbi.ac.ke'], 'UoN')
on conflict (id) do nothing;

-- If 'uon' already existed (added by hand), make sure the student domain and short label are set.
update universities
   set email_domains = case when 'students.uonbi.ac.ke' = any(email_domains) then email_domains else email_domains || array['students.uonbi.ac.ke'] end,
       short_name = coalesce(short_name, 'UoN')
 where id = 'uon';
