-- =============================================================================
-- Bốn quy tắc thật, đọc từ OM10100
--
-- The previous migration said the seventeen rule flags on OM_DiscSeq were not
-- implemented anywhere either repository could see. That was true of the two
-- repositories I had; it is not true of the ERP order screen, whose source has
-- since arrived. `OM10100Controller.cs` is where "CalDiscountService" actually
-- lives — the legacy books orders by posting the cart to its CalcPromo4eSales
-- endpoint, which is the engine the simplified CalcPromo.cs was only ever an
-- approximation of.
--
-- Four of the flags are decoded and implemented here. They are the ones whose
-- meaning is self-contained; the rest hang off the "required items" mechanism or
-- off budgets and stock, which are features rather than switches.
--
--   BreakBoundType   -> break_bound        does a level repeat, or fire once
--   IsDeductQtyAmt   -> deducts_shared     does the rule see what others ate
--   ExcludePromo     -> discount_exclusion firing this one kills those ones
--   FirstOrder       -> first_order_only   only on the outlet's first order
--
-- Each is a real behaviour change, not bookkeeping: the same campaign data can
-- produce different money depending on all four.
-- =============================================================================

-- OM21100_pcBreakBoundType's two values.
--
-- 'lower' is the legacy's L, "chan duoi": the level is a floor, and a basket
-- that clears it several times earns it several times. The remainder is then
-- walked against the ladder again.
--
-- 'bounded' is B, "chan duoi va tren": clearing the level once is all it is
-- worth, and doing so ends the rule -- `qtyAmt = 0` in OM10100, which stops the
-- lower levels being tried at all.
--
-- Guarded so the migration can be re-run: the first attempt failed on a later
-- statement, and a type that already exists must not take the retry down.
do $$
begin
    if not exists (select 1 from pg_type where typname = 'discount_break_bound') then
        create type discount_break_bound as enum ('lower', 'bounded');
    end if;
end $$;

-- break_bound      BreakBoundType, above.
--
-- deducts_shared   IsDeductQtyAmt, and it is not what its name suggests. It does
--                  not ask whether this rule deducts; it asks which cart the
--                  rule is measured against. TotalInvtV2 reads the running
--                  DumyLineQty when it is set and the untouched StkQty when it
--                  is not. False, the default, is what this engine already did:
--                  every rule sees the basket the customer actually built, so
--                  two campaigns may both reward the same case. True puts the
--                  rule into a shared pool that earlier rules have eaten from,
--                  which is how head office stops a product paying twice.
--
-- first_order_only FirstOrder. A sign-up incentive, checked against the outlet's
--                  order history rather than against the basket.
alter table discount_sequence
    add column if not exists break_bound discount_break_bound not null default 'lower',
    add column if not exists deducts_shared boolean not null default false,
    add column if not exists first_order_only boolean not null default false;

/**
 * ExcludePromo: a semicolon-separated list of DiscID#DiscSeq over there, a table
 * here. Order-dependent by design — a rule only kills the others once it has
 * actually fired, so which rules survive depends on which fired first, which is
 * why `applicable_discount_sequences` orders by scope then priority.
 *
 * Distinct from `exclude_other_disc`, which is about the customer qualifying for
 * an exclusive campaign at all. This one is one campaign vetoing another.
 */
create table discount_exclusion (
    id           uuid not null default gen_random_uuid() primary key,
    sequence_id  uuid not null references discount_sequence (id) on delete cascade,
    excludes_id  uuid not null references discount_sequence (id) on delete cascade,

    unique (sequence_id, excludes_id),
    constraint discount_exclusion_not_self check (sequence_id <> excludes_id)
);

create index on discount_exclusion (sequence_id);

alter table discount_exclusion enable row level security;

create policy "everyone reads discount exclusions" on discount_exclusion
    for select to authenticated using (true);

-- -----------------------------------------------------------------------------
-- The listing gains the flags, and the first-order test
-- -----------------------------------------------------------------------------

-- Dropped rather than replaced: the return table gains two columns, and
-- `create or replace` refuses to change a function's signature.
drop function if exists applicable_discount_sequences(uuid, date);

create function applicable_discount_sequences(
    p_customer_id uuid,
    p_order_date  date default current_date
)
returns table (
    sequence_id        uuid,
    program_id         uuid,
    program_code       text,
    program_name       text,
    sequence_code      text,
    sequence_name      text,
    scope              discount_scope,
    break_by           discount_break_by,
    reward             discount_reward,
    auto_free_item     boolean,
    exclude_other_disc boolean,
    priority           integer,
    break_bound        discount_break_bound,
    deducts_shared     boolean
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select id, branch_id, class_id, channel_id, shop_type_id
        from customer where id = p_customer_id
    ),
    -- Has this outlet ever ordered? A first-order rule is spent the moment one
    -- order exists, whoever took it.
    history as (
        select exists (
            select 1 from sales_order
            where customer_id = p_customer_id
              and status <> 'cancelled'
        ) as has_ordered
    ),
    live as (
        select s.*, p.scope, p.code as program_code, p.name as program_name
        from discount_sequence s
        join discount_program p on p.id = s.program_id
        cross join me
        cross join history h
        where s.is_active
          and p.is_active
          and s.from_date <= p_order_date
          -- open_ended is the legacy's Promo = 0: the end date stops being a
          -- boundary and the rule keeps running until someone deactivates it.
          and (s.open_ended or p_order_date <= s.to_date)
          and (s.branch_id is null or s.branch_id = me.branch_id)
          and (not s.first_order_only or not h.has_ordered)
    ),
    -- Membership: any one match is enough, and no rows at all means the whole
    -- branch, which is how a campaign runs for everybody.
    eligible as (
        select l.*
        from live l
        cross join me
        where not exists (select 1 from discount_audience a where a.sequence_id = l.id)
           or exists (
                select 1 from discount_audience a
                where a.sequence_id = l.id
                  and (
                      (a.kind = 'customer'  and a.target_id = me.id)
                   or (a.kind = 'class'     and a.target_id = me.class_id)
                   or (a.kind = 'channel'   and a.target_id = me.channel_id)
                   or (a.kind = 'shop_type' and a.target_id = me.shop_type_id)
                  )
           )
    )
    select
        e.id, e.program_id, e.program_code, e.program_name,
        e.code, e.name, e.scope, e.break_by, e.reward,
        e.auto_free_item, e.exclude_other_disc, e.priority,
        e.break_bound, e.deducts_shared
    from eligible e
    where
        case
            when exists (select 1 from eligible x where x.exclude_other_disc)
                then e.exclude_other_disc
            else true
        end
    -- Line rules first, then group, then order: each shrinks what the next sees.
    -- Priority decides within a scope, and it decides more than presentation now
    -- that a rule can veto another — whichever fires first does the vetoing.
    order by e.scope, e.priority desc, e.program_code, e.code;
$$;

comment on function applicable_discount_sequences(uuid, date) is
    'Discount rules this outlet qualifies for on this date. The audience half of '
    'the legacy API_GetDiscSeq, plus OM10100''s first-order test.';

-- -----------------------------------------------------------------------------
-- The engine, with the four flags honoured
-- -----------------------------------------------------------------------------

create or replace function calculate_promotions(
    p_customer_id uuid,
    p_lines       jsonb,
    p_order_date  date default current_date
)
returns jsonb
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_seq        record;
    v_break      record;
    v_cond       record;
    v_p          integer;
    v_available  integer;
    v_basis      numeric;   -- the money the reward is computed against
    v_group_qty  numeric;
    v_group_amt  numeric;
    v_order_amt  numeric;   -- the order as the customer built it, reported back
    v_order_left numeric;   -- what order-scope levels have not yet consumed
    v_amount     numeric;
    v_percent    numeric;
    v_earned     jsonb := '[]'::jsonb;
    v_free       jsonb;
    v_total      numeric := 0;
    v_fired      boolean;
    v_vetoed     uuid[] := '{}';
begin
    -- Three carts, because the flags make one insufficient.
    --
    --   _cart_base   the basket as the customer built it, never written to
    --   _cart_shared the running remainder, eaten by every deducts_shared rule
    --   _cart        the copy the rule being evaluated works on
    --
    -- Session-scoped rather than transaction-scoped on purpose: a temp table
    -- dropped at every commit invalidates the cached plans that reference it,
    -- and the second call in a pooled connection then fails on a relation that
    -- no longer exists. Created once, emptied per call.
    create temporary table if not exists _cart (
        product_id uuid,
        uom_code   text,
        qty        numeric,
        amount     numeric
    );

    create temporary table if not exists _cart_base (
        product_id uuid,
        uom_code   text,
        qty        numeric,
        amount     numeric
    );

    create temporary table if not exists _cart_shared (
        product_id uuid,
        uom_code   text,
        qty        numeric,
        amount     numeric
    );

    -- What the rule currently being evaluated has eaten. Kept apart from the
    -- carts because the pool has to be reduced by the *delta*, not overwritten:
    -- a rule reading the pristine basket must still take its bite out of the
    -- pool, or the flag would only ever matter to the rule that carries it.
    create temporary table if not exists _used (
        product_id uuid,
        uom_code   text,
        qty        numeric,
        amount     numeric
    );

    -- `where true` rather than a bare delete: the pooled connection PostgREST
    -- uses refuses an unqualified DELETE, and a temp table is no exception.
    delete from _cart_base where true;

    insert into _cart_base (product_id, uom_code, qty, amount)
    select product_id, uom_code, qty, amount
    from cart_in_all_units(p_customer_id, p_order_date, p_lines);

    delete from _cart_shared where true;
    insert into _cart_shared (product_id, uom_code, qty, amount)
    select product_id, uom_code, qty, amount from _cart_base;

    -- The order total is the cart at its own units, before any rule touches it:
    -- an order-level threshold is written against what the customer is spending,
    -- not against what is left after the line rules have eaten into it.
    select coalesce(sum((l ->> 'qty')::numeric * pr.price), 0)
    into v_order_amt
    from jsonb_array_elements(coalesce(p_lines, '[]'::jsonb)) as l
    join customer c on c.id = p_customer_id
    left join lateral (
        select pl.price
        from price_list pl
        where pl.product_id = (l ->> 'product_id')::uuid
          and pl.uom_code = (l ->> 'uom_code')::text
          and (pl.class_id = c.class_id or pl.class_id is null)
          and p_order_date between pl.from_date and pl.to_date
        order by pl.class_id nulls last
        limit 1
    ) pr on true;

    -- The running copy. The reported total stays what the customer is spending.
    v_order_left := v_order_amt;

    for v_seq in
        select * from applicable_discount_sequences(p_customer_id, p_order_date)
    loop
        -- Vetoed by a rule that already fired.
        if v_seq.sequence_id = any (v_vetoed) then
            continue;
        end if;

        -- Which basket this rule is measured against. The shared pool is what
        -- earlier deducting rules have left; the base is what the customer
        -- actually bought.
        delete from _cart where true;
        insert into _cart (product_id, uom_code, qty, amount)
        select product_id, uom_code, qty, amount
        from (
            select * from _cart_shared where v_seq.deducts_shared
            union all
            select * from _cart_base where not v_seq.deducts_shared
        ) src;

        delete from _used where true;
        v_fired := false;

        -- Group rules pool their condition items before looking at any level.
        if v_seq.scope = 'group' then
            select coalesce(sum(c.qty), 0), coalesce(sum(c.amount), 0)
            into v_group_qty, v_group_amt
            from discount_condition_item ci
            join _cart c
                on c.product_id = ci.product_id and c.uom_code = ci.uom_code
            where ci.sequence_id = v_seq.sequence_id;
        end if;

        for v_break in
            select * from discount_break
            where sequence_id = v_seq.sequence_id
            order by break_qty desc, break_amt desc
        loop
            -- A bounded rule is spent once any level of it has been earned.
            -- OM10100 zeroes the basis rather than breaking the loop, which
            -- amounts to the same thing and is what this stops instead.
            exit when v_fired and v_seq.break_bound = 'bounded';

            v_p := 0;
            v_basis := 0;

            if v_seq.scope = 'line' then
                -- The money a percentage reward is measured against: every
                -- condition item this rule names, as the cart stands before this
                -- level consumes anything.
                select coalesce(sum(c.amount), 0) into v_basis
                from discount_condition_item ci
                join _cart c
                    on c.product_id = ci.product_id and c.uom_code = ci.uom_code
                where ci.sequence_id = v_seq.sequence_id;

                -- Every condition item is measured on its own, and each one that
                -- qualifies consumes what it used.
                for v_cond in
                    select ci.product_id, ci.uom_code
                    from discount_condition_item ci
                    where ci.sequence_id = v_seq.sequence_id
                loop
                    select
                        case
                            when v_seq.break_by = 'qty' and v_break.break_qty > 0
                                then floor(c.qty / v_break.break_qty)
                            when v_seq.break_by = 'amount' and v_break.break_amt > 0
                                then floor(c.amount / v_break.break_amt)
                            else 0
                        end
                    into v_available
                    from _cart c
                    where c.product_id = v_cond.product_id
                      and c.uom_code = v_cond.uom_code;

                    if coalesce(v_available, 0) < 1 then
                        continue;
                    end if;

                    -- A bounded level is worth one lot however many times the
                    -- basket clears it — CalcDiscountRate's last two lines.
                    if v_seq.break_bound = 'bounded' then
                        v_available := 1;
                    end if;

                    if v_break.max_lot > 0 and v_p + v_available > v_break.max_lot then
                        v_available := greatest(v_break.max_lot - v_p, 0);
                    end if;

                    if v_available < 1 then
                        continue;
                    end if;

                    v_p := v_p + v_available;

                    -- Consume. This is what stops one basket earning the same
                    -- level twice through two different levels of the same rule,
                    -- and it is recorded so the shared pool can be reduced by it.
                    insert into _used (product_id, uom_code, qty, amount)
                    select
                        v_cond.product_id,
                        v_cond.uom_code,
                        case when v_seq.break_by = 'qty'
                             then v_available * v_break.break_qty else 0 end,
                        case when v_seq.break_by = 'qty'
                             then v_available * v_break.break_qty
                                  * (case when c.qty > 0 then c.amount / c.qty else 0 end)
                             else v_available * v_break.break_amt end
                    from _cart c
                    where c.product_id = v_cond.product_id
                      and c.uom_code = v_cond.uom_code;

                    if v_seq.break_by = 'qty' then
                        update _cart
                        set qty = qty - v_available * v_break.break_qty,
                            amount = amount
                                - v_available * v_break.break_qty
                                * (case when qty > 0 then amount / qty else 0 end)
                        where product_id = v_cond.product_id
                          and uom_code = v_cond.uom_code;
                    else
                        update _cart
                        set amount = amount - v_available * v_break.break_amt
                        where product_id = v_cond.product_id
                          and uom_code = v_cond.uom_code;
                    end if;

                    exit when v_seq.break_bound = 'bounded';
                end loop;

            elsif v_seq.scope = 'group' then
                if v_seq.break_by = 'qty' and v_break.break_qty > 0
                   and v_group_qty >= v_break.break_qty then
                    v_p := floor(v_group_qty / v_break.break_qty);
                elsif v_seq.break_by = 'amount' and v_break.break_amt > 0
                      and v_group_amt >= v_break.break_amt then
                    v_p := floor(v_group_amt / v_break.break_amt);
                end if;

                if v_seq.break_bound = 'bounded' and v_p > 1 then
                    v_p := 1;
                end if;

                if v_break.max_lot > 0 and v_p > v_break.max_lot then
                    v_p := v_break.max_lot;
                end if;

                v_basis := v_group_amt;

                if v_p >= 1 then
                    if v_seq.break_by = 'qty' then
                        v_group_qty := v_group_qty - v_p * v_break.break_qty;
                    else
                        v_group_amt := v_group_amt - v_p * v_break.break_amt;
                    end if;
                end if;

            else
                -- Order scope. Money only, and it used to be pinned to one lot
                -- unconditionally; the flag says so now instead, which is why a
                -- 'lower' order rule can finally repeat.
                if v_break.break_amt > 0 and v_order_left >= v_break.break_amt then
                    v_p := floor(v_order_left / v_break.break_amt);

                    if v_seq.break_bound = 'bounded' and v_p > 1 then
                        v_p := 1;
                    end if;

                    if v_break.max_lot > 0 and v_p > v_break.max_lot then
                        v_p := v_break.max_lot;
                    end if;

                    v_basis := v_order_left;
                    v_order_left := v_order_left - v_p * v_break.break_amt;
                end if;
            end if;

            if v_p < 1 then
                continue;
            end if;

            v_fired := true;

            -- A percentage is a threshold, not a multiplier. `CalcPromo.cs`
            -- multiplies the portion count in twice and accumulates the
            -- percentage as well; neither is arithmetic a campaign was written
            -- to mean, and neither is reproduced.
            if v_seq.reward = 'percent' then
                v_p := 1;
                v_percent := v_break.disc_amt;
                v_amount := round(v_basis * v_break.disc_amt / 100);
            else
                v_percent := null;
                v_amount := case
                    when v_seq.reward in ('amount', 'amount_or_item')
                        then round(v_break.disc_amt * v_p)
                    else 0
                end;
            end if;

            -- The goods on offer. `amount_or_item` carries both and lets the rep
            -- choose; `auto_free_item = false` makes the list alternatives rather
            -- than a bundle, with the first pre-selected as the legacy does.
            v_free := '[]'::jsonb;

            if v_seq.reward in ('free_item', 'amount_or_item') then
                select coalesce(jsonb_agg(item order by rn), '[]'::jsonb)
                into v_free
                from (
                    select
                        row_number() over (order by fi.sort_order, p.name) as rn,
                        jsonb_build_object(
                            'product_id', fi.product_id,
                            'product_code', p.code,
                            'product_name', p.name,
                            'uom_code', fi.uom_code,
                            'qty', fi.free_qty * v_p,
                            -- Everything is given when the list is a bundle; when
                            -- it is a set of alternatives only the first arrives
                            -- pre-selected, which is what the legacy does before
                            -- the rep changes it.
                            'chosen', v_seq.auto_free_item
                                or row_number() over (order by fi.sort_order, p.name) = 1
                        ) as item
                    from discount_free_item fi
                    join product p on p.id = fi.product_id
                    where fi.sequence_id = v_seq.sequence_id
                ) ranked;
            end if;

            v_total := v_total + v_amount;

            v_earned := v_earned || jsonb_build_object(
                'sequence_id', v_seq.sequence_id,
                'program_code', v_seq.program_code,
                'program_name', v_seq.program_name,
                'sequence_code', v_seq.sequence_code,
                'sequence_name', v_seq.sequence_name,
                'scope', v_seq.scope,
                'break_by', v_seq.break_by,
                'reward', v_seq.reward,
                'break_id', v_break.id,
                'break_name', v_break.name,
                'break_qty', v_break.break_qty,
                'break_amt', v_break.break_amt,
                'portion', v_p,
                'discount_amount', v_amount::bigint,
                'percent', v_percent,
                -- A rule where the rep picks: goods versus money, or which goods.
                'needs_choice', v_seq.reward = 'amount_or_item'
                    or (v_seq.reward = 'free_item' and not v_seq.auto_free_item),
                'free_items', v_free
            );
        end loop;

        -- Only a rule that actually fired writes back what it ate, and only a
        -- rule that actually fired vetoes anything. A campaign the basket did
        -- not qualify for silences nobody.
        if v_fired then
            -- Every rule that fires takes its bite out of the pool, whether or
            -- not it was measured against it. The flag governs reading; the
            -- eating is unconditional, which is what makes a later flagged rule
            -- see less than the customer actually bought.
            update _cart_shared s
            set qty = greatest(s.qty - u.qty, 0),
                amount = greatest(s.amount - u.amount, 0)
            from (
                select product_id, uom_code, sum(qty) as qty, sum(amount) as amount
                from _used group by product_id, uom_code
            ) u
            where s.product_id = u.product_id and s.uom_code = u.uom_code;

            select v_vetoed || coalesce(array_agg(x.excludes_id), '{}')
            into v_vetoed
            from discount_exclusion x
            where x.sequence_id = v_seq.sequence_id;
        end if;
    end loop;

    return jsonb_build_object(
        'order_amount', v_order_amt::bigint,
        'total_discount', v_total::bigint,
        'earned', v_earned
    );
end;
$$;

revoke execute on function calculate_promotions(uuid, jsonb, date) from public;
grant execute on function calculate_promotions(uuid, jsonb, date) to authenticated;

comment on function calculate_promotions(uuid, jsonb, date) is
    'Line, group and order discounts over one cart. OM10100''s BreakBoundType, '
    'IsDeductQtyAmt, ExcludePromo and FirstOrder are honoured; the flags that '
    'hang off required items, budgets and warehouse stock are not yet.';
