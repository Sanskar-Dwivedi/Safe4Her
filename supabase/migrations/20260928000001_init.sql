-- Security4Her schema: profiles, trusted contacts, devices, incidents, map data.
-- See CONTRACT.md §3. RPCs live in the next migration.

create extension if not exists postgis with schema extensions;
set search_path = public, extensions;

-- ---------------------------------------------------------------------------
-- Config (read only by security-definer functions)
-- ---------------------------------------------------------------------------
create table public.app_config (
  key   text primary key,
  value text not null
);
insert into public.app_config (key, value) values
  ('digilocker_mode', 'mock'),        -- 'mock' | 'digilocker'
  ('incident_ttl_minutes', '120'),
  ('max_reports_per_day', '10')
on conflict (key) do nothing;

-- ---------------------------------------------------------------------------
-- Profiles (one per auth user, created by trigger)
-- ---------------------------------------------------------------------------
create table public.profiles (
  id                  uuid primary key references auth.users (id) on delete cascade,
  display_name        text not null default '',
  phone               text,
  verified_female     boolean not null default false,
  verification_method text check (verification_method in ('digilocker', 'mock')),
  verified_at         timestamptz,
  digilocker_sub_hash text unique,
  created_at          timestamptz not null default now()
);

create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  insert into public.profiles (id, display_name)
  values (
    new.id,
    coalesce(nullif(trim(new.raw_user_meta_data ->> 'display_name'), ''),
             split_part(coalesce(new.email, ''), '@', 1))
  )
  on conflict (id) do nothing;
  return new;
end $$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

create function public.is_verified_female(uid uuid) returns boolean
language sql stable security definer set search_path = public, pg_temp as $$
  select coalesce((select p.verified_female from public.profiles p where p.id = uid), false)
$$;

-- ---------------------------------------------------------------------------
-- Trusted contacts
-- ---------------------------------------------------------------------------
create table public.trusted_contacts (
  id                 uuid primary key default gen_random_uuid(),
  owner_id           uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  name               text not null check (length(trim(name)) > 0),
  phone              text not null check (length(trim(phone)) > 0),
  relation           text,
  priority           int  not null default 1,
  receives_sos       boolean not null default true,
  telegram_chat_id   bigint,
  telegram_link_code text not null unique default substr(replace(gen_random_uuid()::text, '-', ''), 1, 8),
  created_at         timestamptz not null default now()
);
create index trusted_contacts_owner_idx on public.trusted_contacts (owner_id, priority);

-- ---------------------------------------------------------------------------
-- Devices (watch device tokens are stored hashed)
-- ---------------------------------------------------------------------------
create table public.devices (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null references public.profiles (id) on delete cascade,
  kind       text not null check (kind in ('watch', 'phone')),
  token_hash text not null unique,
  created_at timestamptz not null default now(),
  last_seen  timestamptz
);
create index devices_user_idx on public.devices (user_id);

-- ---------------------------------------------------------------------------
-- Incidents
-- ---------------------------------------------------------------------------
create table public.incidents (
  id          uuid primary key,                       -- created by the triggering device
  user_id     uuid not null references public.profiles (id) on delete cascade,
  source      text not null check (source in ('phone', 'watch', 'watch_direct')),
  trigger     text not null check (trigger in ('knock', 'shake', 'button')),
  status      text not null default 'active' check (status in ('active', 'cancelled', 'resolved', 'expired')),
  test_mode   boolean not null default true,
  share_token text not null unique default replace(gen_random_uuid()::text, '-', ''),
  created_at  timestamptz not null default now(),
  ended_at    timestamptz,
  expires_at  timestamptz not null default now() + interval '2 hours'
);
create index incidents_user_idx on public.incidents (user_id, created_at desc);
create index incidents_active_idx on public.incidents (expires_at) where status = 'active';

create table public.incident_locations (
  id          bigserial primary key,
  incident_id uuid not null references public.incidents (id) on delete cascade,
  geog        extensions.geography(point, 4326) not null,
  accuracy_m  real,
  fix_at      timestamptz not null default now(),
  source      text,
  created_at  timestamptz not null default now()
);
create index incident_locations_incident_idx on public.incident_locations (incident_id, fix_at desc);

create table public.incident_events (
  id          bigserial primary key,
  incident_id uuid not null references public.incidents (id) on delete cascade,
  type        text not null,
  details     jsonb not null default '{}'::jsonb,
  created_at  timestamptz not null default now()
);
create index incident_events_incident_idx on public.incident_events (incident_id, created_at);

-- ---------------------------------------------------------------------------
-- Map data
-- ---------------------------------------------------------------------------
create table public.safe_places (
  id       bigserial primary key,
  name     text not null,
  category text not null check (category in
             ('police', 'hospital', 'clinic', 'pharmacy', 'fire_station', 'cafe', 'ngo', 'other')),
  geog     extensions.geography(point, 4326) not null,
  verified boolean not null default false,
  source   text not null default 'manual',
  osm_id   text unique,
  phone    text,
  address  text
);
create index safe_places_geog_idx on public.safe_places using gist (geog);

create table public.unsafe_reports (
  id          bigserial primary key,
  reporter_id uuid not null references public.profiles (id) on delete cascade,
  geog        extensions.geography(point, 4326) not null,
  category    text not null check (category in ('poor_lighting', 'harassment', 'stalking', 'isolated', 'other')),
  note        text check (note is null or length(note) <= 280),
  created_at  timestamptz not null default now()
);
create index unsafe_reports_reporter_idx on public.unsafe_reports (reporter_id, created_at desc);

-- "Known information": curated hotspots from public sources, shown without the k-anonymity threshold.
create table public.known_hotspots (
  id         bigserial primary key,
  geog       extensions.geography(point, 4326) not null,
  source     text not null,
  note       text,
  weight     real not null default 2,
  created_at timestamptz not null default now()
);

-- Danger cells: ~300 m Web-Mercator grid, time-decayed score (90-day e-folding).
-- Crowd cells need >= 3 distinct reporters so one woman's SOS never pins her on a public map.
-- Test-mode incidents and incidents cancelled during the countdown never count.
create materialized view public.danger_cells as
with signals as (
  select r.reporter_id::text as who, r.geog, r.created_at, 1.0::float8 as w, false as official
  from public.unsafe_reports r
  union all
  select i.user_id::text, l.geog, i.created_at, 3.0::float8, false
  from public.incidents i
  join lateral (
    select x.geog from public.incident_locations x
    where x.incident_id = i.id order by x.fix_at asc limit 1
  ) l on true
  where not i.test_mode and i.status <> 'cancelled'
  union all
  select 'known:' || k.id, k.geog, k.created_at, k.weight::float8, true
  from public.known_hotspots k
),
cells as (
  select st_snaptogrid(st_transform(s.geog::geometry, 3857), 300) as cell,
         s.who,
         s.w * exp(-extract(epoch from (now() - s.created_at)) / 86400.0 / 90.0) as score,
         s.official
  from signals s
),
grouped as (
  select st_transform(st_translate(c.cell, 150, 150), 4326) as center,
         sum(c.score) as score,
         count(distinct c.who) filter (where not c.official) as reports,
         bool_or(c.official) as official
  from cells c
  group by c.cell
)
select st_y(g.center) as lat, st_x(g.center) as lng, g.score, g.reports::int as reports, g.official
from grouped g
where g.reports >= 3 or g.official;

create index danger_cells_latlng_idx on public.danger_cells (lat, lng);

-- ---------------------------------------------------------------------------
-- Row-level security and privileges
-- Supabase grants every new public table to anon/authenticated by default, so revoke explicitly.
-- ---------------------------------------------------------------------------
alter table public.app_config         enable row level security;
alter table public.profiles           enable row level security;
alter table public.trusted_contacts   enable row level security;
alter table public.devices            enable row level security;
alter table public.incidents          enable row level security;
alter table public.incident_locations enable row level security;
alter table public.incident_events    enable row level security;
alter table public.safe_places        enable row level security;
alter table public.unsafe_reports     enable row level security;
alter table public.known_hotspots     enable row level security;

revoke all on public.app_config, public.profiles, public.trusted_contacts, public.devices,
              public.incidents, public.incident_locations, public.incident_events,
              public.safe_places, public.unsafe_reports, public.known_hotspots,
              public.danger_cells
  from anon, authenticated;

-- profiles: read own; only display_name/phone are user-editable
grant select on public.profiles to authenticated;
grant update (display_name, phone) on public.profiles to authenticated;
create policy profiles_select_own on public.profiles
  for select to authenticated using (id = (select auth.uid()));
create policy profiles_update_own on public.profiles
  for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));

-- trusted_contacts: full CRUD on own rows, verified users only; telegram fields are server-managed
grant select, delete on public.trusted_contacts to authenticated;
grant insert (name, phone, relation, priority, receives_sos) on public.trusted_contacts to authenticated;
grant update (name, phone, relation, priority, receives_sos) on public.trusted_contacts to authenticated;
create policy contacts_own on public.trusted_contacts
  for all to authenticated
  using (owner_id = (select auth.uid()) and public.is_verified_female((select auth.uid())))
  with check (owner_id = (select auth.uid()) and public.is_verified_female((select auth.uid())));

-- incidents / locations: read own; writes only via RPCs
grant select on public.incidents, public.incident_locations to authenticated;
create policy incidents_select_own on public.incidents
  for select to authenticated using (user_id = (select auth.uid()));
create policy incident_locations_select_own on public.incident_locations
  for select to authenticated using (
    exists (select 1 from public.incidents i where i.id = incident_id and i.user_id = (select auth.uid())));

-- safe places: readable by verified users
grant select on public.safe_places to authenticated;
create policy safe_places_select on public.safe_places
  for select to authenticated using (public.is_verified_female((select auth.uid())));

-- unsafe reports: reporters see only their own; inserts via report_unsafe()
grant select on public.unsafe_reports to authenticated;
create policy unsafe_reports_select_own on public.unsafe_reports
  for select to authenticated using (reporter_id = (select auth.uid()));

-- devices, incident_events, app_config, known_hotspots, danger_cells: no direct client access.
