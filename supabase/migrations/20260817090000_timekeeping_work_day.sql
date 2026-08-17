-- =============================================================================
-- Timekeeping: the selling day a punch belongs to
--
-- `timekeeping` has been in the schema since the first migration and nothing has
-- ever written to it. Opening and closing the day is the first thing that will,
-- and it needs one guarantee the table cannot currently make: a rep clocks in
-- once a day and out once a day.
--
-- Deriving that day from `happened_at` is not good enough. It is a timestamptz,
-- so the date depends on the reader's time zone, and `at time zone` is stable
-- rather than immutable — it cannot be indexed on, which is exactly where the
-- guarantee has to live. `visit` settled this question already with `visit_date`;
-- this is the same answer for the same reason, and it keeps the two tables
-- agreeing about what "today" means.
--
-- The client sends the date, as it does for a visit. It is the party that knows
-- which day the rep believes they are working — a punch at 23:58 delivered at
-- 00:01 belongs to the day the rep was standing in the depot.
-- =============================================================================

alter table timekeeping add column work_date date;

-- The table is empty in every environment this has run against; the backfill is
-- here so that stops being something anyone has to check first.
update timekeeping
   set work_date = (happened_at at time zone 'utc')::date
 where work_date is null;

alter table timekeeping alter column work_date set not null;

comment on column timekeeping.work_date is
    'The selling day this punch belongs to, sent by the client. Not derived from '
    'happened_at: that is a timestamptz, and which date it falls on depends on '
    'the time zone reading it.';

-- The guarantee. A second clock-in is refused by the database rather than by
-- whichever caller remembered to look first.
create unique index timekeeping_one_punch_per_day
    on timekeeping (salesperson_id, work_date, type);
