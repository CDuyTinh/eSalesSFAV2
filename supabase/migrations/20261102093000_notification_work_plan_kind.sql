-- =============================================================================
-- Thông báo công việc — the new kind, on its own
--
-- Separate from its use for the same reason as 20261101090000: an enum value
-- cannot be used in the transaction that added it.
-- =============================================================================

alter type notification_kind add value if not exists 'work_plan';
