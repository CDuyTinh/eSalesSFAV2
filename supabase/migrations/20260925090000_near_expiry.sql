-- =============================================================================
-- Hàng cận date
--
-- The third of the legacy's ten in-call fragments and the first one this rebuild
-- never had: StockOutDateFragment, whose whole job is to walk the shelf for
-- stock that is about to expire and write down what is there, lot by lot.
--
--   PPC_TransDateHeader -> near_expiry_check   one document per call
--   PPC_TransDateLot    -> near_expiry_lot     the lots found
--   PPC_TransDateImage  -> near_expiry_photo   the evidence
--
-- It is not the stock count wearing a different hat. `stock_count` answers "how
-- many of this product are on the shelf"; this answers "which batches of it are
-- about to go off, and how many of each" — a different question with a different
-- unit of record. A lot number and an expiry date have nowhere to live in
-- `stock_count_line`, which is why that table's own comment says lots are
-- deliberately out of its scope.
--
-- Two rules come straight from the legacy form and are worth naming, because
-- both look arbitrary until you know where they come from:
--
--   A lot number is exactly eight characters (`input_lot_num_then_eght`). That
--   is the shape the ERP prints on the case, and a rep who types seven has
--   misread one. Held in the step's config rather than hard-coded, so a market
--   whose lots are ten characters is a settings change.
--
--   The check finishes with a photograph *or* a reason for not taking one
--   (`alert_chosen_reason`). Evidence, or an explanation of its absence —
--   never neither. The reason reuses the `photo_skipped` kind the check-in
--   already has, because it is the same question asked at a different moment.
-- =============================================================================

create table near_expiry_check (
    -- Client-minted, as for orders and audits: the idempotency key that makes an
    -- outbox replay a no-op rather than a second document.
    id                 uuid        primary key,

    visit_id           uuid        not null references visit (id) on delete cascade,
    customer_id        uuid        not null references customer (id),
    salesperson_id     uuid        not null references salesperson (id),
    check_date         date        not null,

    /** PPC_TransDateHeader.Descr: what the rep wants head office to know. */
    note               text,

    /**
     * Why there is no photograph. Null when there is one; the two are exclusive
     * and `submit_near_expiry_check` refuses a document with neither.
     */
    no_photo_reason_id uuid        references reason_code (id),

    -- Denormalised so the workflow list can say "4 lô" without pulling them.
    lot_count          integer     not null default 0,
    total_qty          integer     not null default 0,
    photo_count        integer     not null default 0,

    lat                double precision,
    lng                double precision,

    client_created_at  timestamptz not null,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),

    -- One document per visit. Redoing the step replaces the earlier attempt
    -- rather than leaving two lists with no way to tell which is current.
    unique (visit_id)
);

create index on near_expiry_check (customer_id, check_date desc);
create index on near_expiry_check (salesperson_id, check_date desc);

create trigger near_expiry_check_set_updated_at
    before update on near_expiry_check
    for each row execute function set_updated_at();

create table near_expiry_lot (
    id          uuid    primary key default gen_random_uuid(),
    check_id    uuid    not null references near_expiry_check (id) on delete cascade,
    product_id  uuid    not null references product (id),

    /** LotSerNbr. Its length is the step's business, not this column's. */
    lot_no      text    not null,

    /** ExpDate: when this batch goes off. */
    expiry_date date    not null,

    -- Counted in whatever unit the rep was holding, with the conversion beside
    -- it, exactly as the stock count does. A case of twenty-four and twenty-four
    -- singles are the same quantity and a different sentence.
    uom_code    text    not null references uom (code),
    qty         integer not null check (qty > 0),
    base_qty    integer not null check (base_qty > 0),

    -- One row per batch of a product. Two lines for the same lot number of the
    -- same product is a rep entering it twice, which the legacy form refuses
    -- outright (`dup_lot_number`).
    unique (check_id, product_id, lot_no)
);

create index on near_expiry_lot (check_id);
create index on near_expiry_lot (product_id, expiry_date);

create table near_expiry_photo (
    id           uuid   primary key default gen_random_uuid(),
    check_id     uuid   not null references near_expiry_check (id) on delete cascade,

    -- Object name in the visit-photos bucket, following the convention the
    -- storage policies authorise on: <salesperson_id>/<visit_id>/<file>.
    storage_path text   not null,

    taken_at     timestamptz not null,
    lat          double precision,
    lng          double precision,
    file_size    integer,

    unique (check_id, storage_path)
);

create index on near_expiry_photo (check_id);

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table near_expiry_check enable row level security;
alter table near_expiry_lot   enable row level security;
alter table near_expiry_photo enable row level security;

create policy "rep reads own near expiry checks"
    on near_expiry_check for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own near expiry checks"
    on near_expiry_check for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

-- The submit function counts the lots and photos it stored and writes the totals
-- back, so the update policy has to exist. Learned the hard way twice now: an
-- UPDATE with no policy behind it matches no rows rather than failing.
create policy "rep updates own near expiry checks"
    on near_expiry_check for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

create policy "rep deletes own near expiry checks"
    on near_expiry_check for delete to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep reads own near expiry lots"
    on near_expiry_lot for select to authenticated
    using (
        exists (
            select 1 from near_expiry_check c
            where c.id = near_expiry_lot.check_id
              and c.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own near expiry lots"
    on near_expiry_lot for insert to authenticated
    with check (
        exists (
            select 1 from near_expiry_check c
            where c.id = near_expiry_lot.check_id
              and c.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep reads own near expiry photos"
    on near_expiry_photo for select to authenticated
    using (
        exists (
            select 1 from near_expiry_check c
            where c.id = near_expiry_photo.check_id
              and c.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own near expiry photos"
    on near_expiry_photo for insert to authenticated
    with check (
        exists (
            select 1 from near_expiry_check c
            where c.id = near_expiry_photo.check_id
              and c.salesperson_id = current_salesperson_id()
        )
    );

-- -----------------------------------------------------------------------------
-- The step
--
-- Slotted third, where constant_app.dart puts StockOutDateFragment: after the
-- shelf has been counted and before anything is sold, because what is about to
-- expire changes what the rep should be ordering.
-- -----------------------------------------------------------------------------

update sales_step set step = step + 1 where step >= 3;

insert into sales_step (form_id, step, title_key, is_required, needs_visit, config)
values (
    'stock_out_date',
    3,
    'menu_stock_out_date',
    false,
    true,
    -- eight is `input_lot_num_then_eght`; the photo ceiling matches the other
    -- evidence-taking steps.
    '{"lot_no_length": 8, "photo_max": 4}'::jsonb
)
on conflict (form_id) do nothing;

insert into translation (lang_code, key, value)
values ('vi', 'menu_stock_out_date', 'Hàng cận date')
on conflict (lang_code, key) do update set value = excluded.value;

-- The legacy offers a list of reasons for arriving without a photograph; the
-- rebuild had exactly one, which makes a picker pointless.
insert into reason_code (code, name, kind, is_active)
values
    ('NO_PHOTO_SHOP', 'Cửa hàng không cho chụp', 'photo_skipped', true),
    ('NO_PHOTO_STOCK', 'Hàng để trong kho, không tiếp cận được', 'photo_skipped', true)
on conflict (code) do nothing;
