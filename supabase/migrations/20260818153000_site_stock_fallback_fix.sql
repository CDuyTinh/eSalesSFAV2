-- =============================================================================
-- site_stock_list: actually fall back
--
-- The previous version promised, in its own comment, that a site id it could not
-- serve — one belonging to another branch, or a stale one from a screen left
-- open overnight — would quietly show the branch's own stock instead. It did not.
--
--     where s.is_active and (p_site_id is null or s.id = p_site_id)
--     order by (s.id = p_site_id) desc, s.code
--
-- The `where` had already thrown away every other row by the time the `order by`
-- got its chance to prefer one, so an unmatched id selected nothing and the
-- function returned a null site with an empty list. The screen would have shown
-- an empty warehouse rather than the fallback, which is the failure the fallback
-- existed to prevent.
--
-- The preference belongs in the ordering alone. `is not distinct from` rather
-- than `=` because p_site_id is null on the common path, and null = anything is
-- null, which sorts as though nothing were preferred.
-- =============================================================================

create or replace function site_stock_list(p_site_id uuid default null)
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_sites jsonb;
    v_site  uuid;
begin
    if current_salesperson_id() is null then
        raise exception 'no salesperson is linked to this account';
    end if;

    select coalesce(jsonb_agg(jsonb_build_object(
               'site_id', s.id,
               'code',    s.code,
               'name',    s.name,
               'address', s.address
           ) order by s.code), '[]'::jsonb)
      into v_sites
      from site s
     where s.is_active;

    select s.id into v_site
      from site s
     where s.is_active
     order by (s.id is not distinct from p_site_id) desc, s.code
     limit 1;

    return jsonb_build_object(
        'sites',   v_sites,
        'site_id', v_site,
        'items',   coalesce((
            select jsonb_agg(jsonb_build_object(
                       'product_id',   p.id,
                       'product_code', p.code,
                       'product_name', p.name,
                       'base_uom',     p.base_uom,
                       'qty_base',     st.qty_base,
                       'updated_at',   st.updated_at
                   ) order by p.code)
              from site_stock st
              join product p on p.id = st.product_id
             where st.site_id = v_site
               and p.is_active
        ), '[]'::jsonb)
    );
end;
$$;
