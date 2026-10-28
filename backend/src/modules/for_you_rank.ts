import { inScope, notSuspended } from '../access';
import { visible } from '../posts';
import { discoverAudience } from './community_state';

/**
 * The For You ranking, written as one SQL query. No machine learning, no extra service: every signal is computed from
 * rows that already exist, and every weight is below so it can be tuned by editing numbers.
 *
 * Five signals, each squashed into 0..1, then blended:
 *
 *  1. interest     What this student cares about. Their own interests matching the text, hashtags they have been
 *                  liking and commenting on lately, authors they keep engaging with, authors they follow, and
 *                  authors on their own course. (Learned from behaviour, so it adapts without them filling anything in.)
 *  2. similar      Engagement from similar students. "Similar" = people who liked or commented on the same posts as
 *                  this student (at least two in common), people they follow, and people on their course. A post
 *                  those people engaged with scores higher. This is collaborative filtering, done with joins.
 *  3. predicted    A prediction of how likely people are to engage once they see it: the post's engagement per view,
 *                  shrunk toward its author's track record, which is shrunk toward the campus average. A brand-new
 *                  post from an average author starts at 0.5, a proven one rises, a poor one falls. This is
 *                  empirical-Bayes smoothing, the standard way to estimate a rate from very little data.
 *  4. fresh        Half-life decay (see HALF_LIFE_HOURS), plus a gentle multiplier so old posts fade rather than vanish.
 *  5. quality      Has real media, has a caption of a sensible length, a poll; penalised for hashtag stuffing and
 *                  shouting. Reports divide the whole score.
 *
 * Everything is "as of" the instant in $3, so pages of one feed never reshuffle while the student scrolls.
 * Parameters: $1 viewer, $2 viewer's university, $3 as-of time, $4 window in hours, $8 campus scope.
 */
export const FOR_YOU_WEIGHTS = {
  interest: 0.28,
  similar: 0.22,
  predicted: 0.22,
  quality: 0.10,
  fresh: 0.18,
  /** A post loses half its freshness every this many hours. */
  halfLifeHours: 18,
  /** Pseudo-views that pull a post's engagement rate toward its author's; more = trust new posts less. */
  postPrior: 10,
  /** Same, pulling the author's rate toward the campus average. */
  authorPrior: 30,
} as const;

const W = FOR_YOU_WEIGHTS;
const TAG = `'#([a-z0-9_]{2,30})'`;

/** Returns a complete `with ... select ... where ...` query; the caller may append `and <more conditions>`. */
export const forYouSql = (notHidden: string) => `
  with
  viewer as (select u.id, u.course, u.university_id from users u where u.id = $1),

  -- What the viewer engaged with in the last 45 days (comments count most), each post once.
  my_eng as (
    select post_id, max(w) as w from (
      select l.post_id, 1.0 as w from post_likes l
       where l.user_id = $1 and l.created_at <= $3::timestamptz and l.created_at > $3::timestamptz - interval '45 days'
      union all
      select c.post_id, 1.5 from comments c
       where c.author_id = $1 and c.status = 'active' and c.created_at <= $3::timestamptz and c.created_at > $3::timestamptz - interval '45 days'
      union all
      select r.post_id, 1.2 from post_reposts r
       where r.user_id = $1 and r.created_at <= $3::timestamptz and r.created_at > $3::timestamptz - interval '45 days'
    ) x group by post_id
  ),
  my_authors as (
    select mp.author_id, sum(m.w) as w from my_eng m join posts mp on mp.id = m.post_id group by mp.author_id
  ),
  my_tags as (
    select t.tag, sum(m.w) as w
      from my_eng m join posts mp on mp.id = m.post_id
     cross join lateral (select distinct (regexp_matches(lower(mp.text), ${TAG}, 'g'))[1] as tag) t
     group by t.tag
  ),

  -- Similar students: co-engagement with the viewer, people they follow, people on their course.
  neighbors as (
    select n.user_id, sum(n.w) as overlap from (
      select l.user_id, m.w from my_eng m join post_likes l on l.post_id = m.post_id
       where l.user_id <> $1 and l.created_at <= $3::timestamptz
      union all
      select c.author_id, m.w from my_eng m join comments c on c.post_id = m.post_id
       where c.author_id <> $1 and c.status = 'active' and c.created_at <= $3::timestamptz
    ) n group by n.user_id
  ),
  sim as (
    select s.user_id, least(4.0, sum(s.w)) as w from (
      select n.user_id, least(3.0, n.overlap / 2.0) as w from neighbors n where n.overlap >= 2
      union all
      select f.followee_id, 0.8 from follows f where f.follower_id = $1
      union all
      select u.id, 0.6 from users u join viewer on true
       where u.id <> viewer.id and viewer.course <> '' and u.course = viewer.course and u.university_id = viewer.university_id
    ) s group by s.user_id
  ),
  sim_post as (
    select x.post_id, sum(x.w) as w from (
      select l.post_id, s.w from sim s join post_likes l on l.user_id = s.user_id
       where l.created_at <= $3::timestamptz and l.created_at > $3::timestamptz - make_interval(hours => $4::int)
      union all
      select c.post_id, s.w from sim s join comments c on c.author_id = s.user_id
       where c.status = 'active' and c.created_at <= $3::timestamptz and c.created_at > $3::timestamptz - make_interval(hours => $4::int)
    ) x group by x.post_id
  ),

  -- Raw counts for every campus-wide post in the window (also used for the author and campus averages).
  stats as (
    select p.id, p.author_id, p.share_count, sig.likers, sig.commenters, sig.reposters, sig.views,
           sig.recent_likes, sig.recent_commenters, sig.reports
      from posts p join users a on a.id = p.author_id
     cross join lateral (select
        (select count(distinct l.user_id) from post_likes l where l.post_id = p.id and l.created_at <= $3::timestamptz and l.user_id <> p.author_id) as likers,
        (select count(distinct c.author_id) from comments c where c.post_id = p.id and c.status = 'active' and c.created_at <= $3::timestamptz and c.author_id <> p.author_id) as commenters,
        (select count(*) from post_reposts r where r.post_id = p.id and r.created_at <= $3::timestamptz and r.user_id <> p.author_id) as reposters,
        (select count(*) from post_views v where v.post_id = p.id and v.created_at <= $3::timestamptz) as views,
        (select count(*) from post_likes l where l.post_id = p.id and l.created_at <= $3::timestamptz and l.created_at > $3::timestamptz - interval '6 hours' and l.user_id <> p.author_id) as recent_likes,
        (select count(distinct c.author_id) from comments c where c.post_id = p.id and c.status = 'active' and c.created_at <= $3::timestamptz and c.created_at > $3::timestamptz - interval '6 hours' and c.author_id <> p.author_id) as recent_commenters,
        (select count(*) from post_reports r where r.post_id = p.id) as reports) sig
     where p.status = 'active' and ${discoverAudience('p')} and ${inScope('a', '$8')} and ${notSuspended('a')}
       and p.created_at <= $3::timestamptz and p.created_at > $3::timestamptz - make_interval(hours => $4::int)
  ),
  -- eng = weighted engagement; imps = views, but never fewer than the engagements (views may not all be recorded).
  weighted as (
    select s.*, (s.likers + 2.0 * s.commenters + 1.5 * s.reposters + 0.5 * least(s.share_count, 20)) as eng from stats s
  ),
  weighted2 as (select w.*, greatest(w.views, w.eng) as imps from weighted w),
  glob as (
    select least(0.5, greatest(0.01, coalesce(sum(eng) / nullif(sum(imps), 0), 0.05))) as rate from weighted2
  ),
  by_author as (select author_id, sum(eng) as eng, sum(imps) as imps from weighted2 group by author_id)

  select p.id, p.author_id, p.created_at,
         w.likers, w.commenters, w.recent_likes, w.recent_commenters,
         x.followed, x.interest_match,
         round((((
             ${W.interest} * c.interest + ${W.similar} * c.similar + ${W.predicted} * c.pred + ${W.quality} * c.quality
           ) * (0.5 + 0.5 * c.fresh) + ${W.fresh} * c.fresh) / (1 + w.reports))::numeric, 9) as score
    from weighted2 w
    join posts p on p.id = w.id
    join users a on a.id = p.author_id
   cross join glob g
   cross join lateral (select
        exists (select 1 from follows f where f.follower_id = $1 and f.followee_id = p.author_id) as followed,
        exists (select 1 from users me, unnest(me.interests) i where me.id = $1 and length(i) >= 2 and position(lower(i) in lower(p.text)) > 0) as interest_match,
        exists (select 1 from viewer v where v.course <> '' and v.course = a.course) as same_course,
        coalesce((select ma.w from my_authors ma where ma.author_id = p.author_id), 0) as author_aff,
        coalesce((select sum(mt.w) from my_tags mt where mt.tag in (select (regexp_matches(lower(p.text), ${TAG}, 'g'))[1])), 0) as tag_aff,
        coalesce((select sp.w from sim_post sp where sp.post_id = p.id), 0) as sim_w,
        exists (select 1 from media m where m.post_id = p.id and m.status = 'ready') as has_media,
        char_length(p.text) as len,
        (select count(*) from regexp_matches(p.text, '#[A-Za-z0-9_]+', 'g')) as tag_count,
        coalesce((select ba.eng from by_author ba where ba.author_id = p.author_id), 0) - w.eng as a_eng,
        coalesce((select ba.imps from by_author ba where ba.author_id = p.author_id), 0) - w.imps as a_imps
   ) x
   cross join lateral (select
        -- the author's engagement per view, pulled toward the campus average; then this post's, pulled toward the author's
        (w.eng + ${W.postPrior} * ((x.a_eng + ${W.authorPrior} * g.rate) / (x.a_imps + ${W.authorPrior}))) / (w.imps + ${W.postPrior}) as rate
   ) r
   cross join lateral (select
        1 - exp(-(
            (case when x.interest_match then 1.0 else 0.0 end)
          + 0.6 * ln(1 + x.tag_aff) + 0.5 * ln(1 + x.author_aff)
          + (case when x.followed then 0.6 else 0.0 end)
          + (case when x.same_course then 0.3 else 0.0 end)
          + (case when a.university_id = $2 then 0.2 else 0.0 end)) / 1.5) as interest,
        1 - exp(-x.sim_w / 2.0) as similar,
        r.rate / (r.rate + g.rate) as pred,
        exp(-0.693147 * (extract(epoch from ($3::timestamptz - p.created_at)) / 3600.0) / ${W.halfLifeHours}) as fresh,
        greatest(0, least(1,
            0.45 * (case when x.len = 0 then (case when x.has_media then 0.6 else 0.0 end) when x.len < 8 then 0.3 else 1.0 end)
          + 0.35 * (case when x.has_media then 1 else 0 end)
          + (case when p.poll_ends_at is not null then 0.10 else 0.0 end) + 0.10)
          - 0.3 * ((case when x.tag_count > 5 then 1 else 0 end)
                 + (case when x.len >= 12 and length(regexp_replace(p.text, '[^A-Z]', '', 'g')) > 0.7 * greatest(1, length(regexp_replace(p.text, '[^A-Za-z]', '', 'g'))) then 1 else 0 end))) as quality
   ) c
   where ${visible('p', 'a')} and ${inScope('a', '$8')} and ${discoverAudience('p')} and ${notHidden}`;
