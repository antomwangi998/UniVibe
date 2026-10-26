-- The audit trail can be added to, never rewritten. Deleting a person still works: the database nulls actor_id on the
-- rows they wrote (the only change allowed), which keeps the record of what was done and drops who it pointed at.
create or replace function audit_logs_guard() returns trigger language plpgsql as $$
begin
  if tg_op = 'DELETE' then
    raise exception 'audit_logs rows cannot be deleted' using errcode = '42501';
  end if;
  if new.actor_id is null and old.actor_id is not null
     and (new.id, new.action, new.target_type, new.target_id, new.details, new.created_at)
         is not distinct from (old.id, old.action, old.target_type, old.target_id, old.details, old.created_at) then
    return new;
  end if;
  raise exception 'audit_logs rows cannot be changed' using errcode = '42501';
end $$;

drop trigger if exists audit_logs_append_only on audit_logs;
create trigger audit_logs_append_only before update or delete on audit_logs
  for each row execute function audit_logs_guard();
