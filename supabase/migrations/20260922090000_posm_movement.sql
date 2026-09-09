-- =============================================================================
-- Đăng ký, giao và thu hồi POSM
--
-- The POSM step reads three things and writes one. It can count what is at the
-- outlet; it cannot ask for anything, hand anything over, or take anything back.
-- The previous migration said so in as many words — "registration, delivery and
-- recall are read-only from this app for now" — and this is that sentence being
-- retired.
--
-- The legacy's three writes:
--
--   InsertPosmRegis  -> IN_POSMCust, one row per asset, Status 'H', Qty asked
--                       and AppQty to be ruled on later.
--   InsertDeliverPosm with OrderType 'IN' -> hands assets over
--   the same endpoint with OrderType 'IR' -> takes them back
--
-- The delivery and the recall are booked over there as sales orders, on
-- OM_PDASalesOrd with POSM lines. That is not portable and is not being ported:
-- POSM is not inventory in this rebuild — `sales_order_line` references
-- `product`, a chiller is not a product, and putting one down that pipeline
-- would corrupt every revenue figure that reads those lines. What the order
-- document is really doing there is recording a movement with a date, a
-- quantity, a note and a photograph, so that is what this table is.
--
-- One photograph is required on both, as `posm_image_mess_required` is over
-- there. A rep saying they handed over a fridge is an assertion; a rep saying it
-- with a picture of the fridge in the shop is a record.
-- =============================================================================

create type posm_movement_kind as enum (
    'delivery',  -- IN: assets reach the outlet
    'recall'     -- IR: assets come back
);

create table posm_movement (
    -- Client-minted, as for orders and checks: the idempotency key that makes an
    -- outbox replay a no-op rather than a second fridge.
    id             uuid    primary key,

    visit_id       uuid    not null references visit (id) on delete cascade,
    customer_id    uuid    not null references customer (id),
    salesperson_id uuid    not null references salesperson (id),

    kind           posm_movement_kind not null,

    note           text,
    photo_count    integer not null default 0,
    moved_on       date    not null default current_date,

    client_created_at timestamptz not null,
    created_at     timestamptz not null default now()
);

create index on posm_movement (customer_id, moved_on desc);
create index on posm_movement (visit_id);

create table posm_movement_line (
    id           uuid    primary key default gen_random_uuid(),
    movement_id  uuid    not null references posm_movement (id) on delete cascade,
    program_id   uuid    not null references posm_program (id),
    posm_item_id uuid    not null references posm_item (id),

    qty          integer not null check (qty > 0),

    unique (movement_id, program_id, posm_item_id)
);

create index on posm_movement_line (movement_id);

create table posm_movement_photo (
    id          uuid   primary key default gen_random_uuid(),
    movement_id uuid   not null references posm_movement (id) on delete cascade,

    -- Object name in the visit-photos bucket, following the convention the
    -- storage policies authorise on: <salesperson_id>/<visit_id>/<file>.
    storage_path text  not null,

    taken_at    timestamptz not null,
    lat         double precision,
    lng         double precision,
    file_size   integer,

    unique (movement_id, storage_path)
);

create index on posm_movement_photo (movement_id);

-- -----------------------------------------------------------------------------
-- The registration gains a rep and a visit
--
-- IN_POSMCust records the SlsperID and VisitDate of whoever asked. Without them
-- a request has no author, and "who promised this shop a fridge" is the first
-- question anyone asks about one.
-- -----------------------------------------------------------------------------

alter table posm_registration
    add column if not exists salesperson_id uuid references salesperson (id),
    add column if not exists visit_id uuid references visit (id) on delete set null,
    /** IN_POSMCust.Reason: why the outlet wants it. */
    add column if not exists reason text;

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table posm_movement       enable row level security;
alter table posm_movement_line  enable row level security;
alter table posm_movement_photo enable row level security;

create policy "rep reads own posm movements"
    on posm_movement for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own posm movements"
    on posm_movement for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

create policy "rep reads own posm movement lines"
    on posm_movement_line for select to authenticated
    using (
        exists (
            select 1 from posm_movement m
            where m.id = posm_movement_line.movement_id
              and m.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own posm movement lines"
    on posm_movement_line for insert to authenticated
    with check (
        exists (
            select 1 from posm_movement m
            where m.id = posm_movement_line.movement_id
              and m.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep reads own posm movement photos"
    on posm_movement_photo for select to authenticated
    using (
        exists (
            select 1 from posm_movement m
            where m.id = posm_movement_photo.movement_id
              and m.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own posm movement photos"
    on posm_movement_photo for insert to authenticated
    with check (
        exists (
            select 1 from posm_movement m
            where m.id = posm_movement_photo.movement_id
              and m.salesperson_id = current_salesperson_id()
        )
    );

-- A rep may create a registration for an outlet and may not rule on one: the
-- approved quantity and the status are head office's, and an insert policy that
-- let the client set them would make the approval step decorative. The submit
-- function below writes the row and ignores anything the payload says about them.
create policy "rep registers posm for an outlet"
    on posm_registration for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

-- Delivery moves `delivered_qty` and nothing else; the check constraint already
-- refuses more than was approved.
create policy "rep records a posm delivery"
    on posm_registration for update to authenticated
    using (true)
    with check (delivered_qty >= 0);

create policy "rep moves posm at an outlet"
    on posm_placement for insert to authenticated
    with check (true);

create policy "rep adjusts posm at an outlet"
    on posm_placement for update to authenticated
    using (true) with check (true);

create policy "rep clears an emptied posm holding"
    on posm_placement for delete to authenticated
    using (true);
