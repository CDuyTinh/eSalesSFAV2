-- =============================================================================
-- Closes visits the rep checked into on an earlier day and never checked out of.
--
-- Called by /route at the start of the day, so the reconciliation happens once,
-- next to the data, before the rep is shown anything. A previous day's visit is
-- not resumable by design: checking out of it now would stamp a check_out_at
-- hours or days after the rep left the shop, and that timestamp feeds working-time
-- reporting. Better to record honestly that the visit was never closed and leave
-- check_out_at null, which is what distinguishes an abandoned visit from a
-- completed one.
--
-- Whatever the rep did capture stays exactly where it is: step results, orders and
-- stock counts all hang off the visit and are untouched. Only the visit's own
-- status changes.
-- =============================================================================

create or replace function close_abandoned_visits(p_before date default current_date)
    returns integer
    language plpgsql
    -- security invoker, i.e. the default: the update runs as the calling rep and
    -- the "rep updates own visits" policy still applies, so this can only ever
    -- close the caller's own visits. A definer function here would have been a
    -- hole straight through RLS for the sake of saving a policy check.
    security invoker
    set search_path = public
as
$$
declare
    closed integer;
begin
    update visit
       set status     = 'abandoned',
           updated_at = now()
     -- The caller passes its own local date, because "yesterday" is a question
     -- about where the rep is standing and this server runs in UTC. Clamping to
     -- current_date stops a device with a fast clock from asking us to abandon a
     -- visit that is still open in front of the rep — a real hazard on this
     -- project, where a development machine was found running 159 seconds ahead.
     -- Erring this way delays closing a stale visit by hours at worst; erring the
     -- other way destroys a visit in progress.
     where status     = 'in_progress'
       and visit_date < least(p_before, current_date);

    get diagnostics closed = row_count;
    return closed;
end;
$$;

comment on function close_abandoned_visits(date) is
    'Marks the caller''s in_progress visits from before the given local date as abandoned. Returns the number closed.';

grant execute on function close_abandoned_visits(date) to authenticated;
