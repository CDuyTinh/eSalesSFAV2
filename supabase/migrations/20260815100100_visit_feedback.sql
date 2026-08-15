-- =============================================================================
-- Customer feedback
--
-- Backs the `feedback` step, which until now was a plain note: whatever the rep
-- typed went into visit_step_result.payload as a string and stopped being findable
-- the moment it was saved. Feedback only earns the step it occupies if somebody at
-- head office can act on it, so this gives it a coded topic and a row of its own.
--
-- The topic comes from reason_code with kind 'feedback_topic' rather than a
-- dedicated table — see the previous migration for why.
--
-- Audio is here because `sales_step` has been claiming `{"allow_audio": true}` for
-- this step since it was seeded and nothing honoured it. A rep in a loud shop with a
-- customer talking at them cannot type Vietnamese quickly; thirty seconds of voice is
-- a better record than a rushed sentence, and the config already promised it.
-- =============================================================================

-- Its own bucket rather than reusing visit-photos. The storage policies authorise on
-- the leading <salesperson_id> path segment and are content-agnostic, so sharing
-- would have worked — but a bucket named visit-photos full of .m4a files is exactly
-- the sort of quiet mislabelling that costs someone an afternoon later, and audio
-- will want its own size limit and retention long before photos do.
insert into storage.buckets (id, name, public)
values ('visit-audio', 'visit-audio', false)
on conflict (id) do nothing;

create policy "rep uploads own visit audio"
    on storage.objects for insert to authenticated
    with check (
        bucket_id = 'visit-audio'
        and (storage.foldername(name))[1] = current_salesperson_id()::text
    );

create policy "rep reads own visit audio"
    on storage.objects for select to authenticated
    using (
        bucket_id = 'visit-audio'
        and (storage.foldername(name))[1] = current_salesperson_id()::text
    );

create table visit_feedback (
    -- Client-minted, as for orders and stock counts: the idempotency key that makes
    -- an outbox replay a no-op rather than a second complaint.
    id                uuid        primary key,

    visit_id          uuid        not null references visit (id) on delete cascade,
    customer_id       uuid        not null references customer (id),
    salesperson_id    uuid        not null references salesperson (id),
    feedback_date     date        not null,

    -- What it is about. Nullable on purpose: a market that has configured no topics
    -- still needs the step to work, and a rep should never be blocked from reporting
    -- something because head office has not classified it yet.
    topic_id          uuid        references reason_code (id),

    note              text        not null,

    -- Object name in the visit-audio bucket, same
    -- <salesperson_id>/<visit_id>/<file> convention the photo policies use.
    audio_path        text,
    audio_seconds     integer     constraint audio_seconds_sane check (audio_seconds is null or audio_seconds between 1 and 600),

    client_created_at timestamptz not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),

    -- One per visit. Redoing the step corrects the earlier attempt rather than
    -- leaving two versions with no way to tell which the rep meant.
    unique (visit_id)
);

create index on visit_feedback (customer_id, feedback_date desc);
create index on visit_feedback (salesperson_id, feedback_date desc);

-- The point of the coded topic: "show me every chiller request this month" has to be
-- an index scan, not a text search.
create index on visit_feedback (topic_id, feedback_date desc);

create trigger visit_feedback_set_updated_at
    before update on visit_feedback
    for each row execute function set_updated_at();

comment on table visit_feedback is
    'What the customer said, with a coded topic so it can be routed. One per visit.';

-- -----------------------------------------------------------------------------
-- submit_feedback
-- -----------------------------------------------------------------------------

create or replace function submit_feedback(p_feedback jsonb)
returns uuid
language plpgsql
security invoker
set search_path = public, pg_temp
as $$
declare
    v_id       uuid    := (p_feedback ->> 'id')::uuid;
    v_visit_id uuid    := (p_feedback ->> 'visit_id')::uuid;
    v_date     date    := coalesce((p_feedback ->> 'feedback_date')::date, current_date);
    v_sp_id    uuid    := current_salesperson_id();
    v_customer uuid;
    v_note     text    := nullif(btrim(p_feedback ->> 'note'), '');
    v_topic    uuid    := (p_feedback ->> 'topic_id')::uuid;
    v_audio    text    := nullif(p_feedback ->> 'audio_path', '');
    v_min      integer;
begin
    if v_id is null or v_visit_id is null then
        raise exception 'submit_feedback needs both id and visit_id';
    end if;

    -- Already booked: a replay from the outbox.
    if exists (select 1 from visit_feedback where id = v_id) then
        return v_id;
    end if;

    select v.customer_id into v_customer
    from visit v
    where v.id = v_visit_id and v.salesperson_id = v_sp_id;

    if v_customer is null then
        raise exception 'visit % is not an open visit of this salesperson', v_visit_id;
    end if;

    -- The step's own configuration decides how much text is enough, so the client
    -- refuses exactly what the server refuses. Defaults to 1: this step exists to
    -- record something, and an empty note records nothing.
    select coalesce((s.config ->> 'note_min_length')::integer, 1) into v_min
    from sales_step s
    where s.form_id = 'feedback';

    v_min := coalesce(v_min, 1);

    if v_note is null or char_length(v_note) < v_min then
        raise exception
            'feedback %: note is shorter than the % characters this step requires',
            v_id, v_min;
    end if;

    -- A topic the rep could not have been shown is a topic they did not choose, and
    -- filing feedback under a GPS reason code would poison the one index that makes
    -- this table useful.
    if v_topic is not null and not exists (
        select 1 from reason_code r
        where r.id = v_topic and r.kind = 'feedback_topic'
    ) then
        raise exception 'feedback %: % is not a feedback topic', v_id, v_topic;
    end if;

    -- Same reasoning as the display audit: storage and the database are separate
    -- systems, so a path is checked to exist before a row claims it does. The select
    -- policy limits this to the rep's own folder, so anything outside reads as
    -- missing.
    if v_audio is not null and not exists (
        select 1 from storage.objects o
        where o.bucket_id = 'visit-audio' and o.name = v_audio
    ) then
        raise exception 'feedback %: audio % is not in storage', v_id, v_audio;
    end if;

    delete from visit_feedback where visit_id = v_visit_id;

    insert into visit_feedback (
        id, visit_id, customer_id, salesperson_id, feedback_date,
        topic_id, note, audio_path, audio_seconds, client_created_at
    )
    values (
        v_id, v_visit_id, v_customer, v_sp_id, v_date,
        v_topic, v_note, v_audio,
        (p_feedback ->> 'audio_seconds')::integer,
        coalesce((p_feedback ->> 'client_created_at')::timestamptz, now())
    );

    insert into visit_step_result (visit_id, form_id, completed_at, payload)
    values (
        v_visit_id,
        'feedback',
        now(),
        jsonb_build_object(
            'feedback_id', v_id,
            'topic_id', v_topic,
            'has_audio', v_audio is not null
        )
    )
    on conflict (visit_id, form_id) do update
        set completed_at = excluded.completed_at,
            payload      = excluded.payload;

    return v_id;
end;
$$;

revoke execute on function submit_feedback(jsonb) from public;
grant execute on function submit_feedback(jsonb) to authenticated;

-- -----------------------------------------------------------------------------
-- Row Level Security
-- -----------------------------------------------------------------------------

alter table visit_feedback enable row level security;

create policy "rep reads own feedback"
    on visit_feedback for select to authenticated
    using (salesperson_id = current_salesperson_id());

create policy "rep writes own feedback"
    on visit_feedback for insert to authenticated
    with check (salesperson_id = current_salesperson_id());

create policy "rep updates own feedback"
    on visit_feedback for update to authenticated
    using (salesperson_id = current_salesperson_id())
    with check (salesperson_id = current_salesperson_id());

-- Redoing the step deletes the visit's earlier feedback.
create policy "rep deletes own feedback"
    on visit_feedback for delete to authenticated
    using (salesperson_id = current_salesperson_id());
