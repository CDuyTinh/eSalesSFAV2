-- =============================================================================
-- Khảo sát đối thủ
--
-- `market_info` is one step over two quite different surveys, and the app has
-- only ever had one of them.
--
--   Type 'M', khảo sát thị trường — a questionnaire. SI_SrvQuestionList and its
--   questions, answered into PPC_SurveyNew. The survey engine here already does
--   this; what it lacks is a list of them and a photo per answer.
--
--   Type 'C', khảo sát đối thủ — not a questionnaire at all. A matrix: for each
--   of our products, against each competitor's equivalent product, record a
--   criterion and photograph it. "What is Pepsi's 330ml selling at on this
--   shelf, and show me." Nothing of it exists here.
--
-- This migration builds the second. The legacy shape, and what each becomes:
--
--   OM_CompetitorVendor        -> competitor_vendor         who they are
--   OM_CompetitorInvt          -> competitor_product        what they sell
--   OM_CompetitorCriteria      -> competitor_criteria       what we ask about
--   OM_CompetitorSurveyHeader  -> competitor_survey         the campaign
--   OM_CompetitorSurveyCriteria-> competitor_survey_criteria which questions
--   OM_CompetitorSurveyInvt    -> competitor_survey_item    the pairings
--   OM_CompetitorSurveyResult  -> competitor_survey_result  the answers
--   ...ResultIMGNew            -> competitor_survey_photo
--
-- One simplification, stated rather than hidden: the legacy keys a pairing by
-- (InvtID, CompID, CompInvtID) — our product, the competitor, and their product.
-- The middle term is redundant, because a competitor product already belongs to
-- exactly one competitor. Two columns here, and the vendor is read through the
-- product.
--
-- Results are keyed by visit rather than by day. A shop can be called on twice,
-- and the afternoon call should start with its surveys unanswered rather than
-- inheriting the morning's — the same rule the display and POSM audits follow.
-- =============================================================================

create table competitor_vendor (
    id         uuid    primary key default gen_random_uuid(),
    code       text    not null unique,
    name       text    not null,
    is_active  boolean not null default true,
    created_at timestamptz not null default now()
);

create table competitor_product (
    id          uuid    primary key default gen_random_uuid(),
    vendor_id   uuid    not null references competitor_vendor (id) on delete cascade,
    code        text    not null,
    name        text    not null,
    is_active   boolean not null default true,

    unique (vendor_id, code)
);

create index on competitor_product (vendor_id);

/**
 * What the rep is asked about a competitor's product: price, facings, whether it
 * is running a promotion. Free text on purpose — `OM_CompetitorSurveyResult`
 * stores a `Content` string and nothing narrower, because the questions vary by
 * campaign and a typed column would only fit the campaign that invented it.
 */
create table competitor_criteria (
    id         uuid    primary key default gen_random_uuid(),
    code       text    not null unique,
    name       text    not null,

    /** Shown under the field. "Đơn giá lẻ", "Số mặt trưng bày". */
    hint       text,

    is_active  boolean not null default true
);

create table competitor_survey (
    id         uuid    primary key default gen_random_uuid(),
    code       text    not null unique,
    name       text    not null,

    from_date  date    not null,
    to_date    date    not null,

    /** Null runs it in every branch, as OM_CompetitorSurveyCpny's absence does. */
    branch_id  uuid    references branch (id),

    is_active  boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint competitor_survey_dates check (to_date >= from_date)
);

create trigger competitor_survey_set_updated_at
    before update on competitor_survey
    for each row execute function set_updated_at();

create table competitor_survey_criteria (
    id          uuid    primary key default gen_random_uuid(),
    survey_id   uuid    not null references competitor_survey (id) on delete cascade,
    criteria_id uuid    not null references competitor_criteria (id),

    /**
     * OM_CompetitorSurveyCriteria.Required. A survey is only finished when every
     * required criterion has an answer for every pairing; the optional ones are
     * there for the rep who has time.
     */
    is_required boolean not null default true,
    sort_order  integer not null default 0,

    unique (survey_id, criteria_id)
);

create index on competitor_survey_criteria (survey_id);

/** Our product against theirs. The pairing the rep stands in front of. */
create table competitor_survey_item (
    id                    uuid    primary key default gen_random_uuid(),
    survey_id             uuid    not null references competitor_survey (id) on delete cascade,
    product_id            uuid    not null references product (id),
    competitor_product_id uuid    not null references competitor_product (id),
    sort_order            integer not null default 0,

    unique (survey_id, product_id, competitor_product_id)
);

create index on competitor_survey_item (survey_id);

-- -----------------------------------------------------------------------------
-- What the rep found
-- -----------------------------------------------------------------------------

create table competitor_survey_result (
    id                    uuid    primary key,
    visit_id              uuid    not null references visit (id) on delete cascade,
    customer_id           uuid    not null references customer (id),
    salesperson_id        uuid    not null references salesperson (id),
    survey_id             uuid    not null references competitor_survey (id),
    product_id            uuid    not null references product (id),
    competitor_product_id uuid    not null references competitor_product (id),
    criteria_id           uuid    not null references competitor_criteria (id),

    /** OM_CompetitorSurveyResult.Content. Whatever the criterion asked for. */
    content               text    not null,

    photo_count           integer not null default 0,
    client_created_at     timestamptz not null,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),

    -- One answer per cell per visit. Redoing it replaces the earlier attempt
    -- rather than leaving two readings with no way to tell which is current.
    unique (visit_id, survey_id, product_id, competitor_product_id, criteria_id)
);

create index on competitor_survey_result (customer_id, created_at desc);
create index on competitor_survey_result (survey_id);

create trigger competitor_survey_result_set_updated_at
    before update on competitor_survey_result
    for each row execute function set_updated_at();

create table competitor_survey_photo (
    id         uuid   primary key default gen_random_uuid(),
    result_id  uuid   not null references competitor_survey_result (id) on delete cascade,

    -- Object name within the visit-photos bucket, following the convention the
    -- storage policies authorise on: <salesperson_id>/<visit_id>/<file>.
    storage_path text not null,

    taken_at   timestamptz not null,
    lat        double precision,
    lng        double precision,
    file_size  integer,

    unique (result_id, storage_path)
);

create index on competitor_survey_photo (result_id);

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table competitor_vendor           enable row level security;
alter table competitor_product          enable row level security;
alter table competitor_criteria         enable row level security;
alter table competitor_survey           enable row level security;
alter table competitor_survey_criteria  enable row level security;
alter table competitor_survey_item      enable row level security;
alter table competitor_survey_result    enable row level security;
alter table competitor_survey_photo     enable row level security;

create policy "everyone reads competitor vendors" on competitor_vendor
    for select to authenticated using (true);
create policy "everyone reads competitor products" on competitor_product
    for select to authenticated using (true);
create policy "everyone reads competitor criteria" on competitor_criteria
    for select to authenticated using (true);
create policy "everyone reads competitor surveys" on competitor_survey
    for select to authenticated using (true);
create policy "everyone reads competitor survey criteria" on competitor_survey_criteria
    for select to authenticated using (true);
create policy "everyone reads competitor survey items" on competitor_survey_item
    for select to authenticated using (true);

create policy "rep reads own competitor answers"
    on competitor_survey_result for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own competitor answers"
    on competitor_survey_result for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

create policy "rep updates own competitor answers"
    on competitor_survey_result for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

create policy "rep deletes own competitor answers"
    on competitor_survey_result for delete to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep reads own competitor photos"
    on competitor_survey_photo for select to authenticated
    using (
        exists (
            select 1 from competitor_survey_result r
            where r.id = competitor_survey_photo.result_id
              and r.salesperson_id = current_salesperson_id()
        )
    );

create policy "rep writes own competitor photos"
    on competitor_survey_photo for insert to authenticated
    with check (
        exists (
            select 1 from competitor_survey_result r
            where r.id = competitor_survey_photo.result_id
              and r.salesperson_id = current_salesperson_id()
        )
    );
