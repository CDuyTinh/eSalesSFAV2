-- =============================================================================
-- Who a plan can be with
--
-- The object picker needs a list of customers, and this app has never had one:
-- the rep sees a route, which is today's stops, not the outlets they cover.
--
-- The right universe is the rep's own routes, not every customer in the branch.
-- A plan to work with a shop somebody else calls on is not a plan, and the
-- legacy's picker is fed the same way -- `OM_MakingPlanObject` is filled from
-- the salesperson's assigned customers.
-- =============================================================================

create function plannable_customers(p_query text default null)
returns table (
    id      uuid,
    code    text,
    name    text,
    address text
)
language sql
stable
security invoker
set search_path = public, pg_temp
as $$
    select distinct c.id, c.code, c.name, c.address
    from customer c
    join route_customer rc on rc.customer_id = c.id and rc.is_active
    join sales_route r on r.id = rc.sales_route_id and r.is_active
    where r.salesperson_id = current_salesperson_id()
      and c.is_active
      -- Filtered server-side as well as in the picker: a rep with several
      -- hundred outlets should not be shipped all of them to type into.
      and (
          p_query is null
          or btrim(p_query) = ''
          or c.name  ilike '%' || btrim(p_query) || '%'
          or c.code  ilike '%' || btrim(p_query) || '%'
      )
    order by c.name
    limit 300;
$$;

comment on function plannable_customers(text) is
    'The outlets on this rep''s routes, for the work-plan object picker.';
