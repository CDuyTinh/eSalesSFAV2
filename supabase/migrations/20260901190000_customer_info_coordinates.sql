-- =============================================================================
-- Coordinates on the customer detail payload
--
-- The header on the outlet's screen carries a map button, the way the legacy one
-- did. Without the outlet's position that button is decoration — it would have
-- to open a map centred on nothing, or be drawn and do nothing at all.
--
-- Only two fields added; everything else about customer_info is unchanged. The
-- whole body is repeated because create-or-replace has no other form.
-- =============================================================================

create or replace function customer_info(
    p_customer_id uuid,
    p_on          date default current_date
)
returns jsonb
language plpgsql
stable
security invoker
set search_path = public, pg_temp
as $$
declare
    v_month_start date := date_trunc('month', p_on)::date;
    v_month_end   date := (date_trunc('month', p_on) + interval '1 month - 1 day')::date;
    v_result      jsonb;
begin
    select jsonb_build_object(
               'customer_id',    c.id,
               'code',           c.code,
               'name',           c.name,
               'phone',          c.phone,
               'address',        c.address,
               'avatar_url',     c.avatar_url,
               -- Null on an outlet nobody has geocoded yet, which is normal for
               -- one a rep registered in the field. The button hides rather than
               -- opening a map on the null island off West Africa.
               'lat',            c.lat,
               'lng',            c.lng,
               -- The legacy proc substitutes the outlet name when Attn is empty,
               -- which reads on screen as though a contact had been recorded when
               -- none was. Left null here; the screen has room to say so plainly.
               'contact_name',   c.contact_name,
               'channel_name',   ch.name,
               'class_name',     cc.name,
               'shop_type_name', st.name,
               'credit_limit',   c.credit_limit,
               'month_revenue',  coalesce((
                   select sum(o.total_amount)
                     from sales_order o
                    where o.customer_id = c.id
                      and o.order_date between v_month_start and v_month_end
                      and o.status <> 'cancelled'
               ), 0)
           )
      into v_result
      from customer c
      left join sales_channel  ch on ch.id = c.channel_id
      left join customer_class cc on cc.id = c.class_id
      left join shop_type      st on st.id = c.shop_type_id
     where c.id = p_customer_id;

    -- Null, not an empty object. An outlet in another branch is invisible to
    -- this rep, and the caller should say "not found" rather than render a card
    -- of blank rows that looks like missing data.
    return v_result;
end;
$$;

revoke execute on function customer_info(uuid, date) from public;
grant execute on function customer_info(uuid, date) to authenticated;
