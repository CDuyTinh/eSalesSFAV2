-- =============================================================================
-- Thông báo kết quả duyệt — the new kinds
--
-- On their own because Postgres will not let a value added to an enum be used in
-- the transaction that added it; the next migration is the one that uses them.
--
-- Legacy kinds: LEAVEAPPROVALRESULT, CUSTOMERAPPROVALRESULT and the three
-- VISIBILITY / LOYALTY / POSM *APPROVALRESULT (the manager app's notify types).
-- The three programme families share one kind here: the rep's bell groups them
-- as "Đăng ký chương trình" and the title already says which programme.
-- =============================================================================

alter type notification_kind add value if not exists 'leave_result';
alter type notification_kind add value if not exists 'customer_result';
alter type notification_kind add value if not exists 'trade_result';
