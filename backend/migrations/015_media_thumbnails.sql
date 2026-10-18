-- Stage 15: processed photos. A feed thumbnail is stored beside the full-size photo (post photos only).
alter table media add column if not exists thumb_key text;
