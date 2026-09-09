-- =============================================================================
-- Cho phép ghi lại số ảnh của một lần giao POSM
--
-- `submit_posm_movement` counts the photographs it stored and writes the total
-- back onto the movement, so a list can say "2 ảnh" without joining. That update
-- was silently doing nothing: the movement tables got insert policies and no
-- update policy, and an UPDATE with no policy behind it matches no rows rather
-- than failing. Every movement filed so far claims nought photos while its own
-- photo rows sit there.
--
-- Narrow on purpose. A rep may correct a movement they filed; the policy will
-- not let them touch anyone else's.
-- =============================================================================

create policy "rep updates own posm movements"
    on posm_movement for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- Backfill what the missing policy lost. Runs as the migration's own role, so
-- it sees every row rather than one rep's.
update posm_movement m
set photo_count = (
    select count(*) from posm_movement_photo p where p.movement_id = m.id
)
where m.photo_count is distinct from (
    select count(*) from posm_movement_photo p where p.movement_id = m.id
);
