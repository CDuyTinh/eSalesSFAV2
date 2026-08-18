-- =============================================================================
-- Work notes
--
-- The rep's own to-do list: things they mean to come back to, optionally about a
-- particular outlet, closed off with a note of what actually happened.
--
-- Wholly personal. Nothing here is assigned by head office and nothing is
-- reported upward — that is what makes it different from the task features still
-- unbuilt, and why it needs no schema anyone else has to agree to.
-- =============================================================================

create type work_note_status as enum ('open', 'done');

create table work_note (
    id              uuid    primary key default gen_random_uuid(),
    salesperson_id  uuid    not null references salesperson (id) on delete cascade,

    /** The outlet it is about, when it is about one. */
    customer_id     uuid    references customer (id) on delete set null,

    title           text    not null
        constraint work_note_title_not_blank check (length(btrim(title)) > 0),
    body            text,
    due_on          date,

    status          work_note_status not null default 'open',

    /**
     * What came of it. Required to close a note and forbidden while it is open —
     * see the constraint below.
     */
    result          text,
    done_at         timestamptz,

    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),

    -- A note is either open with nothing recorded against it, or done with both
    -- an outcome and a time. Enforced here rather than in the app because the
    -- alternative is a "done" row that says nothing about what was done, and
    -- discovering that months later leaves nobody able to reconstruct it.
    constraint work_note_done_is_complete check (
        (status = 'open' and result is null and done_at is null)
        or
        (status = 'done' and length(btrim(coalesce(result, ''))) > 0 and done_at is not null)
    )
);

create index on work_note (salesperson_id, status, due_on);

create trigger work_note_set_updated_at
    before update on work_note
    for each row execute function set_updated_at();

alter table work_note enable row level security;

-- Every verb, all scoped to the author. A scratchpad is editable and deletable
-- by the person who wrote it and invisible to everyone else — unlike a receipt,
-- which is why the receivables tables have no update or delete policy at all.
create policy "rep reads own notes"
    on work_note for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own notes"
    on work_note for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

create policy "rep edits own notes"
    on work_note for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

create policy "rep deletes own notes"
    on work_note for delete to authenticated
    using (salesperson_id = current_salesperson_id());

-- -----------------------------------------------------------------------------
-- The list
--
-- Open notes first and oldest due date at the top, because that is the order a
-- rep works them in. A note with no due date sorts after the dated ones rather
-- than before: it is something to get to, not something overdue.
-- -----------------------------------------------------------------------------

create or replace function work_notes(p_status text default null)
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

    return coalesce((
        select jsonb_agg(row order by
                   (row ->> 'status') <> 'open',
                   (row ->> 'due_on') is null,
                   row ->> 'due_on',
                   row ->> 'created_at')
          from (
                select jsonb_build_object(
                           'note_id',       n.id,
                           'title',         n.title,
                           'body',          n.body,
                           'due_on',        n.due_on,
                           'status',        n.status,
                           'result',        n.result,
                           'done_at',       n.done_at,
                           'created_at',    n.created_at,
                           'customer_id',   c.id,
                           'customer_name', c.name
                       ) as row
                  from work_note n
                  left join customer c on c.id = n.customer_id
                 where n.salesperson_id = v_sp
                   and (p_status is null or n.status::text = p_status)
               ) per_note
    ), '[]'::jsonb);
end;
$$;

revoke execute on function work_notes(text) from public;
grant execute on function work_notes(text) to authenticated;
