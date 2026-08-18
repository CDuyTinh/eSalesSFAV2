-- =============================================================================
-- Focus products
--
-- The SKUs head office wants pushed this period, and how far the rep has got
-- with them.
--
-- Deliberately not the trade-programme engine the legacy screen sits on top of.
-- That one carries qualification tiers, rewards, sample display photos and a
-- registration flow, none of which exist here and none of which can be designed
-- from the outside — inventing what earns a rep a tier would be inventing
-- somebody's commercial policy. This is the part of that screen that stands on
-- its own: which products matter now, and how each rep is doing on them.
--
-- Reference data, so it is loaded by head office and read by everyone, exactly
-- like price_list and msl.
-- =============================================================================

create table focus_product (
    id              uuid    primary key default gen_random_uuid(),
    product_id      uuid    not null references product (id) on delete cascade,

    /** Null means every branch. A push is usually national; this is the exception. */
    branch_id       uuid    references branch (id),

    from_date       date    not null,
    to_date         date    not null,

    /** Lower sorts first, so head office controls what the rep reads at the top. */
    priority        integer not null default 0,

    /**
     * What one outlet is expected to take, in base units. Null when the push is
     * qualitative — "get it on the shelf" — which is a real kind of instruction
     * and not the same as a target of zero.
     */
    target_base_qty integer
        constraint focus_target_positive check (target_base_qty is null or target_base_qty > 0),

    /** What head office wants the rep to actually say at the counter. */
    note            text,

    is_active       boolean not null default true,
    created_at      timestamptz not null default now(),

    constraint focus_period_valid check (to_date >= from_date)
);

create index on focus_product (from_date, to_date);

alter table focus_product enable row level security;

create policy "reference readable by authenticated"
    on focus_product for select to authenticated using (true);

comment on table focus_product is
    'SKUs head office is pushing in a period. Loaded by head office; the app only reads.';

-- -----------------------------------------------------------------------------
-- The briefing
--
-- Everything in force on the given day, with the rep's own progress against it.
--
-- Progress is base units sold this period by this rep, which is the only figure
-- RLS lets the app see and also the only one the rep is answerable for. It is
-- summed over sales_order_line.base_qty rather than counting orders: two boxes
-- and three bottles is a quantity, and the whole point of the base unit is that
-- it can be added up.
-- -----------------------------------------------------------------------------

create or replace function focus_products(p_date date)
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_sp uuid := current_salesperson_id();
begin
    if v_sp is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    return coalesce((
        select jsonb_agg(row order by (row ->> 'priority')::integer,
                              row ->> 'product_name')
          from (
                select jsonb_build_object(
                           'focus_id',        f.id,
                           'product_id',      p.id,
                           'product_code',    p.code,
                           'product_name',    p.name,
                           'base_uom',        p.base_uom,
                           'from_date',       f.from_date,
                           'to_date',         f.to_date,
                           'priority',        f.priority,
                           'target_base_qty', f.target_base_qty,
                           'note',            f.note,
                           'sold_base_qty',   coalesce(s.sold, 0),
                           -- How many of the rep's own outlets have taken it at
                           -- all. A rep can hit a quantity target through one big
                           -- order while the product reaches nobody, and coverage
                           -- is the half of the push that says so.
                           'outlets',         coalesce(s.outlets, 0)
                       ) as row
                  from focus_product f
                  join product p on p.id = f.product_id
                  left join lateral (
                        select sum(l.base_qty)                as sold,
                               count(distinct o.customer_id)  as outlets
                          from sales_order o
                          join sales_order_line l on l.order_id = o.id
                         where o.salesperson_id = v_sp
                           and o.status <> 'cancelled'
                           and l.product_id = f.product_id
                           and o.order_date between f.from_date and f.to_date
                       ) s on true
                 where f.is_active
                   and p.is_active
                   and p_date between f.from_date and f.to_date
                   and (f.branch_id is null or f.branch_id = current_branch_id())
               ) per_focus
    ), '[]'::jsonb);
end;
$$;

revoke execute on function focus_products(date) from public;
grant execute on function focus_products(date) to authenticated;
