-- =============================================================================
-- The basket is data, not screen state
--
-- Đặt hàng built its basket in a ViewModel and sent nothing until the rep
-- pressed "Gửi đơn hàng". Backing out of the step, or Android reclaiming the
-- process, threw away everything typed — which is a whole shop's order, entered
-- standing at a counter.
--
-- The app this replaces does not do that. Its basket is a table, OM_PDACart,
-- keyed (SlsperId, CustId, BranchID, InvtID, Unit), written on every "cập nhật
-- giỏ hàng" through InsertCartItem/UpdateCartItem and read back by
-- API_GetCartItem. API_GetProductOrd joins it too, which is how the legacy
-- product list can print the quantity already in the basket beside each row.
--
-- This is that table. Branch is absent because salesperson_id implies it here,
-- and it is keyed per (rep, customer) rather than per visit for the same reason
-- the legacy key is: a rep who backs out and comes back to the same shop should
-- find their basket, and a shop may now be called on twice in a day.
--
-- No price column, unlike the legacy row. `submit_order` prices the order from
-- the effective-dated catalogue when it is booked, and the client prices the
-- screen from the same catalogue; a third copy sitting in the basket could only
-- ever disagree with those two.
-- =============================================================================

create table if not exists order_cart (
    salesperson_id uuid        not null references salesperson (id) on delete cascade,
    customer_id    uuid        not null references customer (id) on delete cascade,
    product_id     uuid        not null references product (id) on delete cascade,
    uom_code       text        not null,
    -- A zero-quantity basket line is a line the rep removed. It is deleted, not
    -- stored as 0, so "in the basket" needs no second condition to test.
    qty            integer     not null check (qty > 0),
    updated_at     timestamptz not null default now(),

    primary key (salesperson_id, customer_id, product_id, uom_code)
);

create trigger order_cart_set_updated_at
    before update on order_cart
    for each row execute function set_updated_at();

alter table order_cart enable row level security;

-- A basket is the rep's own working note. Nobody else reads it, including
-- colleagues covering the same route: two reps with two baskets for one shop is
-- exactly what the primary key already allows, and merging them would be
-- guessing at which quantities the customer actually agreed to.
create policy "rep reads own cart" on order_cart
    for select using (salesperson_id = current_salesperson_id());

create policy "rep writes own cart" on order_cart
    for insert with check (salesperson_id = current_salesperson_id());

create policy "rep updates own cart" on order_cart
    for update using (salesperson_id = current_salesperson_id());

create policy "rep clears own cart" on order_cart
    for delete using (salesperson_id = current_salesperson_id());

-- -----------------------------------------------------------------------------
-- Replacing a basket
--
-- One statement rather than a delete and an insert from the client: the basket
-- the rep is looking at is the truth, and a half-applied update would leave them
-- looking at something else.
--
-- The whole basket is posted every time. The legacy endpoint takes a list of
-- changed units and merges, which means a line dropped on the device has to be
-- sent as a qty of zero and cannot simply be absent — a rule that is easy to get
-- wrong and silent when you do.
-- -----------------------------------------------------------------------------

create or replace function set_order_cart(p_customer_id uuid, p_items jsonb)
returns integer
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_sp_id uuid := current_salesperson_id();
    v_kept  integer;
begin
    if v_sp_id is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    with input as (
        select
            (i ->> 'product_id')::uuid as product_id,
            (i ->> 'uom_code')::text   as uom_code,
            (i ->> 'qty')::integer     as qty
        from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) as i
    ),
    -- Defensive rather than trusting: a zero arriving from a client that
    -- computed a removal the long way should drop the line, not violate the
    -- check constraint and lose the whole basket.
    wanted as (
        select * from input where qty > 0
    ),
    gone as (
        delete from order_cart c
        where c.salesperson_id = v_sp_id
          and c.customer_id = p_customer_id
          and not exists (
              select 1 from wanted w
              where w.product_id = c.product_id and w.uom_code = c.uom_code
          )
        returning 1
    ),
    upserted as (
        insert into order_cart (salesperson_id, customer_id, product_id, uom_code, qty)
        select v_sp_id, p_customer_id, product_id, uom_code, qty
        from wanted
        on conflict (salesperson_id, customer_id, product_id, uom_code)
            do update set qty = excluded.qty
        returning 1
    )
    select (select count(*) from upserted) into v_kept;

    return v_kept;
end;
$$;

comment on function set_order_cart(uuid, jsonb) is
    'Replaces this rep''s basket for one customer with the posted lines.';

-- -----------------------------------------------------------------------------
-- Emptying it when the order is booked
--
-- A trigger rather than a line inside submit_order, so the two stay independent:
-- any path that books an order for a customer empties that customer''s basket,
-- and submit_order does not have to remember to. Same transaction either way —
-- an order that rolls back leaves the basket where the rep left it.
-- -----------------------------------------------------------------------------

create or replace function clear_cart_after_order()
returns trigger
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
begin
    delete from order_cart
    where salesperson_id = new.salesperson_id
      and customer_id = new.customer_id;

    return new;
end;
$$;

create trigger sales_order_clears_cart
    after insert on sales_order
    for each row execute function clear_cart_after_order();
