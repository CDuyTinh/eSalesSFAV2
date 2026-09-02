-- =============================================================================
-- One open visit at a time
--
-- The app this replaces refuses a check-in while another shop is still open —
-- `if (custIdIncall.isEmpty && enableCheckin)` guards the only path to its
-- check-in screen. This build had no such rule at any layer: not on the card,
-- not in submit-visit, and not here. A rep could leave one shop mid-visit, check
-- into the next, and end the day with two visits whose durations overlap.
--
-- That is not a tidiness problem. check_in_at and check_out_at are what working
-- time is reported from, and two overlapping visits make both of them
-- unreadable — there is no way afterwards to say which shop the rep was actually
-- in.
--
-- Scoped to (salesperson_id, visit_date) rather than to the salesperson alone.
-- A visit left open yesterday is closed by close_abandoned_visits, which /route
-- calls at the start of the day — but only once the rep opens the route. A
-- salesperson-wide index would let yesterday's unswept visit block this morning's
-- first check-in, which is a worse failure than the one being prevented: it
-- stops a rep working, in a shop, for a reason they cannot see or fix.
--
-- The `where` clause is what makes this safe to add to live data. Completed,
-- abandoned, no-order and closed visits are all outside the index, so a customer
-- visited every week for a year still indexes exactly zero rows here.
-- =============================================================================

create unique index if not exists visit_one_open_per_salesperson_day
    on visit (salesperson_id, visit_date)
    where status = 'in_progress';
