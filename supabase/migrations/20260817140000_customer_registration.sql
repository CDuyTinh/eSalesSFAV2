-- =============================================================================
-- Customers registered in the field
--
-- A rep meets an outlet that is not on any route and cannot sell to it, because
-- every write in the app hangs off a customer row and there is no way to make
-- one. This adds that way, and the two things it needs to stay honest: who
-- created the row, and whether head office has accepted it.
--
-- The customer table is reused rather than a staging table added. A registration
-- that lived somewhere else would have to be copied into `customer` on approval,
-- and until then the rep could not check in, order, or count stock against it —
-- every one of those is a foreign key to `customer`. The approval status rides
-- on the row instead, which is also how the legacy app modelled it.
-- =============================================================================

create type customer_approval as enum ('pending', 'approved', 'rejected');

alter table customer
    -- 'approved' as the default is deliberate: every row already here was put
    -- there by head office, and so is every future one loaded the same way. A
    -- rep cannot reach this default — the insert policy below refuses anything
    -- but 'pending' from them.
    add column approval_status customer_approval not null default 'approved',
    add column created_by_salesperson_id uuid references salesperson (id),
    -- What the rep wants head office to know when they look at it. Not the
    -- outlet's address or anything the columns already hold.
    add column registration_note text;

comment on column customer.approval_status is
    'pending until head office accepts a rep-registered outlet. Rows loaded by '
    'head office are approved by definition.';

comment on column customer.created_by_salesperson_id is
    'The rep who registered this outlet in the field. Null for rows head office loaded.';

create index on customer (created_by_salesperson_id, approval_status);

-- -----------------------------------------------------------------------------
-- Codes
--
-- Head office numbers its customers KH001, KH002. A rep-registered outlet takes
-- a namespace of its own — NEW-BR01-0001 — for two reasons: it cannot collide
-- with the next code head office assigns, and anyone reading a report can see at
-- a glance which rows came in from the field and are still provisional.
--
-- The advisory lock is what makes the number safe. Two reps registering in the
-- same branch in the same second would otherwise both read the same max and both
-- propose the same code, and one of them would lose to the unique constraint
-- after having filled in a whole form. The lock is per branch and released at
-- commit, so it serialises the pair without touching anyone else.
-- -----------------------------------------------------------------------------

create or replace function next_customer_registration_code(p_branch_id uuid)
returns text
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_prefix text;
    v_next   integer;
begin
    perform pg_advisory_xact_lock(hashtext(p_branch_id::text));

    select 'NEW-' || code || '-' into v_prefix from branch where id = p_branch_id;
    if v_prefix is null then
        raise exception 'no such branch: %', p_branch_id;
    end if;

    select coalesce(max(substring(code from '\d+$')::integer), 0) + 1
      into v_next
      from customer
     where branch_id = p_branch_id
       and code like v_prefix || '%';

    return v_prefix || lpad(v_next::text, 4, '0');
end;
$$;

revoke execute on function next_customer_registration_code(uuid) from public;
grant execute on function next_customer_registration_code(uuid) to authenticated;

-- -----------------------------------------------------------------------------
-- Who may create one
--
-- Reading is already allowed: "rep reads customers in own branch" covers a row
-- the moment it exists, so a rep sees what they just registered without a policy
-- of its own.
--
-- The three conditions together are what stop this being a hole. Own branch, so
-- a rep cannot plant an outlet in someone else's. Attributed to themselves, so
-- the row cannot be laid at a colleague's door. Pending, so registering is never
-- the same act as being approved — that decision stays with head office, which
-- writes through the service role and is not subject to this policy.
-- -----------------------------------------------------------------------------

create policy "rep registers customers in own branch"
    on customer for insert to authenticated
    with check (
        branch_id = current_branch_id()
        and created_by_salesperson_id = current_salesperson_id()
        and approval_status = 'pending'
    );
