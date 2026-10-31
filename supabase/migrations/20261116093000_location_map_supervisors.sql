-- =============================================================================
-- The supervisors' own days on the map.
--
-- The manager app now records a supervisor's position through their working
-- day as the rep app does a rep's (submit_locations already keeps points for
-- whoever is signed in, inside their own clock-in and clock-out). An area or
-- regional manager sees those days next to the reps': the map takes the
-- supervisors in the caller's tree as well, and says which row is which.
-- For a supervisor, who has no supervisors under them, nothing changes.
-- =============================================================================

/**
 * OM30400: each rep's day on a map (and, for an area or regional manager, each
 * supervisor's) — the path, where they are now, and the
 * outlets they called on (check-in position, time, outcome).
 */
create or replace function mgr_location_map(p_date date, p_rep uuid default null)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
    if p_rep is not null and not is_team_member(p_rep) then
        raise exception 'nhân viên không thuộc nhóm của bạn';
    end if;
    return coalesce((
        select jsonb_agg(jsonb_build_object(
            'rep', jsonb_build_object('id', s.id, 'code', s.code, 'name', s.full_name),
            'position', s.position,
            'clock_in', (select min(t.happened_at) from timekeeping t
                         where t.salesperson_id = s.id and t.work_date = p_date and t.type = 'check_in'),
            'clock_out', (select max(t.happened_at) from timekeeping t
                          where t.salesperson_id = s.id and t.work_date = p_date and t.type = 'check_out'),
            'path', coalesce((
                select jsonb_agg(jsonb_build_array(l.lat, l.lng, l.recorded_at) order by l.recorded_at)
                from location_trace l
                where l.salesperson_id = s.id
                  and (l.recorded_at at time zone 'Asia/Ho_Chi_Minh')::date = p_date
            ), '[]'::jsonb),
            'visits', coalesce((
                select jsonb_agg(jsonb_build_object(
                    'customer_code', c.code, 'customer_name', c.name,
                    'lat', coalesce(v.check_in_lat, c.lat), 'lng', coalesce(v.check_in_lng, c.lng),
                    'check_in_at', v.check_in_at, 'check_out_at', v.check_out_at, 'status', v.status,
                    'distance_m', v.check_in_distance_m
                ) order by v.check_in_at)
                from visit v join customer c on c.id = v.customer_id
                where v.salesperson_id = s.id and v.visit_date = p_date and v.check_in_at is not null
            ), '[]'::jsonb)
        ) order by s.position desc, s.code)
        from salesperson s
        where (s.id in (select team_reps())
               or (s.id in (select team_salesperson_ids()) and s.position <> 'sr'))
          and (p_rep is null or s.id = p_rep)
    ), '[]'::jsonb);
end;
$$;
