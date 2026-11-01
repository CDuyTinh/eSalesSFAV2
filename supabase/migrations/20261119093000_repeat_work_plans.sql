-- =============================================================================
-- Việc lặp lại (legacy RepeatID).
--
-- A manager hands down the same task every working day or every week until a
-- date. Each occurrence is an ordinary task — it is done, reported, changed or
-- called off on its own — written in one go and marked with the series it
-- came from, so the list can say it repeats.
-- =============================================================================

alter table work_plan add column repeat_group uuid;
create index on work_plan (repeat_group) where repeat_group is not null;

/**
 * Assigns one task per occurrence from the first one's day to p_until:
 * 'day' is every day but Sunday, 'week' the same weekday each week. At most
 * 62 occurrences. @return how many were written.
 */
create function mgr_assign_work_plan_repeat(payload jsonb, p_every text, p_until date)
returns integer
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_from   timestamptz := (payload ->> 'from_at')::timestamptz;
    v_to     timestamptz := (payload ->> 'to_at')::timestamptz;
    v_first  date := (v_from at time zone 'Asia/Ho_Chi_Minh')::date;
    v_group  uuid := gen_random_uuid();
    v_day    date;
    v_shift  interval;
    v_id     uuid;
    v_count  integer := 0;
begin
    if p_every not in ('day', 'week') then
        raise exception 'kiểu lặp không hợp lệ';
    end if;
    if p_until is null or p_until <= v_first then
        raise exception 'ngày kết thúc lặp phải sau ngày bắt đầu';
    end if;

    v_day := v_first;
    while v_day <= p_until loop
        if not (p_every = 'day' and extract(isodow from v_day) = 7) then
            v_count := v_count + 1;
            if v_count > 62 then
                raise exception 'lặp quá 62 lần, hãy chọn ngày kết thúc sớm hơn';
            end if;
            v_shift := (v_day - v_first) * interval '1 day';
            v_id := mgr_assign_work_plan(
                payload || jsonb_build_object('from_at', v_from + v_shift, 'to_at', v_to + v_shift)
            );
            update work_plan set repeat_group = v_group where id = v_id;
        end if;
        v_day := v_day + case p_every when 'day' then 1 else 7 end;
    end loop;

    return v_count;
end;
$$;

revoke execute on function mgr_assign_work_plan_repeat(jsonb, text, date) from public, anon;
grant execute on function mgr_assign_work_plan_repeat(jsonb, text, date) to authenticated;
