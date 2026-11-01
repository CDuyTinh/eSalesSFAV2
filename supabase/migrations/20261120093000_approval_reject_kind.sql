-- =============================================================================
-- A reason_kind for why a manager refuses something: a leave request, a new
-- customer, a display or loyalty registration, a work plan.
--
-- Refusing has always needed a reason, because the rep sees it; typed fresh each
-- time it comes out as "ko dc", "sai", or a paragraph. The common ones offered as
-- chips make the note both quicker and readable, and the manager can still edit
-- it or write their own.
--
-- reason_code rather than a table of its own, as feedback_topic is, for the same
-- reason: it is exactly code, name, kind and active flag.
--
-- Its own migration because Postgres will not let a new enum value be used in the
-- transaction that adds it, and the seed inserts rows with this kind.
-- =============================================================================

alter type reason_kind add value if not exists 'approval_reject';
