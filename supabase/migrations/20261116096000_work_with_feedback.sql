-- =============================================================================
-- Phản hồi khách hàng on a work-with call (legacy manager FEED_BACK_CUSTOMER).
--
-- The supervisor's call is their own visit row, so submit_feedback takes it as
-- it is: the same topics, the same note and photo rules from the feedback
-- step's config, the same tables. What the manager app needs besides is the
-- form — topics and rules, and what was already filed on this call — and the
-- call's summary saying the step is done.
-- =============================================================================

/** The feedback form for a work-with call: topics, the step's rules, any earlier answer. */
create or replace function mgr_feedback_form(p_visit_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v visit := own_work_with_visit(p_visit_id);
    v_config jsonb := coalesce((select s.config from sales_step s where s.form_id = 'feedback'), '{}'::jsonb);
begin
    return jsonb_build_object(
        'topics', coalesce((select jsonb_agg(jsonb_build_object('id', r.id, 'name', r.name) order by r.name)
                            from reason_code r where r.kind = 'feedback_topic' and r.is_active), '[]'::jsonb),
        'note_min_length', coalesce((v_config ->> 'note_min_length')::integer, 1),
        'photo_min', coalesce((v_config ->> 'photo_min')::integer, 0),
        'photo_max', coalesce((v_config ->> 'photo_max')::integer, 5),
        'filed', (select jsonb_build_object('topic_id', f.topic_id, 'note', f.note, 'photos', f.photo_count)
                  from visit_feedback f where f.visit_id = v.id)
    );
end;
$$;

revoke execute on function mgr_feedback_form(uuid) from public, anon;
grant execute on function mgr_feedback_form(uuid) to authenticated;

/** What the supervisor has done on this call so far, for the visit screen. */
create or replace function mgr_visit_tasks(p_visit_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $$
declare
    v visit := own_work_with_visit(p_visit_id);
begin
    return jsonb_build_object(
        'stock_count', (select jsonb_build_object('at', sc.created_at, 'lines', sc.line_count)
                        from stock_count sc where sc.visit_id = v.id),
        'orders', coalesce((select jsonb_agg(jsonb_build_object(
                        'id', o.id, 'order_no', o.order_no, 'total_amount', o.total_amount,
                        'discount_amount', o.discount_amount, 'created_at', o.created_at)
                        order by o.created_at)
                    from sales_order o where o.visit_id = v.id and o.status <> 'cancelled'), '[]'::jsonb),
        'posm_checked', (select count(*) from posm_check where visit_id = v.id),
        'surveys_done', (select count(distinct survey_id) from competitor_survey_result where visit_id = v.id),
        'feedback', exists (select 1 from visit_feedback where visit_id = v.id),
        'min_minutes', coalesce((select value::integer from app_setting where key = 'work_with_min_minutes'), 0)
    );
end;
$$;
