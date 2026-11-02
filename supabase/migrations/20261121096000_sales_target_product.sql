-- =============================================================================
-- Monthly sales targets by product group or by product — the legacy
-- OM_KPISales_Class (ClassID) and OM_KPISales_Invt (InvtID).
--
-- sales_target holds the one revenue figure per rep and month; this holds the
-- finer ones head office sets beside it ("Bánh kẹo 40 triệu", "Coca-Cola 330ml
-- 15 triệu"). One row names a group or a product, never both: a group target
-- already covers its products, and counting a sale against both would make the
-- two lines on the manager's overview add up to more than was sold.
--
-- Revenue only, in VND, as the overview reads it against order line amounts.
-- =============================================================================

create table sales_target_product (
    id              uuid primary key default gen_random_uuid(),
    salesperson_id  uuid        not null references salesperson (id),
    period_month    date        not null check (period_month = date_trunc('month', period_month)::date),
    category_id     uuid        references product_category (id),
    product_id      uuid        references product (id),
    target_amount   bigint      not null check (target_amount > 0),
    created_at      timestamptz not null default now(),
    check ((category_id is null) <> (product_id is null))
);

-- One figure per rep, month and group (or product); a second would be a typo.
create unique index sales_target_product_category_uq
    on sales_target_product (salesperson_id, period_month, category_id)
    where category_id is not null;
create unique index sales_target_product_product_uq
    on sales_target_product (salesperson_id, period_month, product_id)
    where product_id is not null;

alter table sales_target_product enable row level security;

-- A rep reads their own; a manager reads their team's. Writes come from head
-- office tooling with the service role, as sales_target's do.
create policy sales_target_product_read
    on sales_target_product for select to authenticated
    using (salesperson_id = current_salesperson_id() or is_team_member(salesperson_id));
