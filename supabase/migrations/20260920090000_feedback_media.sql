-- =============================================================================
-- Ảnh và nhiều bản ghi âm cho ý kiến khách hàng
--
-- The step records one note, one topic and one voice clip. The legacy records a
-- note, *any number of photos* and *any number of recordings*:
--
--   OM_FeedBackCustomer        note, one row per submission
--   OM_FeedBackCustomerImage   -> visit_feedback_photo
--   OM_FeedBackCustomerRecords -> visit_feedback_audio
--
-- and it sizes both from settings the rebuild has no equivalent of:
--
--   FEEDBACK_CUSTOMER_IMAGE_REQUIRED -> sales_step.config photo_min
--   FEEDBACK_CUSTOMER_IMAGE          -> sales_step.config photo_max
--   SALES_RECORD_TIME_FILE           -> sales_step.config audio_max_seconds
--   SALES_RECORD_MAX_REALTIME        -> sales_step.config audio_total_seconds
--
-- The photos are the bigger of the two gaps. Half of what a rep is told at the
-- counter is about something they are standing in front of — a split case, a
-- rival's new shelf, a chiller that has stopped — and a step that cannot carry a
-- picture of it turns a report into an assertion.
--
-- The recordings matter for a duller reason: the legacy caps one file and lets
-- the total run longer, splitting as it goes, so a customer still talking at the
-- cap does not get cut off. One clip per feedback loses the rest of the sentence.
--
-- Three things the rebuild keeps that the legacy does not have, stated rather
-- than quietly dropped:
--
--   The topic. Nothing in the legacy classifies feedback, and unclassified
--   feedback is unroutable.
--
--   The required note. `_validateSave` over there checks the photo count and
--   nothing else — the content check is commented out — so a submission with no
--   note at all is accepted. Nobody can search or route a sound file, and one
--   readable line is what makes the rest findable.
--
--   One per visit. The legacy keys on CreateDate and files a new row every time
--   the rep presses save, which leaves two complaints from one call with no way
--   to tell whether the second corrects the first. Redoing the step replaces it
--   here, the way every other step in this app behaves.
-- =============================================================================

create table visit_feedback_photo (
    id           uuid   primary key default gen_random_uuid(),
    feedback_id  uuid   not null references visit_feedback (id) on delete cascade,

    -- Object name in the visit-photos bucket, following the convention the
    -- storage policies authorise on: <salesperson_id>/<visit_id>/<file>.
    storage_path text   not null,

    taken_at     timestamptz not null,
    lat          double precision,
    lng          double precision,
    file_size    integer,

    unique (feedback_id, storage_path)
);

create index on visit_feedback_photo (feedback_id);

create table visit_feedback_audio (
    id           uuid   primary key default gen_random_uuid(),
    feedback_id  uuid   not null references visit_feedback (id) on delete cascade,

    /** Object name in the visit-audio bucket, same path convention. */
    storage_path text   not null,

    seconds      integer not null
        constraint feedback_audio_seconds_sane check (seconds between 1 and 3600),

    -- What the rep sees. A recording split at the per-file cap is still the
    -- second half of one thought, and playing them out of order is nonsense.
    sort_order   integer not null default 0,

    recorded_at  timestamptz not null,
    file_size    integer,

    unique (feedback_id, storage_path)
);

create index on visit_feedback_audio (feedback_id);

-- -----------------------------------------------------------------------------
-- The parent row carries the totals only
-- -----------------------------------------------------------------------------

alter table visit_feedback
    add column if not exists photo_count integer not null default 0,
    add column if not exists audio_count integer not null default 0;

-- audio_seconds stays and changes meaning: the total across every clip rather
-- than the length of the only one. The old ceiling of 600 was one clip's worth.
alter table visit_feedback
    drop constraint if exists audio_seconds_sane;

alter table visit_feedback
    add constraint audio_seconds_sane
    check (audio_seconds is null or audio_seconds between 0 and 36000);

comment on column visit_feedback.audio_seconds is
    'Total across every clip on this feedback. Per-clip length lives on the clip.';

-- The single-clip column is gone: a feedback with three recordings has no one
-- path to put here, and leaving it would be a second, disagreeing answer to
-- "where is the audio". There are no rows to carry over.
alter table visit_feedback drop column if exists audio_path;

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table visit_feedback_photo enable row level security;
alter table visit_feedback_audio enable row level security;

create policy "rep reads own feedback photos"
    on visit_feedback_photo for select to authenticated
    using (
        exists (
            select 1 from visit_feedback f
            where f.id = visit_feedback_photo.feedback_id
              and f.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own feedback photos"
    on visit_feedback_photo for insert to authenticated
    with check (
        exists (
            select 1 from visit_feedback f
            where f.id = visit_feedback_photo.feedback_id
              and f.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep reads own feedback audio"
    on visit_feedback_audio for select to authenticated
    using (
        exists (
            select 1 from visit_feedback f
            where f.id = visit_feedback_audio.feedback_id
              and f.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own feedback audio"
    on visit_feedback_audio for insert to authenticated
    with check (
        exists (
            select 1 from visit_feedback f
            where f.id = visit_feedback_audio.feedback_id
              and f.salesperson_id = current_salesperson_id()
        )
    );

-- -----------------------------------------------------------------------------
-- How much of each the step wants
-- -----------------------------------------------------------------------------

-- Nought required and five allowed is FEEDBACK_CUSTOMER_IMAGE_REQUIRED = 0 with
-- the old client's own default ceiling. Feedback is not always about something
-- visible, so a floor here would block the rep who has only been told a price.
--
-- Five minutes a clip and fifteen in total are SALES_RECORD_TIME_FILE and
-- SALES_RECORD_MAX_REALTIME at values a shop conversation fits inside.
update sales_step
set config = config
    || '{"photo_min": 0, "photo_max": 5}'::jsonb
    || '{"audio_max_seconds": 300, "audio_total_seconds": 900}'::jsonb
where form_id = 'feedback';
