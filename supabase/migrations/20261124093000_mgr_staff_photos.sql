-- =============================================================================
-- eSalesManager: a rep's photos for one day
--
-- What the rep photographed on the day, in the order it happened: the punch
-- that opened the day, each visit's shop front at check-in and at check-out,
-- the punch that closed it. Storage paths only; mgr-reports signs them (the
-- "manager reads team visit photos" policy is what lets it).
--
-- Rows without a photo are returned too, with a null path, so the screen can
-- say a visit had none rather than leave it out.
-- =============================================================================

create or replace function mgr_staff_photos(p_salesperson_id uuid, p_date date)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
begin
    if not is_team_member(p_salesperson_id) then
        raise exception 'nhân viên không thuộc nhóm của bạn';
    end if;

    return jsonb_build_object(
        'punches', coalesce((
            select jsonb_agg(jsonb_build_object(
                'type', t.type,
                'at', t.happened_at,
                'photo_path', t.photo_path
            ) order by t.happened_at)
            from timekeeping t
            where t.salesperson_id = p_salesperson_id and t.work_date = p_date
        ), '[]'::jsonb),
        'visits', coalesce((
            select jsonb_agg(jsonb_build_object(
                'visit_id', v.id,
                'customer_code', c.code,
                'customer_name', c.name,
                'check_in_at', v.check_in_at,
                'check_out_at', v.check_out_at,
                'check_in_photo_path', v.check_in_photo_path,
                'check_out_photo_path', v.check_out_photo_path
            ) order by v.check_in_at)
            from visit v
            join customer c on c.id = v.customer_id
            where v.salesperson_id = p_salesperson_id and v.visit_date = p_date
        ), '[]'::jsonb)
    );
end;
$$;

revoke execute on function mgr_staff_photos(uuid, date) from public, anon;
grant execute on function mgr_staff_photos(uuid, date) to authenticated;
