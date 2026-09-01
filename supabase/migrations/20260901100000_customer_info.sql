-- =============================================================================
-- Customer detail
--
-- The card on the route says which outlet is next. This says what the rep wants
-- to know before walking in: who to ask for, what the shop has bought this
-- month, and how much credit head office allows them.
--
-- Ported from the legacy PPC_GetCustomerInfo_RT, with one rule deliberately left
-- behind. That proc totals sales over the sales cycle in SI_Cycle and subtracts
-- return orders (OrderType FR and IR). This schema has neither a cycle nor a
-- return type, so the figure below follows the rule dashboard_overview already
-- uses: the calendar month, everything not cancelled. Two definitions of
-- "doanh số" in one app would be worse than one less clever definition used
-- everywhere — a rep who adds up customer figures expects the dashboard total.
-- =============================================================================

-- Who the rep asks for at the counter. Legacy AR_Customer.Attn. Null is the
-- normal case, not a gap in the data: most outlets are the owner's name on the
-- sign and nobody else.
alter table customer add column if not exists contact_name text;

-- Legacy AR_Customer.CrLmt, in dong like every other money column here.
--
-- Null and zero are different answers and the screen says so in words. Null is
-- "head office has not set a limit"; zero is "this outlet buys cash only".
-- Collapsing them would tell a rep they may not sell on credit when nobody has
-- actually decided that.
alter table customer add column if not exists credit_limit bigint;

-- -----------------------------------------------------------------------------
-- One outlet, as the detail screen needs it.
--
-- security invoker on purpose: `customer` is scoped by RLS to the rep's own
-- branch and `sales_order` to their own orders, so this function inherits both
-- and carries no branch or salesperson filter of its own. That is the same
-- arrangement every other read here uses — the scoping lives next to the data,
-- not in whichever layer happens to be asking.
--
-- The legacy proc filters orders by @SlsperID explicitly. Repeating that here
-- would be a second copy of a rule the policy already enforces, and the copy is
-- what goes stale.
-- -----------------------------------------------------------------------------
create or replace function customer_info(
    p_customer_id uuid,
    p_on          date default current_date
)
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_month_start date := date_trunc('month', p_on)::date;
    v_month_end   date := (date_trunc('month', p_on) + interval '1 month - 1 day')::date;
    v_result      jsonb;
begin
    select jsonb_build_object(
               'customer_id',    c.id,
               'code',           c.code,
               'name',           c.name,
               'phone',          c.phone,
               'address',        c.address,
               'avatar_url',     c.avatar_url,
               -- The legacy proc substitutes the outlet name when Attn is empty,
               -- which reads on screen as though a contact had been recorded when
               -- none was. Left null here; the screen has room to say so plainly.
               'contact_name',   c.contact_name,
               'channel_name',   ch.name,
               'class_name',     cc.name,
               'shop_type_name', st.name,
               'credit_limit',   c.credit_limit,
               'month_revenue',  coalesce((
                   select sum(o.total_amount)
                     from sales_order o
                    where o.customer_id = c.id
                      and o.order_date between v_month_start and v_month_end
                      and o.status <> 'cancelled'
               ), 0)
           )
      into v_result
      from customer c
      left join sales_channel  ch on ch.id = c.channel_id
      left join customer_class cc on cc.id = c.class_id
      left join shop_type      st on st.id = c.shop_type_id
     where c.id = p_customer_id;

    -- Null, not an empty object. An outlet in another branch is invisible to
    -- this rep, and the caller should say "not found" rather than render a card
    -- of blank rows that looks like missing data.
    return v_result;
end;
$$;

revoke execute on function customer_info(uuid, date) from public;
grant execute on function customer_info(uuid, date) to authenticated;
