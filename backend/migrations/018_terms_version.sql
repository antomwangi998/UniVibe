-- Which version of the Terms of Service the person accepted at sign-up (the app sends its Terms version).
-- Empty for accounts created before this was recorded. accepted_terms_at already holds when.
alter table users add column accepted_terms_version text not null default '' check (char_length(accepted_terms_version) <= 20);
