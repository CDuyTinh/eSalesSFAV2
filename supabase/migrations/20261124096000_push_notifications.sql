-- =============================================================================
-- Push notifications (FCM)
--
-- Each phone's FCM token, against whoever is signed in on it and which of the
-- two apps it is. The edge functions that record an event — a leave request
-- sent, a sign-up approved, a plan assigned — look up the tokens of the people
-- it concerns and send through FCM (_shared/push.ts).
--
-- A token is the phone's, not the person's: when somebody else signs in on the
-- same phone the row moves to them, so a handed-over phone stops receiving the
-- previous owner's notifications. Signing out deletes it.
-- =============================================================================

create table device_token (
    token          text        primary key,
    salesperson_id uuid        not null references salesperson (id) on delete cascade,
    app            text        not null check (app in ('sfa', 'manager')),
    updated_at     timestamptz not null default now()
);

create index on device_token (salesperson_id, app);

-- No policies: reached only through the two functions below and, for sending,
-- the service role. Nobody needs to read another person's token.
alter table device_token enable row level security;

create or replace function register_device_token(p_token text, p_app text)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_me uuid := current_salesperson_id();
begin
    if v_me is null then
        raise exception 'no salesperson is linked to this account';
    end if;
    if p_app not in ('sfa', 'manager') then
        raise exception 'app must be sfa or manager';
    end if;

    insert into device_token (token, salesperson_id, app)
    values (p_token, v_me, p_app)
    on conflict (token) do update
        set salesperson_id = excluded.salesperson_id,
            app = excluded.app,
            updated_at = now();
end;
$$;

create or replace function unregister_device_token(p_token text)
returns void
language sql
security definer
set search_path = public, pg_temp
as $$
    delete from device_token
    where token = p_token and salesperson_id = current_salesperson_id();
$$;

/**
 * Whose request an approval item is: the rep to tell once it is decided.
 * Programme sign-ups follow trade_registration_rep, as the approval list does.
 */
create or replace function approval_owner(p_kind text, p_id uuid)
returns uuid
language sql
stable
security definer
set search_path = public, pg_temp
as $$
    select case p_kind
        when 'leave' then (select salesperson_id from leave_request where id = p_id)
        when 'customer' then (select created_by_salesperson_id from customer where id = p_id)
        when 'display' then (select trade_registration_rep(salesperson_id, customer_id)
                               from display_registration where id = p_id)
        when 'loyalty' then (select trade_registration_rep(salesperson_id, customer_id)
                               from loyalty_registration where id = p_id)
        when 'posm' then (select trade_registration_rep(salesperson_id, customer_id)
                            from posm_registration where id = p_id)
    end;
$$;

revoke execute on function register_device_token(text, text) from public, anon;
revoke execute on function unregister_device_token(text) from public, anon;
grant execute on function register_device_token(text, text) to authenticated;
grant execute on function unregister_device_token(text) to authenticated;

-- Only the sender (service role) asks whose an item is.
revoke execute on function approval_owner(text, uuid) from public, anon, authenticated;
grant execute on function approval_owner(text, uuid) to service_role;
