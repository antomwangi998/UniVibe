-- Indexes for the For You ranking (src/modules/for_you_rank.ts): it looks up what one student liked or commented on
-- recently, and what a group of similar students engaged with, so both need to be quick by person and time.
create index if not exists post_likes_user_recent on post_likes (user_id, created_at desc);
create index if not exists comments_author_recent on comments (author_id, created_at desc) where status = 'active';
create index if not exists post_reposts_user_recent on post_reposts (user_id, created_at desc);
-- "People on my course" at one university.
create index if not exists users_university_course on users (university_id, course);
