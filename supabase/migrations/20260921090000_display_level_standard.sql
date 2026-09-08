-- =============================================================================
-- Chuẩn của mức trưng bày: ảnh mẫu và hàng bắt buộc
--
-- The rep is asked "đạt hay không đạt" against a standard they cannot currently
-- see. The programme carries a paragraph of specification and a facing count,
-- and that is all — so the answer is a judgement call made from memory.
--
-- The legacy hands them two more things, and both are on the list the audit
-- screen is opened from:
--
--   OM_TDisplayLevel.DisplayImage .. DisplayImageSixth -> display_program_level_image
--       Up to six photographs of what the shelf is supposed to look like.
--       API_GetListDisplay returns them as a second result set for exactly this
--       screen, unioning the six columns into rows — which is what this table is,
--       with the arbitrary ceiling of six dropped.
--
--   OM_TDisplayLevelInvt -> display_program_level_item
--       Which products the level wants on that shelf, how many of each, and
--       whether each is compulsory. Without it "12 facings" says nothing about
--       twelve facings *of what*.
--
-- Deliberately not ported, and worth naming rather than leaving as silence:
--
--   The AI branch. MarkDisPlayAI, FetchConfigDisplayAI, FetchDisplayResult and
--   the GenTech panorama and detection endpoints score a shelf photograph with
--   Azure Custom Vision and count facings per product from it. That is an
--   external service and a contract with a vendor, not a schema; the manual
--   count this app already takes is the same number the legacy stores in
--   FaceRemark, and PPC_DisplayRemark keeps the AI's answer in separate columns
--   beside it rather than instead of it.
--
--   ApplyTime and ClosedT2. A programme is audited once ('S') or once per sales
--   cycle ('L'), and OM_TDisplayCustomerApproveDetail.ClosedT2 takes it off an
--   outlet's list once head office has settled it. Both need SI_Cycle, a
--   calendar this rebuild has no equivalent of, and inventing one to serve a
--   flag would be the tail wagging the dog.
--
--   MinSalesPerMonth, MinSalesQtyCycle, QtyRequired. Back-office qualification
--   arithmetic run over sales history, not anything the rep at the shelf does.
-- =============================================================================

create table display_program_level_image (
    id         uuid    primary key default gen_random_uuid(),
    level_id   uuid    not null references display_program_level (id) on delete cascade,

    /** Where the reference photo lives. Head office's, not a rep's upload. */
    image_url  text    not null,

    /** What it shows, when one picture needs a word. "Nhìn từ chính diện". */
    caption    text,

    sort_order integer not null default 0,

    unique (level_id, image_url)
);

create index on display_program_level_image (level_id);

create table display_program_level_item (
    id          uuid    primary key default gen_random_uuid(),
    level_id    uuid    not null references display_program_level (id) on delete cascade,
    product_id  uuid    not null references product (id),

    /** OM_TDisplayLevelInvt.Qty: how many facings of this product the level wants. */
    qty         integer not null check (qty > 0),

    /** Its Unit, kept as text because it labels the number rather than pricing it. */
    unit_name   text    not null default 'Mặt',

    /**
     * IsRequired. An optional line is one of several that may make the count up;
     * a required one has to be there whatever else is.
     */
    is_required boolean not null default true,

    sort_order  integer not null default 0,

    unique (level_id, product_id)
);

create index on display_program_level_item (level_id);

alter table display_program_level_image enable row level security;
alter table display_program_level_item  enable row level security;

-- Head office's, and every rep reads all of it: the standard is not secret, and
-- a rep who cannot see it cannot be asked to score against it.
create policy "everyone reads display level images" on display_program_level_image
    for select to authenticated using (true);

create policy "everyone reads display level items" on display_program_level_item
    for select to authenticated using (true);

-- -----------------------------------------------------------------------------
-- The listing carries the standard
--
-- Both travel with the programme rather than through a second call. The rep opens
-- the list, picks a programme and is looking at the shelf within a second or two;
-- a round trip in between is a round trip made while standing in a shop on 2G.
-- -----------------------------------------------------------------------------

drop function if exists display_programs_for(uuid, uuid);

create function display_programs_for(p_customer_id uuid, p_visit_id uuid)
returns table (
    program_id     uuid,
    program_code   text,
    program_name   text,
    specification  text,
    from_date      date,
    to_date        date,
    registered     boolean,
    registration_status text,
    level_id       uuid,
    level_code     text,
    level_name     text,
    required_faces integer,
    bonus_amount   bigint,
    audit_id       uuid,
    counted_faces  integer,
    achieved       boolean,
    photo_count    integer,
    sample_images  jsonb,
    required_items jsonb
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select branch_id from salesperson where id = current_salesperson_id()
    ),
    live as (
        select p.*
        from display_program p, me
        where p.is_active
          and current_date between p.from_date and p.to_date
          and (p.branch_id is null or p.branch_id = me.branch_id)
    ),
    -- Registered: the outlet signed up, at the level it signed up for.
    registered as (
        select
            l.id as program_id, l.code as program_code, l.name as program_name,
            l.specification, l.from_date, l.to_date,
            true as registered, r.status as registration_status,
            lv.id as level_id, lv.code as level_code, lv.name as level_name,
            lv.required_faces, lv.bonus_amount
        from live l
        join display_registration r
            on r.program_id = l.id
           and r.customer_id = p_customer_id
           and r.status <> 'rejected'
        join display_program_level lv on lv.id = r.level_id
        where l.requires_registration
    ),
    -- Open to all: no registration, so the lowest level is the bar.
    open_to_all as (
        select
            l.id, l.code, l.name, l.specification, l.from_date, l.to_date,
            false, null::text,
            lv.id, lv.code, lv.name, lv.required_faces, lv.bonus_amount
        from live l
        join lateral (
            select *
            from display_program_level x
            where x.program_id = l.id
            order by x.required_faces, x.sort_order
            limit 1
        ) lv on true
        where not l.requires_registration
    ),
    every as (
        select * from registered
        union all
        select * from open_to_all
    )
    select
        b.program_id, b.program_code, b.program_name, b.specification,
        b.from_date, b.to_date, b.registered, b.registration_status,
        b.level_id, b.level_code, b.level_name, b.required_faces, b.bonus_amount,
        a.id, a.counted_faces, a.achieved, coalesce(a.photo_count, 0),
        coalesce((
            select jsonb_agg(jsonb_build_object(
                'image_url', i.image_url,
                'caption', i.caption
            ) order by i.sort_order, i.image_url)
            from display_program_level_image i
            where i.level_id = b.level_id
        ), '[]'::jsonb),
        coalesce((
            select jsonb_agg(jsonb_build_object(
                'product_id', pr.id,
                'product_code', pr.code,
                'product_name', pr.name,
                'qty', it.qty,
                'unit_name', it.unit_name,
                'is_required', it.is_required
            ) order by it.sort_order, pr.name)
            from display_program_level_item it
            join product pr on pr.id = it.product_id
            where it.level_id = b.level_id
        ), '[]'::jsonb)
    from every b
    left join display_audit a
        on a.visit_id = p_visit_id and a.program_id = b.program_id
    order by b.program_name;
$$;

comment on function display_programs_for(uuid, uuid) is
    'Display programmes this outlet is audited on today, each with the standard its level sets and the visit''s own result. The legacy API_GetListDisplay, both of its result sets.';
