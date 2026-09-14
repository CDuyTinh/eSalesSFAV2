-- =============================================================================
-- Địa chỉ giao hàng
--
-- An outlet is not always where its goods go. A shop with a warehouse behind it,
-- a chain whose branches order through one office, a customer who wants this
-- month's delivery sent to a second site — the legacy handles all three with
-- AR_SOAddress: many ship-to rows per customer, each with a recipient, a phone
-- and an address of its own, and the chosen ShipToID written onto the order.
--
-- Two things about the legacy's version are not carried over.
--
-- The first is how it picks a default. `_listAddress![0]` — whatever row the
-- query happened to return first, with no ordering in the proc at all. So the
-- default silently depends on the physical order of rows in a table, which is
-- to say it is not a default. Here one row per customer may be flagged.
--
-- The second is what happens when a customer has no ship-to row: line 406 of
-- confirm_order_form.dart reads `_selectedAddress.value!.shipToId!` and the app
-- goes down. Here the outlet's own address is the implicit default, which is
-- both the right answer and one fewer way to crash.
-- =============================================================================

create table customer_ship_address (
    id           uuid    primary key default gen_random_uuid(),
    customer_id  uuid    not null references customer (id) on delete cascade,

    /** `ShipToID`. Head office's own handle for the address. */
    code         text    not null,

    /** Who receives it — often the outlet, sometimes a warehouse or an office. */
    recipient    text    not null,
    phone        text,
    address      text    not null,
    ward_id      uuid    references ward (id),

    /** For invoicing to a different legal entity than the outlet. */
    tax_code     text,

    /**
     * The one to offer first.
     *
     * At most one per customer, enforced below. The legacy has no such column
     * and takes whichever row came back first; that is a default by accident,
     * and it changes when somebody reindexes.
     */
    is_default   boolean not null default false,

    is_active    boolean not null default true,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),

    unique (customer_id, code)
);

create unique index customer_ship_address_one_default
    on customer_ship_address (customer_id) where is_default;

create index on customer_ship_address (customer_id) where is_active;

create trigger customer_ship_address_set_updated_at
    before update on customer_ship_address
    for each row execute function set_updated_at();

alter table customer_ship_address enable row level security;

/**
 * Readable for the outlets the rep can see.
 *
 * Leaning on the customer's own policy rather than repeating its terms: the two
 * would drift, and the day they did the looser one would win.
 */
create policy "rep reads ship addresses of visible customers"
    on customer_ship_address for select to authenticated
    using (
        is_active
        and exists (select 1 from customer c where c.id = customer_id)
    );

-- -----------------------------------------------------------------------------

/**
 * Where this order goes.
 *
 * Null means the outlet itself, which is the common case and the reason the
 * column is nullable rather than defaulted to a row that would have to exist
 * for every customer.
 */
alter table sales_order
    add column ship_address_id uuid references customer_ship_address (id);

create index on sales_order (ship_address_id);

-- -----------------------------------------------------------------------------

/**
 * The addresses this outlet can be delivered to, the default first.
 *
 * The outlet's own address is prepended by the caller rather than stored as a
 * row here — a row nothing writes is the dead weight this rebuild keeps
 * refusing, and the outlet's address already lives on the customer.
 */
create function ship_addresses_for(p_customer_id uuid)
returns table (
    id         uuid,
    code       text,
    recipient  text,
    phone      text,
    address    text,
    tax_code   text,
    is_default boolean
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select a.id, a.code, a.recipient, a.phone,
           -- The ward, district and province spelled out, the way the legacy
           -- proc concatenates them, so the rep reads one line rather than four
           -- codes.
           a.address
             || coalesce(', ' || w.name, '')
             || coalesce(', ' || d.name, '')
             || coalesce(', ' || p.name, '')
           as address,
           a.tax_code,
           a.is_default
    from customer_ship_address a
    left join ward w on w.id = a.ward_id
    left join district d on d.id = w.district_id
    left join province p on p.id = d.province_id
    where a.customer_id = p_customer_id
      and a.is_active
    order by a.is_default desc, a.recipient;
$$;

comment on function ship_addresses_for(uuid) is
    'Ship-to addresses for one outlet, default first. API_GetCustomerAddress.';
