-- =============================================================================
-- Tighten execute grants on the manager functions
--
-- `revoke ... from public` is not enough on Supabase: the project's default
-- privileges grant EXECUTE on every new function in `public` to anon and
-- authenticated by name, so the revokes in 20261031090000 changed nothing.
--
-- None of it leaked rows — every mgr_* function scopes to the caller's team,
-- and anon has no team — but staff_brief() would hand any caller the code and
-- name behind a salesperson id. It is only ever called from inside definer
-- functions, which run as the owner, so nobody else needs it.
-- =============================================================================

revoke execute on function staff_brief(uuid) from anon, authenticated;

-- Needed by notifications_for(), which runs as the rep; anon never.
revoke execute on function trade_registration_rep(uuid, uuid) from anon;

revoke execute on function mgr_approval_counts() from anon;
revoke execute on function mgr_leave_list(text[], uuid) from anon;
revoke execute on function mgr_decide_leave(uuid, boolean, text) from anon;
revoke execute on function mgr_customer_list(text[], text) from anon;
revoke execute on function mgr_customer_detail(uuid) from anon;
revoke execute on function mgr_decide_customer(uuid, boolean, text, uuid) from anon;
revoke execute on function mgr_trade_list(text[], text[]) from anon;
revoke execute on function mgr_decide_trade(text, uuid, boolean, text, integer) from anon;
revoke execute on function team_salesperson_ids() from anon;
revoke execute on function is_team_member(uuid) from anon;
