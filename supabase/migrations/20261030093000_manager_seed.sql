-- =============================================================================
-- Dữ liệu mẫu: giám sát gsbh01 quản lý nvbh01, nvbh02
--
-- Same shape as the Vifon data (data.sql): an SS on the reps' branch with SupID
-- pointing at them. The login itself is created by scripts/seed_auth_users.ps1,
-- which already links auth users to salesperson rows by code.
-- =============================================================================

insert into salesperson (id, code, full_name, branch_id, phone, allow_multi_device, position)
values ('00000000-0000-0000-0000-000000000083', 'gsbh01', 'Nguyen Van Giam',
        '00000000-0000-0000-0000-000000000010', '0909000083', true, 'ss')
on conflict (id) do nothing;

update salesperson
set supervisor_id = '00000000-0000-0000-0000-000000000083'
where code in ('nvbh01', 'nvbh02')
  and supervisor_id is null;
