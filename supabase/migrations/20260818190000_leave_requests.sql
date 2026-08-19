-- =============================================================================
-- Leave requests
--
-- The rep asks for time off; somebody else decides. That second half is not in
-- this app — there is no supervisor role here, and inventing one would be
-- inventing an org chart. Head office decides through the back office, and the
-- app's job is to submit, show the answer, and let a rep withdraw a request
-- nobody has ruled on yet.
--
-- Leave types get a table rather than joining the reason_code enum. Overloading
-- that enum would have meant `alter type ... add value`, which cannot be used in
-- the same transaction that adds it — two migrations to save one small table,
-- and leave types are their own vocabulary anyway: they carry whether the day is
-- paid, which no reason code does.
-- =============================================================================

create extension if not exists btree_gist;

create table leave_type (
    id          uuid    primary key default gen_random_uuid(),
    code        text    not null unique,
    name        text    not null,
    /** Whether the day is paid. Head office's rule, recorded not derived. */
    is_paid     boolean not null default true,
    sort_order  integer not null default 0,
    is_active   boolean not null default true
);

alter table leave_type enable row level security;

create policy "reference readable by authenticated"
    on leave_type for select to authenticated using (true);

create type leave_status as enum ('pending', 'approved', 'rejected', 'cancelled');

create table leave_request (
    id              uuid    primary key default gen_random_uuid(),
    salesperson_id  uuid    not null references salesperson (id) on delete cascade,
    branch_id       uuid    not null references branch (id),
    leave_type_id   uuid    not null references leave_type (id),

    from_date       date    not null,
    to_date         date    not null,
    reason          text    not null
        constraint leave_reason_not_blank check (length(btrim(reason)) > 0),

    status          leave_status not null default 'pending',
    /** Why it was refused, or any condition attached to an approval. */
    decision_note   text,
    decided_at      timestamptz,

    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    constraint leave_period_valid check (to_date >= from_date),

    -- A decided request has a time on the decision; an undecided one does not.
    constraint leave_decision_complete check (
        (status in ('pending', 'cancelled') and decided_at is null)
        or
        (status in ('approved', 'rejected') and decided_at is not null)
    ),

    -- Two live requests covering the same day are a mess nobody can resolve
    -- afterwards: approve both and the rep is off twice, approve one and which?
    -- Withdrawn and refused requests are excluded, so a rep can ask again for the
    -- same week after being turned down.
    constraint leave_no_overlap exclude using gist (
        salesperson_id with =,
        daterange(from_date, to_date, '[]') with &&
    ) where (status in ('pending', 'approved'))
);

create index on leave_request (salesperson_id, from_date desc);

create trigger leave_request_set_updated_at
    before update on leave_request
    for each row execute function set_updated_at();

-- -----------------------------------------------------------------------------
-- A rep may not decide their own request
--
-- RLS can say which rows a rep may touch; it cannot say which column values they
-- may move between. Without this a rep could update their own row straight to
-- 'approved' — the policy below would allow it, because the row is theirs.
--
-- Withdrawal is the one transition they own, and only from pending: cancelling an
-- approved absence is a conversation with a manager, not a button.
--
-- auth.uid() is null for the service role, which is how head office writes. The
-- guard therefore applies to people and not to the back office.
-- -----------------------------------------------------------------------------

create or replace function leave_request_rep_transition()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if (select auth.uid()) is null then
        return new;
    end if;

    if new.status is distinct from old.status
       and not (old.status = 'pending' and new.status = 'cancelled') then
        raise exception 'chỉ có thể huỷ đơn đang chờ duyệt';
    end if;

    return new;
end;
$$;

create trigger leave_request_rep_transition
    before update on leave_request
    for each row execute function leave_request_rep_transition();

alter table leave_request enable row level security;

create policy "rep reads own leave"
    on leave_request for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep asks for own leave"
    on leave_request for insert to authenticated
    with check (
        salesperson_id = current_salesperson_id()
        and branch_id = current_branch_id()
        -- Submitted pending, never pre-approved.
        and status = 'pending'
        and decided_at is null
    );

create policy "rep withdraws own leave"
    on leave_request for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- -----------------------------------------------------------------------------
-- The list
--
-- Newest period first, which is the order a rep looks for the one they just
-- submitted.
-- -----------------------------------------------------------------------------

create or replace function leave_requests()
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_sp uuid := current_salesperson_id();
begin
    if v_sp is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    return jsonb_build_object(
        'types', coalesce((
            select jsonb_agg(jsonb_build_object(
                       'leave_type_id', t.id,
                       'code',          t.code,
                       'name',          t.name,
                       'is_paid',       t.is_paid
                   ) order by t.sort_order, t.name)
              from leave_type t
             where t.is_active
        ), '[]'::jsonb),
        'requests', coalesce((
            select jsonb_agg(jsonb_build_object(
                       'request_id',    r.id,
                       'leave_type_id', t.id,
                       'type_name',     t.name,
                       'is_paid',       t.is_paid,
                       'from_date',     r.from_date,
                       'to_date',       r.to_date,
                       'reason',        r.reason,
                       'status',        r.status,
                       'decision_note', r.decision_note,
                       'decided_at',    r.decided_at
                   ) order by r.from_date desc)
              from leave_request r
              join leave_type t on t.id = r.leave_type_id
             where r.salesperson_id = v_sp
        ), '[]'::jsonb)
    );
end;
$$;

revoke execute on function leave_requests() from public;
grant execute on function leave_requests() to authenticated;

-- -----------------------------------------------------------------------------
-- The vocabulary. Small, stable, and needed for the form to work at all.
-- -----------------------------------------------------------------------------

insert into leave_type (id, code, name, is_paid, sort_order) values
    ('00000000-0000-0000-0000-0000000000f8', 'ANNUAL',  'Nghi phep nam',    true,  1),
    ('00000000-0000-0000-0000-0000000000f9', 'SICK',    'Nghi om',          true,  2),
    ('00000000-0000-0000-0000-0000000000fc', 'UNPAID',  'Nghi khong luong', false, 3),
    ('00000000-0000-0000-0000-0000000000fd', 'OTHER',   'Ly do khac',       false, 4)
on conflict (id) do nothing;
