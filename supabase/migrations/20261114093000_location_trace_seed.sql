-- =============================================================================
-- Dữ liệu mẫu: chấm công + vết GPS cho nvbh01, nvbh02, 26–30/10/2026
--
-- The seeded visits had no clock-in to frame them and no trace between them,
-- so the map report would show outlets and nothing joining them. For every day
-- a rep has visits: clock in 30 minutes before the first call at the branch,
-- clock out 30 minutes after the last, and a fix every 5 minutes — walking
-- from the branch to each outlet in turn, standing at it for the call, and
-- back. A few metres of jitter so the line looks like a phone made it.
-- =============================================================================

do $$
declare
    d       record;
    stops   jsonb;
    n       integer;
    i       integer;
    t       timestamptz;
    seg_from jsonb;
    seg_to   jsonb;
    frac    double precision;
    lat     double precision;
    lng     double precision;
begin
    for d in
        select s.id as sp, s.branch_id, v.visit_date as day,
               b.lat as blat, b.lng as blng,
               min(v.check_in_at) - interval '30 minutes' as clock_in,
               max(coalesce(v.check_out_at, v.check_in_at)) + interval '30 minutes' as clock_out
        from visit v
        join salesperson s on s.id = v.salesperson_id and s.code in ('nvbh01', 'nvbh02')
        join branch b on b.id = s.branch_id
        where v.visit_date between date '2026-10-26' and date '2026-10-30'
          and v.check_in_at is not null
          and v.work_with_salesperson_id is null
        group by s.id, s.branch_id, v.visit_date, b.lat, b.lng
    loop
        insert into timekeeping (salesperson_id, branch_id, type, work_date, happened_at, lat, lng, accuracy_m, distance_m)
        values (d.sp, d.branch_id, 'check_in',  d.day, d.clock_in,  d.blat, d.blng, 12, 8),
               (d.sp, d.branch_id, 'check_out', d.day, d.clock_out, d.blat, d.blng, 15, 11)
        on conflict do nothing;

        -- Way-points: branch, each call (arrive at check-in, leave at check-out), branch.
        select jsonb_agg(x order by (x ->> 'at')::timestamptz) into stops from (
            select jsonb_build_object('at', d.clock_in, 'lat', d.blat, 'lng', d.blng) as x
            union all
            select jsonb_build_object('at', v.check_in_at, 'lat', coalesce(v.check_in_lat, c.lat), 'lng', coalesce(v.check_in_lng, c.lng))
            from visit v join customer c on c.id = v.customer_id
            where v.salesperson_id = d.sp and v.visit_date = d.day and v.check_in_at is not null
            union all
            select jsonb_build_object('at', coalesce(v.check_out_at, v.check_in_at), 'lat', coalesce(v.check_in_lat, c.lat), 'lng', coalesce(v.check_in_lng, c.lng))
            from visit v join customer c on c.id = v.customer_id
            where v.salesperson_id = d.sp and v.visit_date = d.day and v.check_in_at is not null
            union all
            select jsonb_build_object('at', d.clock_out, 'lat', d.blat, 'lng', d.blng)
        ) w;
        n := jsonb_array_length(stops);

        t := d.clock_in;
        i := 0;
        while t <= d.clock_out loop
            while i < n - 2 and (stops -> (i + 1) ->> 'at')::timestamptz < t loop
                i := i + 1;
            end loop;
            seg_from := stops -> i;
            seg_to   := stops -> (i + 1);
            frac := case
                when (seg_to ->> 'at')::timestamptz <= (seg_from ->> 'at')::timestamptz then 1
                else least(1, greatest(0,
                    extract(epoch from t - (seg_from ->> 'at')::timestamptz) /
                    extract(epoch from (seg_to ->> 'at')::timestamptz - (seg_from ->> 'at')::timestamptz)))
            end;
            lat := (seg_from ->> 'lat')::double precision
                 + frac * ((seg_to ->> 'lat')::double precision - (seg_from ->> 'lat')::double precision)
                 + (random() - 0.5) * 0.0002;
            lng := (seg_from ->> 'lng')::double precision
                 + frac * ((seg_to ->> 'lng')::double precision - (seg_from ->> 'lng')::double precision)
                 + (random() - 0.5) * 0.0002;
            insert into location_trace (salesperson_id, recorded_at, lat, lng, accuracy_m, battery_pct)
            values (d.sp, t, lat, lng, 8 + random() * 20, greatest(15, 95 - extract(epoch from t - d.clock_in)::integer / 600))
            on conflict (salesperson_id, recorded_at) do nothing;
            t := t + interval '5 minutes';
        end loop;
    end loop;
end;
$$;
