-- Meru University of Science and Technology (MUST). Student email: name@students.must.ac.ke (staff use other addresses).
-- No campuses are listed, so sign-up does not ask for one. To offer a campus picker, insert rows into `campuses`
-- for university_id = 'must' (see 049_multicampus.sql for the format).
insert into universities (id, name, email_domains, short_name)
values ('must', 'Meru University of Science and Technology', array['students.must.ac.ke'], 'MUST')
on conflict (id) do nothing;

-- If 'must' already existed (added by hand), make sure the student domain and short label are set.
update universities
   set email_domains = case when 'students.must.ac.ke' = any(email_domains) then email_domains else email_domains || array['students.must.ac.ke'] end,
       short_name = coalesce(short_name, 'MUST')
 where id = 'must';
