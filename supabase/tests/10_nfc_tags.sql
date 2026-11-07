-- NFC tags: bound only by a rep checked in at that customer, never moved to
-- another customer, and a check-in's match decided by the database.

do $$
declare
    v_rep   uuid := pg_temp.sp('nvbh01');
    v_shop  uuid;
    v_other uuid;
    v_visit uuid := gen_random_uuid();
    v       jsonb;
begin
    -- Two customers of the rep's branch with no visit today, for a clean slate.
    select c.id into v_shop from customer c
      join salesperson s on s.id = v_rep and s.branch_id = c.branch_id
     where not exists (select 1 from visit x where x.customer_id = c.id and x.visit_date = current_date)
     order by c.code limit 1;
    select c.id into v_other from customer c
      join salesperson s on s.id = v_rep and s.branch_id = c.branch_id
     where c.id <> v_shop order by c.code limit 1;

    -- Any visit the rep has open today would block the one this test needs.
    update visit set status = 'completed', check_out_at = now()
     where salesperson_id = v_rep and status = 'in_progress';

    perform pg_temp.act_as('nvbh01');
    v := register_nfc_tag(v_shop, '04A1B2C3D4E580');
    perform pg_temp.check(v ->> 'result' = 'no_visit', 'a tag cannot be bound without being checked in there');

    perform pg_temp.act_as_admin();
    insert into visit (id, customer_id, salesperson_id, branch_id, visit_date, status, check_in_at)
    select v_visit, v_shop, v_rep, branch_id, current_date, 'in_progress', now()
      from salesperson where id = v_rep;

    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check(register_nfc_tag(v_shop, 'not a uid') ->> 'result' = 'invalid', 'garbage is refused');
    perform pg_temp.check(register_nfc_tag(v_shop, '04a1b2c3d4e580') ->> 'result' = 'registered',
        'checked in, the rep binds the tag (case does not matter)');
    perform pg_temp.check(register_nfc_tag(v_shop, '04A1B2C3D4E580') ->> 'result' = 'unchanged',
        'binding it again is harmless');
    perform pg_temp.check((nfc_tag_customer('04a1b2c3d4e580') ->> 'customer_id')::uuid = v_shop,
        'the tag leads back to its customer');
    perform pg_temp.check(nfc_tag_customer('FFFFFFFFFFFFFF') is null, 'an unknown tag finds nobody');

    -- Checked in at the other customer, the same sticker cannot be moved there.
    perform pg_temp.act_as_admin();
    update visit set status = 'completed', check_out_at = now() where id = v_visit;
    insert into visit (customer_id, salesperson_id, branch_id, visit_date, status, check_in_at, check_in_nfc_uid, check_in_nfc_verified)
    select v_other, v_rep, branch_id, current_date, 'in_progress', now(), '04A1B2C3D4E580', true
      from salesperson where id = v_rep;
    perform pg_temp.act_as('nvbh01');
    v := register_nfc_tag(v_other, '04A1B2C3D4E580');
    perform pg_temp.check(v ->> 'result' = 'taken' and v ->> 'customer_name' is not null,
        'a tag bound elsewhere is not moved, and says where it belongs');

    -- The visit above claimed a verification for a tag that is not its customer's.
    perform pg_temp.check(
        (select check_in_nfc_verified from visit where customer_id = v_other and visit_date = current_date) = false,
        'a check-in cannot claim a match it did not earn');

    perform pg_temp.act_as_admin();
    update visit set check_in_nfc_uid = '04a1b2c3d4e580' where id = v_visit;
    perform pg_temp.check((select check_in_nfc_verified from visit where id = v_visit),
        'the right tag at the right customer verifies');

    perform pg_temp.act_as('nvbh01');
    perform pg_temp.check_refused('select * from customer_nfc_tag', 'permission denied',
        'the tag table is not readable directly');
end;
$$;

do $$ begin raise exception 'ALL PASSED'; end $$;
