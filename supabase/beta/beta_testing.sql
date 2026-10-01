-- Security4Her beta testing: consented, categorised, anonymous test data.
-- Paste into Supabase → SQL Editor → Run. Safe to run again.
--
-- What the apps can do with the public (anon) key:
--   * record or change their consent (beta_set_consent), and withdraw it with their own delete key (beta_forget)
--   * add events, but only in categories their tester has agreed to, and at most 600 an hour
-- They can never read anything back. The team reads the tables and views below in the Supabase dashboard.

create table if not exists public.beta_consents (
  tester          text primary key check (tester ~ '^[0-9a-f]{32}$'),
  app             text not null check (app in ('phone', 'watch')),
  consent_version int  not null check (consent_version between 1 and 100),
  categories      text[] not null,
  accepted_at     timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  withdrawn_at    timestamptz,
  delete_key_hash text not null check (length(delete_key_hash) = 64)
);

create table if not exists public.beta_events (
  id          bigint generated always as identity primary key,
  received_at timestamptz not null default now(),
  happened_at timestamptz not null,
  tester      text not null check (tester ~ '^[0-9a-f]{32}$'),
  app         text not null check (app in ('phone', 'watch')),
  app_version text not null check (length(app_version) <= 32),
  category    text not null check (category in ('crash', 'error', 'security', 'false_alarm', 'usage', 'performance', 'device', 'feedback')),
  severity    text not null check (severity in ('info', 'warning', 'error', 'critical')),
  code        text not null check (code ~ '^[a-z0-9_.:-]{1,64}$'),
  fingerprint text not null check (length(fingerprint) between 1 and 64),
  message     text check (length(message) <= 4000),
  detail      jsonb check (detail is null or pg_column_size(detail) <= 16000),
  device      jsonb check (device is null or pg_column_size(device) <= 2000)
);
create index if not exists beta_events_fp on public.beta_events (fingerprint, happened_at desc);
create index if not exists beta_events_tester on public.beta_events (tester, received_at desc);

alter table public.beta_consents enable row level security;
alter table public.beta_events enable row level security;
revoke all on public.beta_consents, public.beta_events from anon, authenticated;
grant insert on public.beta_events to anon;
grant usage on sequence public.beta_events_id_seq to anon;

-- Is this tester allowed to send this category? (security definer: anon can't read the consents table itself)
create or replace function public.beta_allowed(p_tester text, p_category text)
returns boolean language sql stable security definer set search_path = public as $$
  select exists (select 1 from beta_consents c
                 where c.tester = p_tester and c.withdrawn_at is null
                   and (p_category = 'feedback' or p_category = any (c.categories)));
$$;

drop policy if exists beta_events_insert on public.beta_events;
create policy beta_events_insert on public.beta_events for insert to anon
  with check (public.beta_allowed(tester, category) and happened_at > now() - interval '30 days' and happened_at < now() + interval '1 day');

-- At most 600 events per tester per hour, so one broken phone can't flood the table.
create or replace function public.beta_rate_limit() returns trigger language plpgsql security definer set search_path = public as $$
begin
  if (select count(*) from beta_events where tester = new.tester and received_at > now() - interval '1 hour') >= 600 then
    raise exception 'rate limit' using errcode = 'P0001';
  end if;
  return new;
end $$;
drop trigger if exists beta_rate_limit on public.beta_events;
create trigger beta_rate_limit before insert on public.beta_events for each row execute function public.beta_rate_limit();

-- Record or update consent. The first call stores the hash of the tester's private delete key; later calls must match it.
create or replace function public.beta_set_consent(p_tester text, p_app text, p_version int, p_categories text[], p_key_hash text)
returns void language plpgsql security definer set search_path = public as $$
begin
  if p_categories is null or not p_categories <@ array['crash','error','security','false_alarm','usage','performance','device'] then
    raise exception 'bad categories';
  end if;
  insert into beta_consents (tester, app, consent_version, categories, delete_key_hash)
  values (p_tester, p_app, p_version, p_categories, p_key_hash)
  on conflict (tester) do update
    set consent_version = excluded.consent_version, categories = excluded.categories, updated_at = now(), withdrawn_at = null
    where beta_consents.delete_key_hash = excluded.delete_key_hash;
end $$;

-- Withdraw: deletes every event from this tester and marks the consent withdrawn. Needs the tester's delete key.
create or replace function public.beta_forget(p_tester text, p_key text)
returns integer language plpgsql security definer set search_path = public as $$
declare n integer;
begin
  if not exists (select 1 from beta_consents where tester = p_tester and delete_key_hash = encode(sha256(convert_to(p_key, 'UTF8')), 'hex')) then
    raise exception 'unknown tester or key';
  end if;
  delete from beta_events where tester = p_tester;
  get diagnostics n = row_count;
  update beta_consents set withdrawn_at = now(), categories = '{}' where tester = p_tester;
  return n;
end $$;

revoke all on function public.beta_set_consent(text, text, int, text[], text), public.beta_forget(text, text), public.beta_allowed(text, text) from public;
grant execute on function public.beta_set_consent(text, text, int, text[], text), public.beta_forget(text, text) to anon;
-- The insert policy calls beta_allowed as the anon role. It only answers yes/no for a random 128-bit tester ID.
grant execute on function public.beta_allowed(text, text) to anon;

-- ---------------------------------------------------------------- views for the team (dashboard only)

-- Every distinct problem, most widespread first.
create or replace view public.beta_problems as
select category, app, code, fingerprint,
       count(*)                                         as occurrences,
       count(distinct tester)                           as testers_affected,
       min(happened_at)                                 as first_seen,
       max(happened_at)                                 as last_seen,
       array_agg(distinct app_version)                  as versions,
       (array_agg(message order by happened_at desc))[1] as latest_message,
       max(severity)                                    as worst_severity
from public.beta_events
where category in ('crash', 'error', 'security', 'false_alarm', 'feedback')
group by category, app, code, fingerprint
order by testers_affected desc, occurrences desc;

-- One row per day and category: is the beta getting healthier?
create or replace view public.beta_daily as
select date_trunc('day', happened_at) as day, app, category, count(*) as events, count(distinct tester) as testers
from public.beta_events group by 1, 2, 3 order by 1 desc, 2, 3;

-- Feature use and performance numbers.
create or replace view public.beta_usage as
select app, code, count(*) as times, count(distinct tester) as testers,
       round(avg((detail ->> 'ms')::numeric)) as avg_ms,
       percentile_cont(0.9) within group (order by (detail ->> 'ms')::numeric) as p90_ms
from public.beta_events where category in ('usage', 'performance')
group by app, code order by times desc;

-- Which phones and watches are in the test.
create or replace view public.beta_devices as
select app, device ->> 'brand' as brand, device ->> 'model' as model, device ->> 'sdk' as android_sdk,
       count(distinct tester) as testers, count(*) as events
from public.beta_events where device is not null group by 1, 2, 3, 4 order by testers desc;

revoke all on public.beta_problems, public.beta_daily, public.beta_usage, public.beta_devices from anon, authenticated;
