-- =============================================================================
-- Issuing sites and their stock
--
-- What the distributor actually has on hand, so a rep can stop promising a case
-- of something the warehouse ran out of on Tuesday.
--
-- Lots are left out on purpose. The legacy screen breaks each product down by
-- batch and batch date, but that is the near-expiry feature (hàng cận date)
-- wearing this screen's clothes: dates, ageing thresholds and a rule about what
-- a rep may still sell. Half of it here would be worse than none — a rep would
-- see batch numbers and reasonably assume the app was watching expiry, which it
-- would not be.
--
-- Both tables are head office's to load, like price_list and msl. Nothing in the
-- app writes stock: the app is not the warehouse system, and a figure a rep could
-- edit is a figure nobody can trust.
-- =============================================================================

create table site (
    id          uuid    primary key default gen_random_uuid(),
    branch_id   uuid    not null references branch (id) on delete cascade,
    code        text    not null,
    name        text    not null,
    address     text,
    is_active   boolean not null default true,
    created_at  timestamptz not null default now(),

    unique (branch_id, code)
);

comment on table site is
    'A warehouse orders are issued from. Loaded by head office; the app only reads.';

create table site_stock (
    id          uuid    primary key default gen_random_uuid(),
    site_id     uuid    not null references site (id) on delete cascade,
    product_id  uuid    not null references product (id) on delete cascade,

    /**
     * Base units, so it can be compared against an order line without going
     * through a conversion the warehouse never agreed to.
     */
    qty_base    integer not null default 0
        constraint stock_not_negative check (qty_base >= 0),

    /**
     * When the warehouse last said so. Carried through to the screen because a
     * stock figure with no age on it invites a rep to treat yesterday's count as
     * this morning's.
     */
    updated_at  timestamptz not null default now(),

    unique (site_id, product_id)
);

create index on site_stock (site_id, product_id);

-- -----------------------------------------------------------------------------
-- Row-level security
--
-- Scoped to the rep's own branch on both. A distributor's stock position is not
-- something a neighbouring distributor's reps have any business reading, and the
-- branch is the boundary every other policy here already uses.
-- -----------------------------------------------------------------------------

alter table site enable row level security;
alter table site_stock enable row level security;

create policy "rep reads sites in own branch"
    on site for select to authenticated
    using (branch_id = current_branch_id());

create policy "rep reads stock in own branch"
    on site_stock for select to authenticated
    using (
        exists (
            select 1 from site s
             where s.id = site_id
               and s.branch_id = current_branch_id()
        )
    );

-- -----------------------------------------------------------------------------
-- One read for the whole screen
--
-- The sites the rep may pick from, which one is being shown, its stock, and how
-- old that stock is. Assembled together so the picker and the list cannot end up
-- describing different warehouses.
--
-- A null site means "the first one", which is the common case: most branches
-- issue from a single warehouse and the rep should not have to choose before
-- seeing anything.
-- -----------------------------------------------------------------------------

create or replace function site_stock_list(p_site_id uuid default null)
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_sites jsonb;
    v_site  uuid;
begin
    if current_salesperson_id() is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    select coalesce(jsonb_agg(jsonb_build_object(
               'site_id', s.id,
               'code',    s.code,
               'name',    s.name,
               'address', s.address
           ) order by s.code), '[]'::jsonb)
      into v_sites
      from site s
     where s.is_active;

    -- Falls back rather than failing: a stale site id from a screen left open
    -- should show the branch's stock, not an error.
    select s.id into v_site
      from site s
     where s.is_active
       and (p_site_id is null or s.id = p_site_id)
     order by (s.id = p_site_id) desc, s.code
     limit 1;

    return jsonb_build_object(
        'sites',   v_sites,
        'site_id', v_site,
        'items',   coalesce((
            select jsonb_agg(jsonb_build_object(
                       'product_id',   p.id,
                       'product_code', p.code,
                       'product_name', p.name,
                       'base_uom',     p.base_uom,
                       'qty_base',     st.qty_base,
                       'updated_at',   st.updated_at
                   ) order by p.code)
              from site_stock st
              join product p on p.id = st.product_id
             where st.site_id = v_site
               and p.is_active
        ), '[]'::jsonb)
    );
end;
$$;

revoke execute on function site_stock_list(uuid) from public;
grant execute on function site_stock_list(uuid) to authenticated;
