-- =============================================================================
-- GPS tracking during the working day, and the manager's map of it
--
-- Legacy: both Flutter apps ran a ServiceBackground that posted the phone's
-- position every few minutes; the manager's OM30400 report drew the reps'
-- paths on a map. Here the SFA app runs a foreground service from clock-in to
-- clock-out and sends its fixes in batches to submit_locations().
--
-- A position is only kept inside the working day the rep declared: after that
-- day's clock-in and, once they have clocked out, not after it. The phone is
-- told to stop at clock-out, but the rule is the server's so that a service
-- left running, a clock set wrong or a replayed batch cannot put an evening or
-- a weekend on record. Points too inaccurate to place a person on a street
-- (worse than gps_track_max_accuracy_m) are dropped as well.
-- =============================================================================

insert into app_setting (key, value, description)
values
    ('gps_track_interval_s', '300', 'Giây giữa hai lần lấy vị trí khi đang trong ngày làm việc'),
    ('gps_track_max_accuracy_m', '200', 'Bỏ điểm theo dõi có sai số lớn hơn mức này (mét)')
on conflict (key) do nothing;

create table location_trace (
    id             bigint generated always as identity primary key,
    salesperson_id uuid             not null references salesperson (id),
    recorded_at    timestamptz      not null,
    lat            double precision not null check (lat between -90 and 90),
    lng            double precision not null check (lng between -180 and 180),
    accuracy_m     real,
    speed_mps      real,
    battery_pct    smallint,
    created_at     timestamptz      not null default now(),
    -- A batch resent after a dropped response lands on the same rows.
    unique (salesperson_id, recorded_at)
);

alter table location_trace enable row level security;

create policy "rep reads own trace"
    on location_trace for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "manager reads team trace"
    on location_trace for select to authenticated
    using (salesperson_id in (select team_salesperson_ids()));

-- Written only through submit_locations().

/**
 * p_points: [{ recorded_at, lat, lng, accuracy_m?, speed_mps?, battery_pct? }].
 * Returns how many were kept and how many dropped, so the phone can discard
 * the batch either way: a dropped point will never be accepted on a retry.
 */
create or replace function submit_locations(p_points jsonb)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me       uuid := current_salesperson_id();
    v_max_acc  real := coalesce((select value::real from app_setting where key = 'gps_track_max_accuracy_m'), 200);
    v_total    integer := jsonb_array_length(coalesce(p_points, '[]'::jsonb));
    v_kept     integer;
begin
    if v_me is null then
        raise exception 'no salesperson is linked to this account';
    end if;
    if v_total > 500 then
        raise exception 'at most 500 points per batch';
    end if;

    with pts as (
        select (p ->> 'recorded_at')::timestamptz as at,
               (p ->> 'lat')::double precision as lat,
               (p ->> 'lng')::double precision as lng,
               (p ->> 'accuracy_m')::real as acc,
               (p ->> 'speed_mps')::real as speed,
               (p ->> 'battery_pct')::smallint as battery
        from jsonb_array_elements(coalesce(p_points, '[]'::jsonb)) p
    ),
    in_day as (
        select pts.* from pts
        where pts.at <= now() + interval '5 minutes'
          and pts.lat between -90 and 90 and pts.lng between -180 and 180
          and (pts.acc is null or pts.acc <= v_max_acc)
          and exists (
              select 1 from timekeeping ci
              where ci.salesperson_id = v_me and ci.type = 'check_in'
                and ci.work_date = (pts.at at time zone 'Asia/Ho_Chi_Minh')::date
                and pts.at >= ci.happened_at
                and not exists (
                    select 1 from timekeeping co
                    where co.salesperson_id = v_me and co.type = 'check_out'
                      and co.work_date = ci.work_date and pts.at > co.happened_at
                )
          )
    )
    insert into location_trace (salesperson_id, recorded_at, lat, lng, accuracy_m, speed_mps, battery_pct)
    select v_me, at, lat, lng, acc, speed, battery from in_day
    on conflict (salesperson_id, recorded_at) do nothing;

    get diagnostics v_kept = row_count;
    return jsonb_build_object('accepted', v_kept, 'dropped', v_total - v_kept);
end;
$$;

/**
 * OM30400: each rep's day on a map — the path, where they are now, and the
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
        ) order by s.code)
        from salesperson s
        where s.id in (select team_reps())
          and (p_rep is null or s.id = p_rep)
    ), '[]'::jsonb);
end;
$$;

revoke execute on function submit_locations(jsonb) from public, anon;
revoke execute on function mgr_location_map(date, uuid) from public, anon;
grant execute on function submit_locations(jsonb) to authenticated;
grant execute on function mgr_location_map(date, uuid) to authenticated;
