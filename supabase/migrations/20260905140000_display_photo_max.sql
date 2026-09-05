-- =============================================================================
-- A ceiling on display photos
--
-- `display_remark` carried only `photo_min`, so nothing stopped a rep taking
-- forty pictures of one shelf and waiting for all of them to upload from a shop
-- doorway. The app this replaces has had a ceiling since the beginning —
-- `DISPLAY_IMAGE`, default 6, read beside `DISPLAY_IMAGE_REQUIRED` — and hides
-- its add-photo tile once the grid is full.
--
-- Written into the step's own config rather than hardcoded, for the same reason
-- photo_min is: head office changes the requirement by changing data, and the
-- client already reads this row. The client defaults to 6 when the key is absent,
-- so this row makes the setting visible rather than making it work.
-- =============================================================================

update sales_step
set config = coalesce(config, '{}'::jsonb) || jsonb_build_object('photo_max', 6)
where form_id = 'display_remark';
