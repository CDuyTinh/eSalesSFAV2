-- =============================================================================
-- Visit step results
--
-- The in-call workflow is data, not code: `sales_step` says which steps exist,
-- in what order, and which are mandatory. This table records what the rep
-- actually did for a given visit.
--
-- `payload` is jsonb on purpose. A stock count, a display audit and a customer
-- note have nothing in common structurally, and giving each its own table would
-- mean a schema migration every time head office adds a step — which is exactly
-- the rigidity the configurable workflow exists to avoid. Steps that grow their
-- own reporting needs can be promoted to real tables later.
-- =============================================================================

create table visit_step_result (
    id           uuid primary key default gen_random_uuid(),
    visit_id     uuid        not null references visit (id) on delete cascade,
    form_id      text        not null references sales_step (form_id),
    completed_at timestamptz not null default now(),
    payload      jsonb       not null default '{}'::jsonb,
    created_at   timestamptz not null default now(),

    -- A step is done or not done; re-submitting updates the same row.
    unique (visit_id, form_id)
);

create index on visit_step_result (visit_id);

alter table visit_step_result enable row level security;

-- Scoped through the parent visit, which is already restricted to the rep who
-- made it. No separate salesperson column to keep in sync.
create policy "rep reads own visit step results"
    on visit_step_result for select to authenticated
    using (
        exists (
            select 1 from visit v
            where v.id = visit_step_result.visit_id
              and v.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own visit step results"
    on visit_step_result for insert to authenticated
    with check (
        exists (
            select 1 from visit v
            where v.id = visit_step_result.visit_id
              and v.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep updates own visit step results"
    on visit_step_result for update to authenticated
    using (
        exists (
            select 1 from visit v
            where v.id = visit_step_result.visit_id
              and v.salesperson_id = current_salesperson_id()
        )
    );
