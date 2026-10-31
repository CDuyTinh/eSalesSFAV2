-- =============================================================================
-- eSalesManager G9 — quản lý tiêu chí EDAC
--
-- The legacy kept the EDAC criteria in head-office screens (OM20130). Here an
-- AM or RM edits them in the manager app; an SS reads them. Past coaching days
-- keep their meaning: once a criterion has answers its kind is fixed and the
-- option values those answers point at cannot be removed (their wording can
-- change). Retiring a criterion is switching it off, not deleting it.
-- =============================================================================

create or replace function mgr_edac_criteria()
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select jsonb_build_object(
        'can_edit', (select position in ('am', 'rm') from salesperson where id = current_salesperson_id()),
        'criteria', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', c.id, 'step', c.step, 'code', c.code, 'content', c.content, 'kind', c.kind,
                'kpi', c.kpi, 'required', c.is_required, 'active', c.is_active, 'sort_order', c.sort_order,
                'answered', exists (select 1 from edac_answer a where a.criterion_id = c.id),
                'options', (select coalesce(jsonb_agg(jsonb_build_object('value', o.value, 'label', o.label) order by o.value), '[]')
                            from edac_criterion_option o where o.criterion_id = c.id)
            ) order by c.step, c.sort_order, c.code)
            from edac_criterion c
        ), '[]'::jsonb)
    );
$$;

/**
 * payload: { id?, step, content, kind, kpi?, required, active,
 *            options: [{ value, label }] }  — options only for kind 'scale'.
 * Without id a new criterion is appended to the step.
 */
create or replace function mgr_edac_save_criterion(payload jsonb)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_id       uuid := (payload ->> 'id')::uuid;
    v_step     smallint := (payload ->> 'step')::smallint;
    v_kind     text := payload ->> 'kind';
    v_content  text := nullif(btrim(coalesce(payload ->> 'content', '')), '');
    v_old      edac_criterion;
    v_answered boolean := false;
    v_options  jsonb := coalesce(payload -> 'options', '[]'::jsonb);
    v_missing  smallint;
begin
    if (select position from salesperson where id = current_salesperson_id()) not in ('am', 'rm') then
        raise exception 'chỉ quản lý khu vực / vùng được sửa tiêu chí EDAC';
    end if;
    if v_content is null then
        raise exception 'nhập nội dung tiêu chí';
    end if;
    if v_kind not in ('scale', 'yes_no', 'text') then
        raise exception 'kiểu tiêu chí không hợp lệ';
    end if;
    if v_step not in (0, 1, 2, 4, 5, 6) then
        raise exception 'bước không có tiêu chí';
    end if;
    if v_kind = 'scale' and (jsonb_array_length(v_options) < 2
        or exists (select 1 from jsonb_array_elements(v_options) o
                   where nullif(btrim(o ->> 'label'), '') is null or (o ->> 'value')::smallint is null)
        or (select count(distinct (o ->> 'value')::smallint) from jsonb_array_elements(v_options) o) <> jsonb_array_length(v_options)) then
        raise exception 'thang điểm cần ít nhất 2 mức, mỗi mức có số và mô tả riêng';
    end if;

    if v_id is not null then
        select * into v_old from edac_criterion where id = v_id for update;
        if not found then raise exception 'không tìm thấy tiêu chí'; end if;
        v_answered := exists (select 1 from edac_answer where criterion_id = v_id);
        if v_answered and v_old.kind <> v_kind then
            raise exception 'tiêu chí đã có câu trả lời, không đổi được kiểu';
        end if;
        if v_answered and v_kind = 'scale' then
            select a.option_value into v_missing from edac_answer a
            where a.criterion_id = v_id and a.option_value is not null
              and a.option_value not in (select (o ->> 'value')::smallint from jsonb_array_elements(v_options) o)
            limit 1;
            if v_missing is not null then
                raise exception 'mức % đã được dùng trong câu trả lời, không xoá được', v_missing;
            end if;
        end if;
        update edac_criterion
        set content = v_content, kind = v_kind, kpi = nullif(btrim(coalesce(payload ->> 'kpi', '')), ''),
            is_required = coalesce((payload ->> 'required')::boolean, true),
            is_active = coalesce((payload ->> 'active')::boolean, true)
        where id = v_id;
    else
        insert into edac_criterion (step, code, content, kind, kpi, is_required, is_active, sort_order)
        select v_step,
               lpad((coalesce(max(code::integer), 0) + 1)::text, 5, '0'),
               v_content, v_kind, nullif(btrim(coalesce(payload ->> 'kpi', '')), ''),
               coalesce((payload ->> 'required')::boolean, true),
               coalesce((payload ->> 'active')::boolean, true),
               coalesce(max(sort_order), 0) + 1
        from edac_criterion where step = v_step and code ~ '^[0-9]+$'
        returning id into v_id;
    end if;

    delete from edac_criterion_option where criterion_id = v_id;
    if v_kind = 'scale' then
        insert into edac_criterion_option (criterion_id, value, label)
        select v_id, (o ->> 'value')::smallint, btrim(o ->> 'label') from jsonb_array_elements(v_options) o;
    end if;
    return v_id;
end;
$$;

revoke execute on function mgr_edac_criteria() from public, anon;
revoke execute on function mgr_edac_save_criterion(jsonb) from public, anon;
grant execute on function mgr_edac_criteria() to authenticated;
grant execute on function mgr_edac_save_criterion(jsonb) to authenticated;
