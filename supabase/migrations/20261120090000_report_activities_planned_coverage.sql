-- "Ghé / KH" on the daily activities report is coverage of the day's MCP:
-- of the shops planned today, how many were reached. It counted every shop
-- visited, so a rep whose one visit was off-route read 1/1 (100%) against a
-- one-stop MCP they never touched. Off-route shops have their own figure
-- (unplanned); strike / non-strike stay over every shop visited, since an
-- order taken off-route is still an order.
CREATE OR REPLACE FUNCTION public.report_activities_of(p_sps uuid[], p_date date)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    v_sps       uuid[]   := readable_salespeople(p_sps);
    v_weekday   smallint := extract(isodow from p_date)::smallint;
    v_planned   integer;
    v_summary   jsonb;
    v_rows      jsonb;
    v_members   jsonb;
begin
    select count(*)
      into v_planned
      from route_customer rc
      join sales_route r on r.id = rc.sales_route_id
     where r.salesperson_id = any (v_sps)
       and r.is_active
       and rc.is_active
       and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date);

    with visits as (
        select v.id,
               v.salesperson_id,
               v.customer_id,
               v.status,
               v.check_in_at,
               v.check_out_at,
               c.code    as customer_code,
               c.name    as customer_name,
               c.address as customer_address,
               -- On the visiting rep's MCP for this weekday. An outlet the rep
               -- registered themselves is not, and neither is one visited off-route.
               exists (
                   select 1
                     from route_customer rc
                     join sales_route r on r.id = rc.sales_route_id
                    where r.salesperson_id = v.salesperson_id
                      and rc.customer_id = v.customer_id
                      and r.is_active
                      and rc.is_active
                      and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date)
               ) as planned,
               coalesce((
                   select sum(o.total_amount)
                     from sales_order o
                    where o.visit_id = v.id
                      and o.status <> 'cancelled'
               ), 0) as order_amount
          from visit v
          join customer c on c.id = v.customer_id
         where v.salesperson_id = any (v_sps)
           and v.visit_date = p_date
    )
    select jsonb_build_object(
               'planned',      v_planned,
               -- Every count below is of shops, not of calls (per rep: the same
               -- shop visited by two reps is two shops reached).
               'visited',      count(distinct (salesperson_id, customer_id)) filter (where check_in_at is not null and planned),
               'unplanned',    count(distinct (salesperson_id, customer_id)) filter (where not planned),
               'strike',       count(distinct (salesperson_id, customer_id)) filter (where order_amount > 0),
               'non_strike',   count(distinct (salesperson_id, customer_id)) filter (where check_in_at is not null)
                               - count(distinct (salesperson_id, customer_id)) filter (where order_amount > 0),
               'closed',       count(distinct (salesperson_id, customer_id)) filter (where status = 'closed'),
               'order_amount', coalesce(sum(order_amount), 0)
           ),
           coalesce(
               jsonb_agg(
                   jsonb_build_object(
                       'visit_id',      id,
                       'salesperson',   staff_brief(salesperson_id),
                       'customer_code', customer_code,
                       'customer_name', customer_name,
                       'address',       customer_address,
                       'planned',       planned,
                       'status',        status,
                       'check_in_at',   check_in_at,
                       'check_out_at',  check_out_at,
                       'minutes',       case
                                            when check_in_at is null or check_out_at is null then null
                                            else floor(extract(epoch from (check_out_at - check_in_at)) / 60)::integer
                                        end,
                       'order_amount',  order_amount
                   )
                   order by check_in_at asc nulls last
               ),
               '[]'::jsonb
           )
      into v_summary, v_rows
      from visits;

    -- Per salesperson: the supervisor's "who started when, who reached how many".
    select coalesce(jsonb_agg(m order by (m ->> 'code')), '[]'::jsonb)
      into v_members
      from (
            select jsonb_build_object(
                       'id', s.id, 'code', s.code, 'name', s.full_name,
                       'planned', (select count(*)
                                     from route_customer rc
                                     join sales_route r on r.id = rc.sales_route_id
                                    where r.salesperson_id = s.id and r.is_active and rc.is_active
                                      and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date)),
                       'visited', (select count(distinct v.customer_id) from visit v
                                    where v.salesperson_id = s.id and v.visit_date = p_date
                                      and v.check_in_at is not null
                                      and exists (select 1 from route_customer rc
                                                   join sales_route r on r.id = rc.sales_route_id
                                                  where r.salesperson_id = s.id and rc.customer_id = v.customer_id
                                                    and r.is_active and rc.is_active
                                                    and on_route_day(rc.visit_weekdays, rc.visit_weeks, p_date))),
                       'strike',  (select count(distinct o.customer_id) from sales_order o
                                    where o.salesperson_id = s.id and o.order_date = p_date
                                      and o.status <> 'cancelled'),
                       'order_amount', (select coalesce(sum(o.total_amount), 0) from sales_order o
                                    where o.salesperson_id = s.id and o.order_date = p_date
                                      and o.status <> 'cancelled'),
                       'first_check_in', (select min(v.check_in_at) from visit v
                                    where v.salesperson_id = s.id and v.visit_date = p_date),
                       'last_check_out', (select max(v.check_out_at) from visit v
                                    where v.salesperson_id = s.id and v.visit_date = p_date)
                   ) as m
              from salesperson s
             where s.id = any (v_sps)
           ) per_rep;

    return jsonb_build_object('date', p_date, 'summary', v_summary, 'rows', v_rows, 'members', v_members);
end;
$function$
;
