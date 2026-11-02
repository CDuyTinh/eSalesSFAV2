-- =============================================================================
-- Shared by every test file; scripts/run-db-tests.sh puts it in front of each.
--
-- The tests run against the linked project's real data, so each file runs in
-- one transaction and ends by raising 'ALL PASSED' on purpose: whatever a test
-- wrote is rolled back either way, and the runner reads that message as the
-- pass. Any other error is a failure, and its message says which check.
-- =============================================================================

-- Acts as the user behind a salesperson code, as the API would: their JWT
-- claims, and the authenticated role RLS and grants are written for.
create function pg_temp.act_as(p_code text) returns void language plpgsql as $$
begin
    -- Looked up as the admin: the user acted as before may not see this row.
    execute 'reset role';
    perform set_config(
        'request.jwt.claims',
        json_build_object(
            'sub', (select user_id from salesperson where code = p_code),
            'role', 'authenticated'
        )::text,
        true
    );
    execute 'set local role authenticated';
end;
$$;

-- Back to the migration role, to set up rows a test needs.
create function pg_temp.act_as_admin() returns void language plpgsql as $$
begin
    execute 'reset role';
    perform set_config('request.jwt.claims', '', true);
end;
$$;

create function pg_temp.check(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if p_ok is distinct from true then
        raise exception 'FAIL: %', p_what;
    end if;
end;
$$;

-- Runs a statement that must be refused, with a message containing p_expect.
create function pg_temp.check_refused(p_sql text, p_expect text, p_what text) returns void language plpgsql as $$
begin
    begin
        execute p_sql;
    exception when others then
        if position(p_expect in sqlerrm) > 0 then
            return;
        end if;
        raise exception 'FAIL: % (refused, but with "%")', p_what, sqlerrm;
    end;
    raise exception 'FAIL: % (was allowed)', p_what;
end;
$$;

-- As its owner, so that whoever is being acted as, any code resolves.
create function pg_temp.sp(p_code text) returns uuid language sql security definer as $$
    select id from salesperson where code = p_code
$$;

create function pg_temp.this_month() returns date language sql as $$
    select date_trunc('month', now() at time zone 'Asia/Ho_Chi_Minh')::date
$$;

grant execute on all functions in schema pg_temp to authenticated;
