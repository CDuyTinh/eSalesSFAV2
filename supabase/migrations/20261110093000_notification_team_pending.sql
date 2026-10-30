-- =============================================================================
-- Manager notifications (legacy API_GetPPC_Notify_Manager)
--
-- The legacy manager's bell lists what the team has sent up for approval:
-- leave, new outlets, display / loyalty / POSM registrations, work plans. Here
-- they join the same notifications_for() feed the rep's bell reads, under four
-- team_* kinds, with read state in the same notification_read table.
--
-- They come from a security-definer function because the supervisor's RLS
-- does not open the trade registration tables; it is scoped by
-- team_salesperson_ids() itself, so a rep gets nothing from it.
--
-- notifications_for() is otherwise unchanged, except that the supervisor's own
-- coaching days (written and approved in one statement by mgr_edac_start) no
-- longer announce themselves as "approved".
-- =============================================================================

create or replace function team_pending_notifications()
returns table(kind notification_kind, source_id uuid, title text, body text, code text, from_date date, to_date date)
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with team as (select team_salesperson_ids() as id)
    select 'team_leave'::notification_kind, r.id,
           'Đơn nghỉ phép: ' || s.full_name,
           t.name || ' · ' || to_char(r.from_date, 'DD/MM') || ' – ' || to_char(r.to_date, 'DD/MM')
               || coalesce(' · ' || nullif(btrim(r.reason), ''), ''),
           s.code, (r.created_at at time zone 'Asia/Ho_Chi_Minh')::date, (r.created_at at time zone 'Asia/Ho_Chi_Minh')::date
    from leave_request r
    join salesperson s on s.id = r.salesperson_id
    join leave_type t on t.id = r.leave_type_id
    where r.status = 'pending' and r.salesperson_id in (select id from team)

    union all

    select 'team_customer', c.id,
           'Khách hàng mới chờ duyệt: ' || c.name,
           'Do ' || s.full_name || ' đăng ký' || coalesce(' · ' || c.address, ''),
           c.code, (c.created_at at time zone 'Asia/Ho_Chi_Minh')::date, (c.created_at at time zone 'Asia/Ho_Chi_Minh')::date
    from customer c
    join salesperson s on s.id = c.created_by_salesperson_id
    where c.approval_status = 'pending' and c.created_by_salesperson_id in (select id from team)

    union all

    select 'team_trade', d.id,
           'Đăng ký trưng bày: ' || cu.name,
           p.code || ' – ' || p.name || ' · ' || s.full_name,
           cu.code, (d.created_at at time zone 'Asia/Ho_Chi_Minh')::date, (d.created_at at time zone 'Asia/Ho_Chi_Minh')::date
    from display_registration d
    join display_program p on p.id = d.program_id
    join customer cu on cu.id = d.customer_id
    join salesperson s on s.id = trade_registration_rep(d.salesperson_id, d.customer_id)
    where d.status = 'pending' and s.id in (select id from team)

    union all

    select 'team_trade', l.id,
           'Đăng ký tích lũy: ' || cu.name,
           p.code || ' – ' || p.name || ' · ' || s.full_name,
           cu.code, (l.created_at at time zone 'Asia/Ho_Chi_Minh')::date, (l.created_at at time zone 'Asia/Ho_Chi_Minh')::date
    from loyalty_registration l
    join loyalty_program p on p.id = l.program_id
    join customer cu on cu.id = l.customer_id
    join salesperson s on s.id = trade_registration_rep(l.salesperson_id, l.customer_id)
    where l.status = 'pending' and s.id in (select id from team)

    union all

    select 'team_trade', pr.id,
           'Đăng ký POSM: ' || cu.name,
           p.code || ' – ' || p.name || ' · SL ' || pr.regis_qty || ' · ' || s.full_name,
           cu.code, (pr.created_at at time zone 'Asia/Ho_Chi_Minh')::date, (pr.created_at at time zone 'Asia/Ho_Chi_Minh')::date
    from posm_registration pr
    join posm_program p on p.id = pr.program_id
    join customer cu on cu.id = pr.customer_id
    join salesperson s on s.id = trade_registration_rep(pr.salesperson_id, pr.customer_id)
    where pr.status = 'pending' and s.id in (select id from team)

    union all

    select 'team_work_plan', w.id,
           'Kế hoạch chờ duyệt: ' || s.full_name,
           w.content || ' · ' || to_char(w.from_at at time zone 'Asia/Ho_Chi_Minh', 'DD/MM HH24:MI'),
           null::text,
           (coalesce(w.submitted_at, w.created_at) at time zone 'Asia/Ho_Chi_Minh')::date,
           (coalesce(w.submitted_at, w.created_at) at time zone 'Asia/Ho_Chi_Minh')::date
    from work_plan w
    join salesperson s on s.id = w.salesperson_id
    where w.status = 'submitted' and w.salesperson_id in (select id from team);
$$;

revoke execute on function team_pending_notifications() from public, anon;
grant execute on function team_pending_notifications() to authenticated;

CREATE OR REPLACE FUNCTION public.notifications_for(p_from_date date DEFAULT (CURRENT_DATE - 30), p_to_date date DEFAULT CURRENT_DATE, p_unread_only boolean DEFAULT false)
 RETURNS TABLE(kind notification_kind, source_id uuid, title text, body text, code text, from_date date, to_date date, is_read boolean, read_at timestamp with time zone)
 LANGUAGE sql
 STABLE
 SET search_path TO 'public', 'pg_temp'
AS $function$
    with me as (
        select id, branch_id from salesperson where id = current_salesperson_id()
    ),
    feed as (
        -- AUTOPROMO. One row per programme rather than per sequence: a rep wants
        -- to know the campaign is running, not that it has four break levels.
        select
            'promotion'::notification_kind as kind,
            p.id as source_id,
            p.name as title,
            'Chương trình khuyến mãi đang chạy' as body,
            p.code,
            min(s.from_date) as from_date,
            max(case when s.open_ended then null else s.to_date end) as to_date
        from discount_program p
        join discount_sequence s on s.program_id = p.id and s.is_active
        cross join me
        where p.is_active
          and (s.branch_id is null or s.branch_id = me.branch_id)
        group by p.id, p.name, p.code

        union all

        -- HANDPROMO.
        select
            'manual_promotion', m.id, m.name,
            'Khuyến mãi áp dụng bằng tay', m.code, m.from_date, m.to_date
        from manual_promotion m
        cross join me
        where m.is_active
          and (m.branch_id is null or m.branch_id = me.branch_id)

        union all

        select
            'display', d.id, d.name,
            case when d.requires_registration
                 then 'Chương trình trưng bày - cần đăng ký'
                 else 'Chương trình trưng bày' end,
            d.code, d.from_date, d.to_date
        from display_program d
        cross join me
        where d.is_active and (d.branch_id is null or d.branch_id = me.branch_id)

        union all

        select
            'loyalty', l.id, l.name, 'Chương trình tích lũy',
            l.code, l.from_date, l.to_date
        from loyalty_program l
        cross join me
        where l.is_active and (l.branch_id is null or l.branch_id = me.branch_id)

        union all

        select
            'posm', pr.id, pr.name, 'Chương trình POSM',
            pr.code, pr.from_date, pr.to_date
        from posm_program pr
        cross join me
        where pr.is_active and (pr.branch_id is null or pr.branch_id = me.branch_id)

        union all

        -- NOTE. The rep's own, so no branch test: a note belongs to whoever
        -- wrote it and nobody else sees it anyway. Open ones only — a note the
        -- rep has already ticked off is a record, not something still to tell.
        select
            'work_note', w.id, w.title,
            coalesce(nullif(btrim(w.body), ''), 'Ghi chú công việc'),
            null::text, w.due_on, w.due_on
        from work_note w
        where w.salesperson_id = (select id from me)
          and w.status = 'open'

        union all

        -- LEAVEAPPROVALRESULT. Dated by the decision, so the result arrives in
        -- the bell on the day it was made, not on the first day of the leave.
        select
            'leave_result', lr.id,
            case lr.status when 'approved' then 'Đơn nghỉ phép đã được duyệt'
                           else 'Đơn nghỉ phép bị từ chối' end,
            lt.name || ' ' || to_char(lr.from_date, 'DD/MM')
                || case when lr.to_date <> lr.from_date
                        then '–' || to_char(lr.to_date, 'DD/MM') else '' end
                || coalesce(' · ' || nullif(btrim(lr.decision_note), ''), ''),
            null::text,
            (lr.decided_at at time zone 'Asia/Ho_Chi_Minh')::date,
            (lr.decided_at at time zone 'Asia/Ho_Chi_Minh')::date
        from leave_request lr
        join leave_type lt on lt.id = lr.leave_type_id
        where lr.salesperson_id = (select id from me)
          and lr.status in ('approved', 'rejected')
          and lr.decided_at is not null

        union all

        -- CUSTOMERAPPROVALRESULT. Only outlets someone actually ruled on:
        -- rows head office loaded are approved by default and were never news.
        select
            'customer_result', c.id,
            case c.approval_status when 'approved' then 'Khách hàng mới đã được duyệt'
                                   else 'Khách hàng mới bị từ chối' end,
            c.name || coalesce(' · ' || nullif(btrim(c.decision_note), ''), ''),
            c.code,
            (c.decided_at at time zone 'Asia/Ho_Chi_Minh')::date,
            (c.decided_at at time zone 'Asia/Ho_Chi_Minh')::date
        from customer c
        where c.created_by_salesperson_id = (select id from me)
          and c.approval_status in ('approved', 'rejected')
          and c.decided_at is not null

        union all

        -- VISIBILITY / LOYALTY / POSM APPROVALRESULT, one kind for the three.
        select
            'trade_result', t.id,
            'Đăng ký ' || t.family || ' '
                || case t.status when 'approved' then 'đã được duyệt' else 'bị từ chối' end,
            cu.customer_name || ' · ' || t.program_name
                || coalesce(' · ' || nullif(btrim(t.decision_note), ''), ''),
            t.program_code,
            (t.decided_at at time zone 'Asia/Ho_Chi_Minh')::date,
            (t.decided_at at time zone 'Asia/Ho_Chi_Minh')::date
        from (
            select d.id, 'trưng bày' as family, d.status, d.decision_note, d.decided_at,
                   d.salesperson_id, d.customer_id, p.code as program_code, p.name as program_name
            from display_registration d join display_program p on p.id = d.program_id
            union all
            select l.id, 'tích lũy', l.status, l.decision_note, l.decided_at,
                   l.salesperson_id, l.customer_id, p.code, p.name
            from loyalty_registration l join loyalty_program p on p.id = l.program_id
            union all
            select r.id, 'POSM', r.status, r.decision_note, r.decided_at,
                   r.salesperson_id, r.customer_id, p.code, p.name
            from posm_registration r join posm_program p on p.id = r.program_id
        ) t
        cross join lateral (
            select c.name as customer_name from customer c where c.id = t.customer_id
        ) cu
        where t.decided_at is not null
          and t.status in ('approved', 'rejected')
          and trade_registration_rep(t.salesperson_id, t.customer_id) = (select id from me)

        union all

        -- Kế hoạch / công việc. Two events on one kind, never on the same plan:
        -- a plan the supervisor wrote for the rep arrives as "được giao" on the
        -- day it was assigned; a plan the rep wrote arrives when it is ruled on.
        select
            'work_plan', wp.id,
            case
                when wp.author_id is distinct from wp.salesperson_id then 'Công việc mới được giao'
                when wp.status = 'rejected' then 'Kế hoạch bị từ chối'
                else 'Kế hoạch đã được duyệt'
            end,
            wc.name || ' · ' || wp.content
                || ' · ' || to_char(wp.from_at at time zone 'Asia/Ho_Chi_Minh', 'DD/MM HH24:MI')
                || case when wp.status = 'rejected'
                        then coalesce(' · ' || nullif(btrim(wp.reason), ''), '') else '' end,
            null::text,
            (coalesce(case when wp.author_id is distinct from wp.salesperson_id then wp.created_at end,
                      wp.rejected_at, wp.approved_at) at time zone 'Asia/Ho_Chi_Minh')::date,
            (coalesce(case when wp.author_id is distinct from wp.salesperson_id then wp.created_at end,
                      wp.rejected_at, wp.approved_at) at time zone 'Asia/Ho_Chi_Minh')::date
        from work_plan wp
        join work_with_category wc on wc.id = wp.category_id
        where wp.salesperson_id = (select id from me)
          and (
              -- Assigned by somebody else and still something to do.
              (wp.author_id is not null and wp.author_id <> wp.salesperson_id
               and wp.status in ('approved', 'submitted'))
              or
              -- The rep's own plan, ruled on.
              (wp.author_id = wp.salesperson_id
               and ((wp.status = 'rejected' and wp.rejected_at is not null)
                    or (wp.status = 'approved' and wp.approved_at is not null)))
          )
          -- Not the supervisor's own coaching days, written and approved in one
          -- go by mgr_edac_start: nobody ruled on those, so there is no news.
          and not (wp.author_id = wp.salesperson_id and wp.approved_at = wp.created_at)

        union all

        -- What the team has sent up and is waiting on the caller for. Empty for a
        -- rep, whose team is nobody.
        -- No end date: a request still waiting stays in the window until ruled on.
        select t.kind, t.source_id, t.title, t.body, t.code, t.from_date, null::date
        from team_pending_notifications() t
    )
    select
        f.kind, f.source_id, f.title, f.body, f.code, f.from_date, f.to_date,
        r.id is not null as is_read,
        r.read_at
    from feed f
    left join notification_read r
        on r.kind = f.kind
       and r.source_id = f.source_id
       and r.salesperson_id = (select id from me)
    where
        -- Overlapping the window rather than starting in it: a campaign that
        -- began last month and runs until next is news to a rep who has not
        -- read it yet, and the legacy's own date test overlaps the same way.
        coalesce(f.from_date, p_from_date) <= p_to_date
        and coalesce(f.to_date, p_to_date) >= p_from_date
        and (not p_unread_only or r.id is null)
    order by f.from_date desc nulls last, f.title;
$function$
;
