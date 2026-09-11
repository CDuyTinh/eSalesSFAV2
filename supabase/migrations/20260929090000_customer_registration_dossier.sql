-- =============================================================================
-- Hồ sơ khách hàng mới
--
-- The rebuild can register an outlet and then never speak of it again: no list,
-- no detail, no edit, no delete, no photographs. The legacy has all five --
-- FetchNewCustomerList, FetchNewCustomerID, UpdateCustomerInfo, DelNewCustomer
-- and FetchNewCustomerImage -- and a rep needs them, because filing a
-- registration is the start of a wait, not the end of a task. They want to know
-- whether it was approved, fix the phone number they mistyped, and drop the one
-- they filed twice.
--
-- The photographs turn out to settle a second gap at the same time. Over there
-- they live in AR_NewCustomerInforImges keyed by `TylePic` -- 'N1', 'N2', … --
-- where the number comes from a server-configured list of slots, each with its
-- own label, its own sample picture and its own required flag. 'N1' is the one
-- every other query reaches for when it wants the outlet's picture:
--
--     ISNULL(cus.ProfilePic, nci.ImageName)  -- API_GetTaskDetail
--
-- So the customer's avatar is not a separate feature. It is the first photo slot
-- of the registration, and this models it that way rather than bolting an
-- upload onto the customer record.
-- =============================================================================

/**
 * A photo slot head office asks for. `ConfigSampleImageModel`.
 *
 * Configured rather than hard-coded because the legacy configures it: a market
 * that wants the business licence photographed adds a row, and no build ships.
 */
create table customer_photo_type (
    id          uuid    primary key default gen_random_uuid(),
    code        text    not null unique,
    name        text    not null,

    /** A picture of what a good one looks like, shown beside the camera button. */
    sample_url  text,

    is_required boolean not null default false,
    /**
     * The outlet's face. Exactly one slot may claim it, and that photo becomes
     * `customer.avatar_url` -- which is the rule `ISNULL(cus.ProfilePic,
     * nci.ImageName)` is reaching for, written down instead of implied.
     */
    is_avatar   boolean not null default false,

    sort_order  integer not null default 0,
    is_active   boolean not null default true,

    created_at  timestamptz not null default now()
);

create unique index customer_photo_type_one_avatar
    on customer_photo_type (is_avatar) where is_avatar;

alter table customer_photo_type enable row level security;

create policy "everyone reads the photo slots"
    on customer_photo_type for select to authenticated
    using (is_active);

-- -----------------------------------------------------------------------------

create table customer_registration_photo (
    id           uuid primary key default gen_random_uuid(),
    customer_id  uuid not null references customer (id) on delete cascade,
    type_id      uuid not null references customer_photo_type (id),

    storage_path text not null,
    taken_at     timestamptz,
    lat          double precision,
    lng          double precision,
    file_size    integer,

    created_at   timestamptz not null default now(),

    /** One photo per slot: re-taking replaces rather than accumulates. */
    unique (customer_id, type_id)
);

create index on customer_registration_photo (customer_id);

alter table customer_registration_photo enable row level security;

create policy "rep reads photos of outlets they registered"
    on customer_registration_photo for select to authenticated
    using (exists (
        select 1 from customer c
        where c.id = customer_id
          and c.created_by_salesperson_id = current_salesperson_id()
    ));

create policy "rep adds photos while the registration is pending"
    on customer_registration_photo for insert to authenticated
    with check (exists (
        select 1 from customer c
        where c.id = customer_id
          and c.created_by_salesperson_id = current_salesperson_id()
          and c.approval_status = 'pending'
    ));

create policy "rep replaces photos while the registration is pending"
    on customer_registration_photo for update to authenticated
    using (exists (
        select 1 from customer c
        where c.id = customer_id
          and c.created_by_salesperson_id = current_salesperson_id()
          and c.approval_status = 'pending'
    ))
    with check (exists (
        select 1 from customer c
        where c.id = customer_id
          and c.created_by_salesperson_id = current_salesperson_id()
          and c.approval_status = 'pending'
    ));

create policy "rep removes photos while the registration is pending"
    on customer_registration_photo for delete to authenticated
    using (exists (
        select 1 from customer c
        where c.id = customer_id
          and c.created_by_salesperson_id = current_salesperson_id()
          and c.approval_status = 'pending'
    ));

-- -----------------------------------------------------------------------------
-- The rep may correct or withdraw their own registration, but only while it is
-- still waiting. An approved outlet is a real customer with orders against it.
-- -----------------------------------------------------------------------------

create policy "rep edits own pending registration"
    on customer for update to authenticated
    using (
        created_by_salesperson_id = current_salesperson_id()
        and approval_status = 'pending'
    )
    with check (
        created_by_salesperson_id = current_salesperson_id()
        and approval_status = 'pending'
    );

create policy "rep withdraws own pending registration"
    on customer for delete to authenticated
    using (
        created_by_salesperson_id = current_salesperson_id()
        and approval_status = 'pending'
    );

-- -----------------------------------------------------------------------------

insert into customer_photo_type (code, name, is_required, is_avatar, sort_order)
values
    ('N1', 'Ảnh mặt tiền cửa hàng', true,  true,  10),
    ('N2', 'Ảnh biển hiệu',         false, false, 20),
    ('N3', 'Ảnh bên trong cửa hàng', false, false, 30),
    ('N4', 'Ảnh giấy phép kinh doanh', false, false, 40)
on conflict (code) do nothing;
