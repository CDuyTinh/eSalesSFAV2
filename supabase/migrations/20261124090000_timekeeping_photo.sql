-- =============================================================================
-- A photo at each punch of the working day, as the legacy app took one
-- (PPC_TimeKeeping.ImageName). The column has been on `timekeeping` since the
-- core schema; nothing ever filled it.
--
-- On by default, like the check-in and check-out photos.
-- =============================================================================

insert into app_setting (key, value, description)
values
    ('timekeeping_photo_required', 'true', 'Require a photo when opening and closing the working day')
on conflict (key) do nothing;
