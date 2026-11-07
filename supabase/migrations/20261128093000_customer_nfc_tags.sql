-- =============================================================================
-- NFC tags at the outlet, as a second proof of presence
--
-- GPS is weakest exactly where reps spend their day: inside a market hall, a
-- covered alley, a shop under a concrete floor. A check-in there lands tens of
-- metres off and asks the rep for a reason, and the reason is all a manager
-- has to go on. A cheap NFC sticker (NTAG213) fixed inside the shop gives a
-- second answer: the phone was held against something that only exists there.
--
-- The tag is identified by its UID. That is not unforgeable -- a UID-writable
-- clone card exists -- but it moves cheating from "toggle a mock-location app"
-- to "visit the shop once with a reader, then buy and program a clone", which
-- is the bar this is meant to raise.
--
-- A tag is bound to a customer by a rep standing in the shop: registering
-- requires the caller's visit to that customer to be in progress today, so the
-- binding is as trustworthy as the check-in that preceded it.
--
-- Whether a check-in's tag matched is decided by the database, not sent by
-- the app: a trigger fills check_in_nfc_verified from the tag table, so a
-- client writing the visit row directly cannot claim a verification it did
-- not earn.
-- =============================================================================

create table customer_nfc_tag (
    -- Upper-case hex, as Android's Tag.getId() bytes print. 4, 7 or 10 bytes
    -- on the tags in use; the range allows for other families.
    tag_uid        text primary key check (tag_uid ~ '^[0-9A-F]{8,20}$'),
    customer_id    uuid        not null references customer (id),
    registered_by  uuid        references salesperson (id),
    registered_at  timestamptz not null default now()
);

create index on customer_nfc_tag (customer_id);

-- Reached only through the functions below.
alter table customer_nfc_tag enable row level security;
revoke all on customer_nfc_tag from anon, authenticated;

alter table visit
    add column check_in_nfc_uid      text,
    add column check_in_nfc_verified boolean;

comment on column visit.check_in_nfc_uid is
    'UID of the NFC tag the rep tapped at check-in, if any.';
comment on column visit.check_in_nfc_verified is
    'Set by trigger: the tapped tag is registered to this visit''s customer. Null when no tag was tapped.';

create or replace function visit_verify_nfc()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    if new.check_in_nfc_uid is null then
        new.check_in_nfc_verified := null;
    else
        new.check_in_nfc_uid := upper(new.check_in_nfc_uid);
        new.check_in_nfc_verified := exists (
            select 1 from customer_nfc_tag t
             where t.tag_uid = new.check_in_nfc_uid
               and t.customer_id = new.customer_id
        );
    end if;
    return new;
end;
$$;

create trigger visit_verify_nfc
    before insert or update of check_in_nfc_uid, check_in_nfc_verified, customer_id on visit
    for each row execute function visit_verify_nfc();

/**
 * Binds [p_tag_uid] to [p_customer_id].
 *
 * result: 'registered' (new binding), 'unchanged' (already this customer's),
 * 'taken' (bound to another customer; customer_name says which),
 * 'no_visit' (the caller is not checked in at this customer today),
 * 'invalid' (not a UID).
 *
 * A tag already bound elsewhere is never moved: a sticker peeled off one shop
 * and stuck in another is exactly what this must not let through quietly. A
 * supervisor clears it by hand.
 */
create or replace function register_nfc_tag(p_customer_id uuid, p_tag_uid text)
returns jsonb
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_uid   text := upper(trim(p_tag_uid));
    v_owner uuid;
begin
    if v_uid !~ '^[0-9A-F]{8,20}$' then
        return jsonb_build_object('result', 'invalid');
    end if;

    if not exists (
        select 1 from visit v
         where v.salesperson_id = current_salesperson_id()
           and v.customer_id = p_customer_id
           and v.visit_date = current_date
           and v.status = 'in_progress'
    ) then
        return jsonb_build_object('result', 'no_visit');
    end if;

    select customer_id into v_owner from customer_nfc_tag where tag_uid = v_uid;

    if v_owner = p_customer_id then
        return jsonb_build_object('result', 'unchanged');
    elsif v_owner is not null then
        return jsonb_build_object(
            'result', 'taken',
            'customer_name', (select name from customer where id = v_owner)
        );
    end if;

    insert into customer_nfc_tag (tag_uid, customer_id, registered_by)
    values (v_uid, p_customer_id, current_salesperson_id());

    return jsonb_build_object('result', 'registered');
end;
$$;

/**
 * The customer a tag belongs to, if the caller's branch serves them; null
 * otherwise, including for a tag nobody registered. Used to open the right
 * check-in from a tap on the route screen.
 */
create or replace function nfc_tag_customer(p_tag_uid text)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select jsonb_build_object('customer_id', c.id, 'code', c.code, 'name', c.name)
      from customer_nfc_tag t
      join customer c on c.id = t.customer_id
      join salesperson s on s.id = current_salesperson_id()
     where t.tag_uid = upper(trim(p_tag_uid))
       and c.branch_id = s.branch_id
$$;

revoke execute on function register_nfc_tag(uuid, text) from public, anon;
revoke execute on function nfc_tag_customer(text) from public, anon;
revoke execute on function visit_verify_nfc() from public, anon, authenticated;
grant execute on function register_nfc_tag(uuid, text) to authenticated;
grant execute on function nfc_tag_customer(text) to authenticated;
