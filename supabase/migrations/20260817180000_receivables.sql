-- =============================================================================
-- Receivables
--
-- What an outlet owes, and the money a rep collects against it at the door.
--
-- Two tables and no third. The balance of an invoice is the sum of what has been
-- paid against it subtracted from what was billed — deriving it costs one
-- aggregate and can never be wrong, while storing it would need every write path
-- to remember to move it and would be wrong the first time one did not. A
-- receivable that disagrees with its own payments is worse than no figure: the
-- rep is standing in front of the person it is about.
--
-- Invoices are head office's to create, exactly like customers and price lists.
-- Nothing in the app issues one: an order is not an invoice, and auto-issuing on
-- submit would be inventing a step of someone else's process. The app reads them
-- and writes payments, which is the whole of what a rep does here.
-- =============================================================================

create table ar_invoice (
    id             uuid primary key default gen_random_uuid(),
    branch_id      uuid   not null references branch (id),
    customer_id    uuid   not null references customer (id),

    invoice_no     text   not null,
    /** Set when the invoice came from an order this app took. Null otherwise. */
    order_id       uuid   references sales_order (id),

    issued_on      date   not null,
    due_on         date   not null,
    total_amount   bigint not null
        constraint invoice_total_positive check (total_amount > 0),

    note           text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),

    -- Invoice numbers are unique per branch, matching how customer codes work.
    unique (branch_id, invoice_no),
    constraint due_not_before_issue check (due_on >= issued_on)
);

create index on ar_invoice (customer_id, due_on);

comment on table ar_invoice is
    'What an outlet was billed. Loaded by head office; the app never issues one.';

-- -----------------------------------------------------------------------------
-- Collections
--
-- The id is supplied by the client, as it is for sales_order and for the same
-- reason: it is the idempotency key. A rep who taps save on a slow connection
-- and taps again must not have collected twice.
-- -----------------------------------------------------------------------------

create table ar_payment (
    id              uuid primary key,
    invoice_id      uuid   not null references ar_invoice (id) on delete cascade,
    salesperson_id  uuid   not null references salesperson (id),
    /** The visit it was collected during, when it was collected during one. */
    visit_id        uuid   references visit (id) on delete set null,

    amount          bigint not null
        constraint payment_positive check (amount > 0),
    collected_on    date   not null,
    note            text,

    created_at      timestamptz not null default now()
);

create index on ar_payment (invoice_id);
create index on ar_payment (salesperson_id, collected_on desc);

comment on table ar_payment is
    'Money a rep collected against an invoice. Append-only: a receipt is not editable.';

-- -----------------------------------------------------------------------------
-- No invoice may be overpaid
--
-- A trigger rather than a check in whichever caller happened to write the row.
-- Two reps collecting the last of a balance in the same minute both read the
-- same outstanding figure and both propose a payment that fits; only a rule
-- inside the transaction sees the other one. The lock makes the pair serial, and
-- serialising two payments against one invoice costs nothing measurable.
--
-- Deliberately not a check constraint: a constraint can only see the row being
-- written, and this rule is about the sum of every row for that invoice.
-- -----------------------------------------------------------------------------

-- security definer, unlike everything else here. The rule is an invariant of the
-- table, not a view of it: it has to see every payment against the invoice, and
-- a guard that could be narrowed by whoever happens to be asking is not a guard.
-- The read policy currently shows the whole branch anyway, so this changes
-- nothing today — it stops the invariant depending on that staying true.
create or replace function ar_payment_within_balance()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_total bigint;
    v_paid  bigint;
begin
    perform pg_advisory_xact_lock(hashtext(new.invoice_id::text));

    select total_amount into v_total from ar_invoice where id = new.invoice_id;
    if v_total is null then
        raise exception 'no such invoice: %', new.invoice_id;
    end if;

    select coalesce(sum(amount), 0) into v_paid
      from ar_payment
     where invoice_id = new.invoice_id
       and id <> new.id;

    if v_paid + new.amount > v_total then
        raise exception
            'số tiền thu vượt quá dư nợ của hoá đơn (còn %, thu %)',
            v_total - v_paid, new.amount;
    end if;

    return new;
end;
$$;

create trigger ar_payment_within_balance
    before insert on ar_payment
    for each row execute function ar_payment_within_balance();

-- -----------------------------------------------------------------------------
-- Row-level security
--
-- Invoices are readable for the branch, matching the customer policy they hang
-- off: a rep covering for a colleague needs to see the debt at the shop they are
-- standing in, and the customer row was already visible to them anyway.
--
-- Payments are read branch-wide and written rep-scoped, and the asymmetry is the
-- point. Scoping the read to the caller looks tighter and is wrong: the balance
-- of an invoice is the sum of every payment against it, so a rep who could not
-- see a colleague's collection would be shown a debt that has already been
-- settled and would go and ask for it again. The ledger of what an outlet owes
-- is shared; only the act of collecting is attributed.
--
-- No update or delete policy at all: a receipt that can be edited is not a
-- receipt, and correcting one is head office's job.
-- -----------------------------------------------------------------------------

alter table ar_invoice enable row level security;
alter table ar_payment enable row level security;

create policy "rep reads invoices in own branch"
    on ar_invoice for select to authenticated
    using (branch_id = current_branch_id());

create policy "rep reads payments in own branch"
    on ar_payment for select to authenticated
    using (
        exists (
            select 1
              from ar_invoice i
             where i.id = invoice_id
               and i.branch_id = current_branch_id()
        )
    );

create policy "rep records own payments"
    on ar_payment for insert to authenticated
    with check (
        salesperson_id = current_salesperson_id()
        and exists (
            select 1
              from ar_invoice i
             where i.id = invoice_id
               and i.branch_id = current_branch_id()
        )
    );

-- -----------------------------------------------------------------------------
-- What the two screens ask
--
-- The list: every outlet in the branch that still owes something, with its total
-- and whether any of it is late. The detail: one outlet's invoices with what is
-- left on each.
--
-- security invoker on both, so the policies above are what scope them.
-- -----------------------------------------------------------------------------

create or replace function receivable_customers()
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select coalesce(jsonb_agg(row order by (row ->> 'outstanding')::bigint desc), '[]'::jsonb)
      from (
            select jsonb_build_object(
                       'customer_id',   c.id,
                       'customer_code', c.code,
                       'customer_name', c.name,
                       'phone',         c.phone,
                       'address',       c.address,
                       'invoices',      count(*),
                       'outstanding',   sum(i.outstanding),
                       -- Any single late invoice makes the outlet late. A rep
                       -- needs to know before they walk in, not after totalling
                       -- the list themselves.
                       'overdue',       bool_or(i.due_on < current_date)
                   ) as row
              from (
                    select i.id,
                           i.customer_id,
                           i.due_on,
                           i.total_amount - coalesce((
                               select sum(p.amount) from ar_payment p where p.invoice_id = i.id
                           ), 0) as outstanding
                      from ar_invoice i
                   ) i
              join customer c on c.id = i.customer_id
             where i.outstanding > 0
             group by c.id, c.code, c.name, c.phone, c.address
           ) per_customer;
$$;

create or replace function receivable_invoices(p_customer_id uuid)
returns jsonb
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select coalesce(jsonb_agg(row order by (row ->> 'due_on')), '[]'::jsonb)
      from (
            select jsonb_build_object(
                       'invoice_id',  i.id,
                       'invoice_no',  i.invoice_no,
                       'issued_on',   i.issued_on,
                       'due_on',      i.due_on,
                       'total',       i.total_amount,
                       'paid',        coalesce(p.paid, 0),
                       'outstanding', i.total_amount - coalesce(p.paid, 0),
                       'note',        i.note
                   ) as row
              from ar_invoice i
              left join (
                    select invoice_id, sum(amount) as paid
                      from ar_payment
                     group by invoice_id
                   ) p on p.invoice_id = i.id
             where i.customer_id = p_customer_id
               and i.total_amount - coalesce(p.paid, 0) > 0
           ) per_invoice;
$$;

revoke execute on function receivable_customers() from public;
revoke execute on function receivable_invoices(uuid) from public;
grant execute on function receivable_customers() to authenticated;
grant execute on function receivable_invoices(uuid) to authenticated;
