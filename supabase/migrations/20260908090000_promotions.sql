-- =============================================================================
-- Khuyến mãi
--
-- The order screen has priced orders with no discounts since the first slice.
-- The note in 20260814120000_orders.sql said the promotion engine lived in an
-- external service nobody had. That turned out to be wrong: it is
-- `CalcPromo.cs` in the legacy backend, 1258 lines, and this migration ports the
-- part of it that actually runs.
--
-- The legacy shape, and what each piece becomes here:
--
--   OM_Discount      -> discount_program           the campaign, and its scope
--   OM_DiscSeq       -> discount_sequence          one rule inside it
--   OM_DiscBreak     -> discount_break             the thresholds
--   OM_DiscItem      -> discount_condition_item    what has to be bought
--   OM_DiscFreeItem  -> discount_free_item         what is given away
--   OM_DiscChannel / OM_DiscCust / OM_DiscCustCate /
--   OM_DiscCustClass / OM_DiscShopType
--                    -> discount_audience          who it applies to
--   OM_DiscCpny      -> discount_sequence.branch_id
--   OM_PDAOrdDisc    -> sales_order_discount       what an order actually earned
--
-- Two deliberate simplifications, both stated rather than hidden:
--
--   * OM_DiscCpny is a five-level geography (zone / territory / state / branch
--     route / district). This schema has one branch table, so a sequence names a
--     branch or runs everywhere. Inventing four more levels for data that does
--     not exist here would be modelling a hierarchy nobody has.
--
--   * Seventeen rule flags on OM_DiscSeq — ProrateAmtType, SubBreakType,
--     BreakBoundType, ChoiceType, IsDeductQtyAmt, ExactQty, PctDiscountByLevel,
--     ConvertDiscAmtToFreeItem, FirstOrder and the rest — are *not* here.
--     `CalcPromo.cs` reads every one of them off the row and then uses none of
--     them, and no stored procedure in the dump evaluates them either: the only
--     routines that mention them are the admin CRUD screens and the sync that
--     ships the master data to the phone.
--
--     Written when neither repository to hand implemented them. They are
--     implemented, in the ERP order screen OM10100 — the "CalDiscountService"
--     an earlier note called unavailable — and 20260909090000 ports the four
--     whose meaning is self-contained. The rest still wait on required items,
--     budgets and warehouse stock.
-- =============================================================================

-- OM_Discount.DiscType. The order of evaluation, and the reason it matters: a
-- line rule shrinks the quantity a group rule then sees, and both shrink the
-- total the order rule lands on.
create type discount_scope as enum (
    'line',   -- L: per product
    'group',  -- G: over a set of products together
    'order'   -- D: on the order total, last
);

-- OM_DiscSeq.BreakBy: is the threshold counted in units or in dong.
create type discount_break_by as enum ('qty', 'amount');

-- OM_DiscSeq.DiscFor.
create type discount_reward as enum (
    'free_item',       -- I
    'amount',          -- A
    'percent',         -- P
    'amount_or_item'   -- B: the rep picks money or goods at each level
);

-- The five OM_Disc* audience tables, which are one table with a kind here.
create type discount_audience_kind as enum (
    'customer',
    'class',
    'channel',
    'shop_type'
);

create table discount_program (
    id         uuid    primary key default gen_random_uuid(),
    code       text    not null unique,
    name       text    not null,

    scope      discount_scope not null,

    /**
     * OM_Discount.DiscClass — II, BB, CI, CB and friends. Carried so the data
     * round-trips and so the combo classes can be recognised later; nothing in
     * this engine branches on it yet, exactly as `CalcPromo.cs` only branches on
     * it for the BB/B1/CB bundle case that is out of scope here.
     */
    disc_class text,

    is_active  boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create trigger discount_program_set_updated_at
    before update on discount_program
    for each row execute function set_updated_at();

create table discount_sequence (
    id          uuid    primary key default gen_random_uuid(),
    program_id  uuid    not null references discount_program (id) on delete cascade,
    code        text    not null,
    name        text    not null,

    break_by    discount_break_by not null,
    reward      discount_reward   not null,

    /**
     * OM_DiscSeq.AutoFreeItem. True gives every free item on the list (AND);
     * false makes them alternatives the rep chooses between (OR). The legacy
     * pre-selects the first one, and so does this.
     */
    auto_free_item boolean not null default true,

    /**
     * ExcludeOtherDisc. A customer who qualifies for any exclusive rule gets
     * *only* the exclusive ones — the rest of the catalogue stops applying to
     * them. Enforced in `applicable_discount_sequences`.
     */
    exclude_other_disc boolean not null default false,

    /** PriorityPromo. Higher first within a scope. */
    priority    integer not null default 0,

    from_date   date    not null,
    to_date     date    not null,

    /**
     * OM_DiscSeq.Promo, inverted into a name that says what it does: false means
     * the window closes on to_date; true means to_date is advisory and the rule
     * keeps running. Both shapes are real in the legacy data.
     */
    open_ended  boolean not null default false,

    /** Null runs the rule in every branch. */
    branch_id   uuid    references branch (id),

    is_active   boolean not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),

    unique (program_id, code),
    constraint discount_sequence_dates check (to_date >= from_date)
);

create index on discount_sequence (program_id);

create trigger discount_sequence_set_updated_at
    before update on discount_sequence
    for each row execute function set_updated_at();

/**
 * Who a rule applies to. No rows at all means every customer in the sequence's
 * branch, which is the legacy's "OM_DiscCpny at branch-route level and nothing
 * narrower" case.
 */
create table discount_audience (
    id          uuid    primary key default gen_random_uuid(),
    sequence_id uuid    not null references discount_sequence (id) on delete cascade,
    kind        discount_audience_kind not null,
    target_id   uuid    not null,

    unique (sequence_id, kind, target_id)
);

create index on discount_audience (sequence_id);
create index on discount_audience (kind, target_id);

create table discount_break (
    id          uuid    primary key default gen_random_uuid(),
    sequence_id uuid    not null references discount_sequence (id) on delete cascade,

    /** OM_DiscBreak.LineRef: the level's own key inside the rule. */
    line_ref    text    not null,
    name        text    not null default '',

    /** The threshold. One of these is read, depending on the rule's break_by. */
    break_qty   integer not null default 0 check (break_qty >= 0),
    break_amt   bigint  not null default 0 check (break_amt >= 0),

    /**
     * What the level is worth: dong when the reward is money, whole percent when
     * it is a percentage, and ignored when the reward is goods. One column
     * because the legacy has one column, and splitting it would invent a
     * distinction the master data does not make.
     */
    disc_amt    numeric(18, 4) not null default 0 check (disc_amt >= 0),

    /** MaxLot: how many times this level may be earned. Zero means no ceiling. */
    max_lot     integer not null default 0 check (max_lot >= 0),

    unique (sequence_id, line_ref)
);

create index on discount_break (sequence_id);

/**
 * What has to be bought. Product *and* unit, because that is how the legacy
 * matches: a rule saying "10 chai" and a cart holding "2 thùng" meet through the
 * unit conversion, not through the product alone.
 */
create table discount_condition_item (
    id          uuid    primary key default gen_random_uuid(),
    sequence_id uuid    not null references discount_sequence (id) on delete cascade,
    product_id  uuid    not null references product (id),
    uom_code    text    not null references uom (code),

    /** BundleQty / BundleAmt, for the combo classes. Kept, not yet acted on. */
    bundle_qty  integer not null default 0 check (bundle_qty >= 0),
    bundle_amt  bigint  not null default 0 check (bundle_amt >= 0),

    unique (sequence_id, product_id, uom_code)
);

create index on discount_condition_item (sequence_id);

/**
 * What is given away. Attached to the rule rather than to a level, because that
 * is what the legacy engine reads: every level of a rule offers the same list,
 * scaled by how many times the level was earned.
 */
create table discount_free_item (
    id          uuid    primary key default gen_random_uuid(),
    sequence_id uuid    not null references discount_sequence (id) on delete cascade,
    product_id  uuid    not null references product (id),
    uom_code    text    not null references uom (code),
    free_qty    integer not null check (free_qty > 0),
    sort_order  integer not null default 0,

    unique (sequence_id, product_id, uom_code)
);

create index on discount_free_item (sequence_id);

-- -----------------------------------------------------------------------------
-- What an order actually earned
--
-- Written by `submit_order` from its own recalculation, never from the client.
-- The rep's device may be hours out of date about which campaigns are running;
-- the order has to be right about money regardless.
-- -----------------------------------------------------------------------------

create table sales_order_discount (
    id           uuid    primary key default gen_random_uuid(),
    order_id     uuid    not null references sales_order (id) on delete cascade,
    sequence_id  uuid    not null references discount_sequence (id),
    break_id     uuid    not null references discount_break (id),

    /** How many times the level was earned. The legacy's Portion. */
    portion      integer not null check (portion > 0),

    /** Money off, already multiplied out. Zero for a goods-only reward. */
    discount_amount bigint not null default 0 check (discount_amount >= 0),

    /** Whole percent, when the reward was a percentage. */
    percent      numeric(7, 4),

    -- Denormalised so a statement of what the customer was given survives the
    -- campaign being edited or retired next month.
    descr        text    not null default '',

    created_at   timestamptz not null default now(),

    unique (order_id, sequence_id, break_id)
);

create index on sales_order_discount (order_id);

-- Free goods become order lines as well, because that is what gets delivered.
-- This table records which rule produced them, which the line cannot say.
create table sales_order_discount_free_item (
    id                 uuid    primary key default gen_random_uuid(),
    order_discount_id  uuid    not null references sales_order_discount (id) on delete cascade,
    product_id         uuid    not null references product (id),
    uom_code           text    not null references uom (code),
    qty                integer not null check (qty > 0),

    unique (order_discount_id, product_id, uom_code)
);

create index on sales_order_discount_free_item (order_discount_id);

-- -----------------------------------------------------------------------------
-- Free goods on the order itself
--
-- A free line is a real line: it is picked, loaded and delivered. It is priced
-- at zero and excluded from the totals, and the uniqueness key gains the flag so
-- a customer can both buy and be given the same product — which is the ordinary
-- case for a "buy ten, get one" rule.
-- -----------------------------------------------------------------------------

alter table sales_order_line
    add column is_free boolean not null default false;

alter table sales_order_line
    drop constraint sales_order_line_order_id_product_id_uom_code_key;

alter table sales_order_line
    add constraint sales_order_line_order_id_product_id_uom_code_is_free_key
    unique (order_id, product_id, uom_code, is_free);

-- -----------------------------------------------------------------------------
-- Row Level Security
--
-- The campaign tables are head office's and every rep reads all of them: which
-- promotions exist is not a secret, and a rep who cannot read a rule cannot be
-- told why an order did or did not earn it.
-- -----------------------------------------------------------------------------

alter table discount_program        enable row level security;
alter table discount_sequence       enable row level security;
alter table discount_audience       enable row level security;
alter table discount_break          enable row level security;
alter table discount_condition_item enable row level security;
alter table discount_free_item      enable row level security;
alter table sales_order_discount    enable row level security;
alter table sales_order_discount_free_item enable row level security;

create policy "everyone reads discount programs" on discount_program
    for select to authenticated using (true);
create policy "everyone reads discount sequences" on discount_sequence
    for select to authenticated using (true);
create policy "everyone reads discount audiences" on discount_audience
    for select to authenticated using (true);
create policy "everyone reads discount breaks" on discount_break
    for select to authenticated using (true);
create policy "everyone reads discount condition items" on discount_condition_item
    for select to authenticated using (true);
create policy "everyone reads discount free items" on discount_free_item
    for select to authenticated using (true);

create policy "rep reads own order discounts"
    on sales_order_discount for select to authenticated
    using (
        exists (
            select 1 from sales_order o
            where o.id = sales_order_discount.order_id
              and o.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own order discounts"
    on sales_order_discount for insert to authenticated
    with check (
        exists (
            select 1 from sales_order o
            where o.id = sales_order_discount.order_id
              and o.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep reads own order free items"
    on sales_order_discount_free_item for select to authenticated
    using (
        exists (
            select 1 from sales_order_discount d
            join sales_order o on o.id = d.order_id
            where d.id = sales_order_discount_free_item.order_discount_id
              and o.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own order free items"
    on sales_order_discount_free_item for insert to authenticated
    with check (
        exists (
            select 1 from sales_order_discount d
            join sales_order o on o.id = d.order_id
            where d.id = sales_order_discount_free_item.order_discount_id
              and o.salesperson_id = current_salesperson_id()
        )
    );

-- -----------------------------------------------------------------------------
-- Which rules apply to this outlet today
--
-- The audience half of API_GetDiscSeq. The legacy unions five membership tables
-- and intersects them with a geography; here the five are one table with a kind,
-- and the geography is a branch.
--
-- The exclusivity rule is the subtle part and is the legacy's: a customer who
-- qualifies for *any* rule marked exclusive sees only the exclusive ones. It is
-- not "this rule excludes others" — it is "the existence of one changes the set".
-- -----------------------------------------------------------------------------

create or replace function applicable_discount_sequences(
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
    priority           integer
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
    live as (
        select s.*, p.scope, p.code as program_code, p.name as program_name
        from discount_sequence s
        join discount_program p on p.id = s.program_id
        cross join me
        where s.is_active
          and p.is_active
          and s.from_date <= p_order_date
          -- open_ended is the legacy's Promo = 0: the end date stops being a
          -- boundary and the rule keeps running until someone deactivates it.
          and (s.open_ended or p_order_date <= s.to_date)
          and (s.branch_id is null or s.branch_id = me.branch_id)
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
        e.auto_free_item, e.exclude_other_disc, e.priority
    from eligible e
    where
        case
            when exists (select 1 from eligible x where x.exclude_other_disc)
                then e.exclude_other_disc
            else true
        end
    -- Line rules first, then group, then order: each shrinks what the next sees.
    order by e.scope, e.priority desc, e.program_code, e.code;
$$;

comment on function applicable_discount_sequences(uuid, date) is
    'Discount rules this outlet qualifies for on this date. The audience half of '
    'the legacy API_GetDiscSeq.';

-- -----------------------------------------------------------------------------
-- The cart, in every unit each product has
--
-- `#CnvFact` in the legacy proc, and the reason a rule written in chai matches a
-- cart entered in thùng. Each ordered product is restated once per unit it is
-- packed in, with the quantity and the money converted, so a condition item can
-- name whichever unit head office wrote the campaign in.
--
-- The money is recomputed per unit rather than carried across, because the price
-- of a thùng is not twelve times the price of a chai and the campaign's
-- threshold was written against one of them.
-- -----------------------------------------------------------------------------

create or replace function cart_in_all_units(
    p_customer_id uuid,
    p_order_date  date,
    p_lines       jsonb
)
returns table (
    product_id uuid,
    uom_code   text,
    qty        numeric,
    amount     numeric
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select class_id from customer where id = p_customer_id
    ),
    input as (
        select
            (l ->> 'product_id')::uuid as product_id,
            (l ->> 'uom_code')::text   as uom_code,
            (l ->> 'qty')::integer     as qty
        from jsonb_array_elements(coalesce(p_lines, '[]'::jsonb)) as l
    ),
    -- Everything in base units first, summed per product: two lines of the same
    -- product in different packs are one holding as far as a campaign is
    -- concerned.
    based as (
        select
            i.product_id,
            sum(i.qty::numeric * pu.conversion_rate) as base_qty
        from input i
        join product_uom pu
            on pu.product_id = i.product_id and pu.uom_code = i.uom_code
        group by i.product_id
    )
    select
        b.product_id,
        pu.uom_code,
        b.base_qty / pu.conversion_rate as qty,
        (b.base_qty / pu.conversion_rate) * coalesce(pr.price, 0) as amount
    from based b
    join product_uom pu on pu.product_id = b.product_id
    cross join me
    left join lateral (
        select pl.price
        from price_list pl
        where pl.product_id = b.product_id
          and pl.uom_code = pu.uom_code
          and (pl.class_id = me.class_id or pl.class_id is null)
          and p_order_date between pl.from_date and pl.to_date
        order by pl.class_id nulls last
        limit 1
    ) pr on true;
$$;

-- -----------------------------------------------------------------------------
-- calculate_promotions
--
-- The engine. `CalcKMDong`, `CalcKMNhom` and `CalcKMChungTu` in one function,
-- because they are one pass over one cart and splitting them would mean passing
-- the consumed quantities between three calls.
--
-- The rule that makes the whole thing behave: a level that is earned *consumes*
-- the quantity or the money that earned it. Ten bottles against a "buy 10" level
-- leave nothing behind for the next level, and a cart of twenty-five earns the
-- twenty level once and then has five left, not twenty-five again. Levels are
-- therefore walked from the highest threshold down, which is the order the
-- legacy proc returns them in.
--
-- Two places where this deliberately does not reproduce the legacy, both
-- involving percentages, both flagged rather than silently copied:
--
--   * `CalcPromo.cs` computes a percentage reward as
--     `(p * DiscAmt) * (p * amount) / 100` — the portion count multiplies in
--     twice, so a cart that qualifies three times gets nine times the discount.
--     That is arithmetic no campaign was written to mean.
--   * It also accumulates `PercentDisc += p * DiscAmt`, making three portions of
--     5% into 15% off.
--
-- Here a percentage level is a threshold: crossing it more than once does not
-- deepen the discount, so the portion is capped at one and the money is
-- `amount * percent / 100`. If head office really does want the legacy's
-- behaviour, this is the one place to change.
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
    v_order_amt  numeric;
    v_amount     numeric;
    v_percent    numeric;
    v_earned     jsonb := '[]'::jsonb;
    v_free       jsonb;
    v_total      numeric := 0;
begin
    -- The cart, restated in every unit, as a table this function may consume from
    -- as levels are earned.
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

    -- `where true` rather than a bare delete: the pooled connection PostgREST
    -- uses refuses an unqualified DELETE, and a temp table is no exception.
    delete from _cart_base where true;

    insert into _cart_base (product_id, uom_code, qty, amount)
    select product_id, uom_code, qty, amount
    from cart_in_all_units(p_customer_id, p_order_date, p_lines);

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

    for v_seq in
        select * from applicable_discount_sequences(p_customer_id, p_order_date)
    loop
        -- A fresh cart per rule. The legacy hands every sequence its own copy
        -- (`GetCartItem()` clones on each call), so a level that consumes what
        -- earned it consumes it only for the other levels of its own rule. Two
        -- campaigns may therefore both reward the same case of Coca, which is
        -- what head office means when it runs two campaigns over one product.
        delete from _cart where true;
        insert into _cart (product_id, uom_code, qty, amount)
        select product_id, uom_code, qty, amount from _cart_base;

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

                    if v_break.max_lot > 0 and v_p + v_available > v_break.max_lot then
                        v_available := greatest(v_break.max_lot - v_p, 0);
                    end if;

                    if v_available < 1 then
                        continue;
                    end if;

                    v_p := v_p + v_available;

                    -- Consume. This is what stops one basket earning the same
                    -- level twice through two different levels of the same rule.
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
                end loop;

            elsif v_seq.scope = 'group' then
                if v_seq.break_by = 'qty' and v_break.break_qty > 0
                   and v_group_qty >= v_break.break_qty then
                    v_p := floor(v_group_qty / v_break.break_qty);
                elsif v_seq.break_by = 'amount' and v_break.break_amt > 0
                      and v_group_amt >= v_break.break_amt then
                    v_p := floor(v_group_amt / v_break.break_amt);
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
                -- Order scope. Money only, and once: the legacy pins the portion
                -- to one here however many times the total clears the threshold.
                if v_break.break_amt > 0 and v_order_amt >= v_break.break_amt then
                    v_p := 1;
                    v_basis := v_order_amt;
                end if;
            end if;

            if v_p < 1 then
                continue;
            end if;

            -- A percentage is a threshold, not a multiplier. See the note above.
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
    'The legacy CalcPromo: line, group and order discounts over one cart, with '
    'earned levels consuming what earned them.';
