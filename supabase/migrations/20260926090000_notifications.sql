-- =============================================================================
-- Thông báo
--
-- The bell, and the one feature in the gap list that needs almost no new data.
--
-- API_GetPPC_Notify is not a query over a notifications table — there is no such
-- table over there. It is a UNION of seven feeds, each one a different thing
-- head office published, with a read marker joined on:
--
--   OTHER      promotion posters        AUTOPROMO  automatic promotions
--   HANDPROMO  manual promotions        DISPLAY    display programmes
--   ACCUMULATE loyalty programmes       POSM       POSM programmes
--   NOTE       working notes
--
-- Every one of those already exists here as a table of its own, so the digest is
-- a function over what the rebuild already has rather than a second copy of it.
-- That is not a shortcut: a `notification` table would need something to write a
-- row into it every time a programme was published, and nothing in this app
-- publishes programmes. A row nobody writes is the dead weight this rebuild has
-- refused three times already.
--
-- What does need storing is the only part that is the rep's own: whether they
-- have read it. PPC_NotifyHistory, one row per rep per item.
-- =============================================================================

create type notification_kind as enum (
    'promotion',        -- AUTOPROMO: a discount programme running now
    'manual_promotion', -- HANDPROMO: a discount the rep may apply by hand
    'display',          -- DISPLAY: a display programme
    'loyalty',          -- ACCUMULATE: a loyalty programme
    'posm',             -- POSM: a POSM programme
    'work_note'         -- NOTE: something the rep wrote for themselves
);

/**
 * That this rep has seen this item.
 *
 * Keyed by kind and the source row's id rather than by a foreign key, because
 * the six sources are six different tables and a column per table would be five
 * nulls on every row. The price is that a deleted programme leaves its marker
 * behind, which costs one orphan row and no correctness: the digest only ever
 * joins the other way.
 */
create table notification_read (
    id             uuid    primary key default gen_random_uuid(),
    salesperson_id uuid    not null references salesperson (id) on delete cascade,
    kind           notification_kind not null,
    source_id      uuid    not null,

    read_at        timestamptz not null default now(),

    unique (salesperson_id, kind, source_id)
);

create index on notification_read (salesperson_id, read_at desc);

alter table notification_read enable row level security;

create policy "rep reads own notification markers"
    on notification_read for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep marks own notifications read"
    on notification_read for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

-- -----------------------------------------------------------------------------
-- The digest
--
-- Everything live for this rep's branch inside the window, newest first, with
-- whether they have read it. Each row carries the id of the thing it is about so
-- the screen can open it and the marker can be written against it.
-- -----------------------------------------------------------------------------

create function notifications_for(
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date,
    p_unread_only boolean default false
)
returns table (
    kind        notification_kind,
    source_id   uuid,
    title       text,
    body        text,
    code        text,
    from_date   date,
    to_date     date,
    is_read     boolean,
    read_at     timestamptz
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    with me as (
        select id, branch_id from salesperson where id = current_salesperson_id()
    ),
    feed as (
        -- AUTOPROMO. One row per programme rather than per sequence: a rep wants
        -- to know the campaign is running, not that it has four break levels.
        select
            'promotion'::notification_kind as kind,
            p.id as source_id,
            p.name as title,
            'Chương trình khuyến mãi đang chạy' as body,
            p.code,
            min(s.from_date) as from_date,
            max(case when s.open_ended then null else s.to_date end) as to_date
        from discount_program p
        join discount_sequence s on s.program_id = p.id and s.is_active
        cross join me
        where p.is_active
          and (s.branch_id is null or s.branch_id = me.branch_id)
        group by p.id, p.name, p.code

        union all

        -- HANDPROMO.
        select
            'manual_promotion', m.id, m.name,
            'Khuyến mãi áp dụng bằng tay', m.code, m.from_date, m.to_date
        from manual_promotion m
        cross join me
        where m.is_active
          and (m.branch_id is null or m.branch_id = me.branch_id)

        union all

        select
            'display', d.id, d.name,
            case when d.requires_registration
                 then 'Chương trình trưng bày - cần đăng ký'
                 else 'Chương trình trưng bày' end,
            d.code, d.from_date, d.to_date
        from display_program d
        cross join me
        where d.is_active and (d.branch_id is null or d.branch_id = me.branch_id)

        union all

        select
            'loyalty', l.id, l.name, 'Chương trình tích lũy',
            l.code, l.from_date, l.to_date
        from loyalty_program l
        cross join me
        where l.is_active and (l.branch_id is null or l.branch_id = me.branch_id)

        union all

        select
            'posm', pr.id, pr.name, 'Chương trình POSM',
            pr.code, pr.from_date, pr.to_date
        from posm_program pr
        cross join me
        where pr.is_active and (pr.branch_id is null or pr.branch_id = me.branch_id)

        union all

        -- NOTE. The rep's own, so no branch test: a note belongs to whoever
        -- wrote it and nobody else sees it anyway. Open ones only — a note the
        -- rep has already ticked off is a record, not something still to tell.
        select
            'work_note', w.id, w.title,
            coalesce(nullif(btrim(w.body), ''), 'Ghi chú công việc'),
            null::text, w.due_on, w.due_on
        from work_note w
        where w.salesperson_id = (select id from me)
          and w.status = 'open'
    )
    select
        f.kind, f.source_id, f.title, f.body, f.code, f.from_date, f.to_date,
        r.id is not null as is_read,
        r.read_at
    from feed f
    left join notification_read r
        on r.kind = f.kind
       and r.source_id = f.source_id
       and r.salesperson_id = (select id from me)
    where
        -- Overlapping the window rather than starting in it: a campaign that
        -- began last month and runs until next is news to a rep who has not
        -- read it yet, and the legacy's own date test overlaps the same way.
        coalesce(f.from_date, p_from_date) <= p_to_date
        and coalesce(f.to_date, p_to_date) >= p_from_date
        and (not p_unread_only or r.id is null)
    order by f.from_date desc nulls last, f.title;
$$;

comment on function notifications_for(date, date, boolean) is
    'Everything head office has published that is live for this rep, with whether they have read it. API_GetPPC_Notify, as a union over the tables it unions.';

-- -----------------------------------------------------------------------------

create function unread_notification_count(
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date
)
returns integer
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select count(*)::integer
    from notifications_for(p_from_date, p_to_date, true);
$$;

comment on function unread_notification_count(date, date) is
    'What the badge shows. API_GetCountNotify.';

-- -----------------------------------------------------------------------------

create function mark_notification_read(
    p_kind      notification_kind,
    p_source_id uuid
)
returns void
language sql
security invoker
set search_path = public, pg_temp
as $$
    insert into notification_read (salesperson_id, kind, source_id)
    values (current_salesperson_id(), p_kind, p_source_id)
    -- Reading something twice is reading it once. The first time stands, so the
    -- list does not reshuffle under a rep who opens an item again.
    on conflict (salesperson_id, kind, source_id) do nothing;
$$;

comment on function mark_notification_read(notification_kind, uuid) is
    'Marks one item read for this rep. ReadNotification.';

create function mark_all_notifications_read(
    p_from_date date default (current_date - 30),
    p_to_date   date default current_date
)
returns integer
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_marked integer;
begin
    insert into notification_read (salesperson_id, kind, source_id)
    select current_salesperson_id(), n.kind, n.source_id
    from notifications_for(p_from_date, p_to_date, true) n
    on conflict (salesperson_id, kind, source_id) do nothing;

    get diagnostics v_marked = row_count;
    return v_marked;
end;
$$;

comment on function mark_all_notifications_read(date, date) is
    'Marks everything currently unread as read. ReadAllNotification.';

revoke execute on function mark_notification_read(notification_kind, uuid) from public;
grant execute on function mark_notification_read(notification_kind, uuid) to authenticated;
revoke execute on function mark_all_notifications_read(date, date) from public;
grant execute on function mark_all_notifications_read(date, date) to authenticated;
