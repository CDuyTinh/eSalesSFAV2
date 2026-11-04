-- Chương trình khuyến mãi: the running promotions with their levels, and the
-- approvals badge counting display results still to decide.

do $$
declare v jsonb;
begin
    perform pg_temp.act_as('nvbh01');
    v := promotion_catalogue();
    perform pg_temp.check(jsonb_array_length(v) > 0, 'the catalogue lists running promotions');
    perform pg_temp.check(
        (select bool_and(jsonb_array_length(x -> 'levels') > 0) from jsonb_array_elements(v) x),
        'every promotion listed has its levels');
    perform pg_temp.check(
        not exists (select 1 from jsonb_array_elements(promotion_catalogue(current_date - 400)) x),
        'nothing ran four hundred days ago');

    perform pg_temp.act_as('gsbh01');
    perform pg_temp.check((mgr_approval_counts() ->> 'display_result')::int >= 1,
        'display results waiting for a decision are counted');
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
