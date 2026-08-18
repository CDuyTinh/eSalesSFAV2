-- =============================================================================
-- Daily sales targets
--
-- Before the round, the rep decides how much they intend to sell at each outlet
-- on today's route. The sum is the day they are setting out to have.
--
-- This is not the figure the Overview tab deliberately refuses to show. That one
-- would have been a monthly target divided by its days — a number nobody agreed
-- to, used to call a rep behind. This is the opposite direction: the rep enters
-- it themselves, per outlet, and it is their own plan rather than a quota derived
-- for them. Same words, opposite provenance, which is the whole difference.
-- =============================================================================

create table daily_sales_target (
    id              uuid   primary key default gen_random_uuid(),
    salesperson_id  uuid   not null references salesperson (id) on delete cascade,
    customer_id     uuid   not null references customer (id) on delete cascade,
    branch_id       uuid   not null references branch (id),

    target_date     date   not null,
    target_amount   bigint not null default 0
        constraint target_not_negative check (target_amount >= 0),

    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    -- One plan per outlet per day. The rep revises before setting out rather
    -- than stacking a second figure on top of the first.
    unique (salesperson_id, customer_id, target_date)
);

create index on daily_sales_target (salesperson_id, target_date);

create trigger daily_sales_target_set_updated_at
    before update on daily_sales_target
    for each row execute function set_updated_at();

alter table daily_sales_target enable row level security;

-- Wholly the rep's own, on every verb. A plan is not a record of what happened,
-- so unlike a receipt it stays editable — right up until it stops being about
-- today, which the unique key already handles.
create policy "rep reads own targets"
    on daily_sales_target for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own targets"
    on daily_sales_target for insert to authenticated
    with check (
        salesperson_id = current_salesperson_id()
        and branch_id = current_branch_id()
    );

create policy "rep revises own targets"
    on daily_sales_target for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- -----------------------------------------------------------------------------
-- Today's plan, with something to base it on
--
-- Every stop on today's route, whatever target is already saved against it, and
-- what the rep last sold there. That last figure is the point: a rep setting a
-- number for an outlet they visit fortnightly cannot hold twenty of them in their
-- head, and a blank field invites either a guess or a zero.
--
-- It is scoped to the caller's own orders, because sales_order is. So it means
-- "the last time you sold here", not the outlet's history — and the screen says
-- exactly that rather than implying a fuller picture than RLS can give.
-- -----------------------------------------------------------------------------

create or replace function daily_sales_targets(p_date date)
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_sp      uuid     := current_salesperson_id();
    v_weekday smallint := extract(isodow from p_date)::smallint;
begin
    if v_sp is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    return coalesce((
        select jsonb_agg(row order by (row ->> 'visit_order')::integer)
          from (
                select jsonb_build_object(
                           'customer_id',   c.id,
                           'customer_code', c.code,
                           'customer_name', c.name,
                           'address',       c.address,
                           'visit_order',   rc.visit_order,
                           'target',        coalesce(t.target_amount, 0),
                           'has_target',    t.id is not null,
                           'last_amount',   o.total_amount,
                           'last_date',     o.order_date
                       ) as row
                  from route_customer rc
                  join sales_route r on r.id = rc.sales_route_id
                  join customer c on c.id = rc.customer_id
                  left join daily_sales_target t
                         on t.customer_id = c.id
                        and t.salesperson_id = v_sp
                        and t.target_date = p_date
                  -- The most recent order this rep took at this outlet, if any.
                  left join lateral (
                        select so.total_amount, so.order_date
                          from sales_order so
                         where so.customer_id = c.id
                           and so.salesperson_id = v_sp
                           and so.status <> 'cancelled'
                         order by so.order_date desc
                         limit 1
                       ) o on true
                 where r.salesperson_id = v_sp
                   and r.is_active
                   and rc.is_active
                   and v_weekday = any (rc.visit_weekdays)
               ) per_stop
    ), '[]'::jsonb);
end;
$$;

revoke execute on function daily_sales_targets(date) from public;
grant execute on function daily_sales_targets(date) to authenticated;
