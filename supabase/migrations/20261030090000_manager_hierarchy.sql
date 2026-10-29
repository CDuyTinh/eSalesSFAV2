-- =============================================================================
-- Sales hierarchy, for the eSalesManager app
--
-- The second app (repo eSalesManagerV2) shares this database. The legacy system
-- kept supervisors in the same table as the reps — AR_Salesperson, with Position
-- S / SS / AM / RM and the boss in SupID — and so does this: a manager is a
-- salesperson row with a position above 'sr'. That keeps timekeeping, leave and
-- work notes usable by managers without a parallel set of tables.
--
-- Only SS (giám sát) is wired up for now. team_salesperson_ids() is recursive so
-- that AM/RM get their whole subtree later without changing a single policy.
--
-- Nothing here changes what a rep can see: every policy below is a new, additive
-- SELECT policy, and Postgres ORs policies together.
-- =============================================================================

create type staff_position as enum ('sr', 'ss', 'am', 'rm');

alter table salesperson
    add column position      staff_position not null default 'sr',
    add column supervisor_id uuid references salesperson (id) on delete set null,
    add constraint salesperson_not_own_supervisor check (supervisor_id <> id);

comment on column salesperson.position is
    'sr = nhân viên bán hàng, ss = giám sát, am = quản lý khu vực, rm = quản lý vùng. Legacy AR_Salesperson.Position.';
comment on column salesperson.supervisor_id is
    'Direct boss. Legacy AR_Salesperson.SupID.';

create index salesperson_supervisor_idx on salesperson (supervisor_id);

-- -----------------------------------------------------------------------------
-- Team helpers
--
-- security definer for the same reason as current_salesperson_id(): walking
-- salesperson under its own RLS would recurse into the policy that calls this.
-- -----------------------------------------------------------------------------

create or replace function team_salesperson_ids()
returns setof uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    with recursive team (id) as (
        select s.id from salesperson s
        where s.supervisor_id = current_salesperson_id()
        union
        select s.id from salesperson s
        join team t on s.supervisor_id = t.id
    )
    select id from team;
$$;

comment on function team_salesperson_ids() is
    'Everyone below the caller in the hierarchy. Empty for a rep.';

create or replace function is_team_member(p_salesperson_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select exists (
        select 1 from team_salesperson_ids() t where t = p_salesperson_id
    );
$$;

revoke execute on function team_salesperson_ids() from public;
revoke execute on function is_team_member(uuid) from public;
grant execute on function team_salesperson_ids() to authenticated;
grant execute on function is_team_member(uuid) to authenticated;

-- -----------------------------------------------------------------------------
-- What a manager may read about the team itself. Feature tables (visits,
-- orders, leave, registrations…) get their team policies in the migration that
-- builds the manager screen reading them, not all at once here.
-- -----------------------------------------------------------------------------

create policy "manager reads team salespeople"
    on salesperson for select to authenticated
    using (id in (select team_salesperson_ids()));

create policy "manager reads team branches"
    on branch for select to authenticated
    using (id in (
        select s.branch_id from salesperson s
        where s.id in (select team_salesperson_ids())
    ));
