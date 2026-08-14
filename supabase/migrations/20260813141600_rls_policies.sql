-- =============================================================================
-- Row Level Security
--
-- The legacy backend scoped every query by hand:
--     { "@BranchID", _identity.GetUserBranch() },
--     { "@SlsperID", _identity.GetUserName() },
-- repeated across ~56 stored procedures. Forget it once and a rep sees another
-- branch's customers. Here the scoping lives in the database, so a missing
-- filter fails closed instead of leaking.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Helpers
--
-- security definer so the lookup itself is not subject to salesperson's own
-- RLS policy (which would recurse). search_path is pinned per Supabase's
-- linter guidance.
-- -----------------------------------------------------------------------------

create or replace function current_salesperson_id()
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select id from salesperson where user_id = (select auth.uid());
$$;

create or replace function current_branch_id()
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select branch_id from salesperson where user_id = (select auth.uid());
$$;

revoke execute on function current_salesperson_id() from public;
revoke execute on function current_branch_id() from public;
grant execute on function current_salesperson_id() to authenticated;
grant execute on function current_branch_id() to authenticated;

-- -----------------------------------------------------------------------------
-- Enable RLS everywhere. Any table without a policy is then unreadable, which
-- is the behaviour we want for anything added later and forgotten.
-- -----------------------------------------------------------------------------

alter table branch          enable row level security;
alter table salesperson     enable row level security;
alter table province        enable row level security;
alter table district        enable row level security;
alter table ward            enable row level security;
alter table customer_class  enable row level security;
alter table sales_channel   enable row level security;
alter table shop_type       enable row level security;
alter table customer        enable row level security;
alter table sales_route     enable row level security;
alter table route_customer  enable row level security;
alter table reason_code     enable row level security;
alter table visit           enable row level security;
alter table timekeeping     enable row level security;
alter table sales_step      enable row level security;
alter table app_setting     enable row level security;
alter table translation     enable row level security;

-- -----------------------------------------------------------------------------
-- Reference data: readable by any signed-in rep, writable by nobody via the
-- client. Seeded and maintained through migrations / the service role.
-- -----------------------------------------------------------------------------

create policy "reference readable by authenticated"
    on province for select to authenticated using (true);
create policy "reference readable by authenticated"
    on district for select to authenticated using (true);
create policy "reference readable by authenticated"
    on ward for select to authenticated using (true);
create policy "reference readable by authenticated"
    on customer_class for select to authenticated using (true);
create policy "reference readable by authenticated"
    on sales_channel for select to authenticated using (true);
create policy "reference readable by authenticated"
    on shop_type for select to authenticated using (true);
create policy "reference readable by authenticated"
    on reason_code for select to authenticated using (true);
create policy "reference readable by authenticated"
    on sales_step for select to authenticated using (true);
create policy "reference readable by authenticated"
    on app_setting for select to authenticated using (true);
create policy "reference readable by authenticated"
    on translation for select to authenticated using (true);

-- -----------------------------------------------------------------------------
-- Identity
-- -----------------------------------------------------------------------------

create policy "rep reads own profile"
    on salesperson for select to authenticated
    using (user_id = (select auth.uid()));

-- Device binding: a rep may claim/refresh their own device_id, nothing else.
create policy "rep updates own device binding"
    on salesperson for update to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

create policy "rep reads own branch"
    on branch for select to authenticated
    using (id = current_branch_id());

-- -----------------------------------------------------------------------------
-- Customers & routes — scoped to the rep's own branch and routes
-- -----------------------------------------------------------------------------

create policy "rep reads customers in own branch"
    on customer for select to authenticated
    using (branch_id = current_branch_id());

create policy "rep reads own routes"
    on sales_route for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep reads own route lines"
    on route_customer for select to authenticated
    using (
        exists (
            select 1
            from sales_route r
            where r.id = route_customer.sales_route_id
              and r.salesperson_id = current_salesperson_id()
        )
    );

-- -----------------------------------------------------------------------------
-- Visits — a rep owns their own visit rows outright
-- -----------------------------------------------------------------------------

create policy "rep reads own visits"
    on visit for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep creates own visits"
    on visit for insert to authenticated
    with check (
        salesperson_id = current_salesperson_id()
        and branch_id = current_branch_id()
    );

create policy "rep updates own visits"
    on visit for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- No delete policy: check-in evidence is append-only from the client's side.

create policy "rep reads own timekeeping"
    on timekeeping for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep creates own timekeeping"
    on timekeeping for insert to authenticated
    with check (
        salesperson_id = current_salesperson_id()
        and branch_id = current_branch_id()
    );

-- -----------------------------------------------------------------------------
-- Storage: visit photos
--
-- Object path convention: <salesperson_id>/<visit_id>/<filename>
-- The first path segment is the owning rep, which is what we authorise on.
-- -----------------------------------------------------------------------------

create policy "rep uploads own visit photos"
    on storage.objects for insert to authenticated
    with check (
        bucket_id = 'visit-photos'
        and (storage.foldername(name))[1] = current_salesperson_id()::text
    );

create policy "rep reads own visit photos"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'visit-photos'
        and (storage.foldername(name))[1] = current_salesperson_id()::text
    );
