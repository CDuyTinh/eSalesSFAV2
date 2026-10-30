-- Manager notifications: what the team has sent up for the supervisor to rule on.
-- Kept apart from their first use, as Postgres will not use an enum value in the
-- transaction that added it (see 20261101090000_notification_result_kinds.sql).
alter type notification_kind add value if not exists 'team_leave';
alter type notification_kind add value if not exists 'team_customer';
alter type notification_kind add value if not exists 'team_trade';
alter type notification_kind add value if not exists 'team_work_plan';
