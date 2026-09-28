-- Security4Her RPCs. Signatures are part of CONTRACT.md §3; don't rename parameters.
set search_path = public, extensions;

-- ---------------------------------------------------------------------------
-- Helpers (internal: not callable by clients)
-- ---------------------------------------------------------------------------
create function public._require_verified() returns uuid
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'not authenticated' using errcode = '28000';
  end if;
  if not public.is_verified_female(uid) then
    raise exception 'not verified' using errcode = '42501';
  end if;
  return uid;
end $$;

create function public._point(p_lat float8, p_lng float8) returns extensions.geography
language plpgsql immutable set search_path = public, extensions, pg_temp as $$
begin
  if p_lat is null or p_lng is null or p_lat not between -90 and 90 or p_lng not between -180 and 180 then
    raise exception 'invalid coordinates' using errcode = '22023';
  end if;
  return st_setsrid(st_makepoint(p_lng, p_lat), 4326)::geography;
end $$;

create function public._add_location(
  p_id uuid, p_lat float8, p_lng float8, p_acc real, p_fix_at timestamptz, p_source text
) returns boolean
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
  if not exists (select 1 from public.incidents i
                 where i.id = p_id and i.status = 'active' and i.expires_at > now()) then
    return false;
  end if;
  insert into public.incident_locations (incident_id, geog, accuracy_m, fix_at, source)
  values (p_id, public._point(p_lat, p_lng), p_acc, coalesce(p_fix_at, now()), p_source);
  return true;
end $$;

create function public._upsert_incident_for(
  p_user uuid, p_id uuid, p_trigger text, p_source text,
  p_lat float8, p_lng float8, p_acc real, p_test boolean, p_fix_at timestamptz
) returns table (id uuid, share_token text, status text, created boolean)
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
#variable_conflict use_column
declare
  v       public.incidents%rowtype;
  v_new   boolean := false;
  v_ttl   int;
begin
  if p_id is null then raise exception 'incident id required' using errcode = '22023'; end if;
  if p_trigger not in ('knock', 'shake', 'button') then
    raise exception 'invalid trigger %', p_trigger using errcode = '22023';
  end if;
  if p_source not in ('phone', 'watch', 'watch_direct') then
    raise exception 'invalid source %', p_source using errcode = '22023';
  end if;

  select c.value::int into v_ttl from public.app_config c where c.key = 'incident_ttl_minutes';

  insert into public.incidents as i (id, user_id, source, trigger, test_mode, expires_at)
  values (p_id, p_user, p_source, p_trigger, coalesce(p_test, true),
          now() + make_interval(mins => coalesce(v_ttl, 120)))
  on conflict on constraint incidents_pkey do nothing
  returning * into v;

  if found then
    v_new := true;
    insert into public.incident_events (incident_id, type, details)
    values (p_id, 'created', jsonb_build_object('source', p_source, 'trigger', p_trigger));
  else
    select * into v from public.incidents i where i.id = p_id;
    if v.user_id <> p_user then
      raise exception 'incident belongs to another user' using errcode = '42501';
    end if;
  end if;

  if p_lat is not null and p_lng is not null then
    perform public._add_location(p_id, p_lat, p_lng, p_acc, p_fix_at, p_source);
  end if;

  return query select v.id, v.share_token, v.status, v_new;
end $$;

create function public._end_incident_for(p_user uuid, p_id uuid, p_status text) returns boolean
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
  if p_status not in ('cancelled', 'resolved') then
    raise exception 'invalid status %', p_status using errcode = '22023';
  end if;
  update public.incidents i
     set status = p_status, ended_at = now()
   where i.id = p_id and i.user_id = p_user and i.status = 'active';
  if found then
    insert into public.incident_events (incident_id, type) values (p_id, p_status);
    return true;
  end if;
  if not exists (select 1 from public.incidents i where i.id = p_id and i.user_id = p_user) then
    raise exception 'incident not found' using errcode = 'P0002';
  end if;
  return false; -- already ended: idempotent
end $$;

create function public._sos_contacts(p_user uuid) returns jsonb
language sql stable security definer set search_path = public, pg_temp as $$
  select coalesce(jsonb_agg(jsonb_build_object(
           'name', c.name, 'phone', c.phone, 'telegram_chat_id', c.telegram_chat_id)
           order by c.priority, c.created_at), '[]'::jsonb)
  from public.trusted_contacts c
  where c.owner_id = p_user and c.receives_sos
$$;

-- ---------------------------------------------------------------------------
-- Verification
-- ---------------------------------------------------------------------------
create function public.mock_verify_female() returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not authenticated' using errcode = '28000'; end if;
  if coalesce((select c.value from public.app_config c where c.key = 'digilocker_mode'), '') <> 'mock' then
    raise exception 'mock verification disabled' using errcode = '42501';
  end if;
  update public.profiles
     set verified_female = true, verification_method = 'mock', verified_at = now()
   where id = uid;
  return found;
end $$;

-- Called by the digilocker-callback Edge Function (service role) after reading e-Aadhaar gender.
create function public.set_verified_from_digilocker(p_user uuid, p_is_female boolean, p_sub_hash text)
returns boolean
language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if exists (select 1 from public.profiles p where p.digilocker_sub_hash = p_sub_hash and p.id <> p_user) then
    raise exception 'digilocker account already linked to another user' using errcode = '23505';
  end if;
  update public.profiles
     set verified_female = p_is_female,
         verification_method = case when p_is_female then 'digilocker' else verification_method end,
         verified_at = case when p_is_female then now() else verified_at end,
         digilocker_sub_hash = p_sub_hash
   where id = p_user;
  return p_is_female;
end $$;

-- ---------------------------------------------------------------------------
-- Incidents (phone, authenticated)
-- ---------------------------------------------------------------------------
create function public.upsert_incident(
  p_id uuid, p_trigger text, p_source text,
  p_lat float8 default null, p_lng float8 default null, p_acc real default null,
  p_test boolean default true
) returns table (id uuid, share_token text, status text, created boolean)
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
  if p_source not in ('phone', 'watch') then
    raise exception 'invalid source %', p_source using errcode = '22023';
  end if;
  return query select * from public._upsert_incident_for(
    public._require_verified(), p_id, p_trigger, p_source, p_lat, p_lng, p_acc, p_test, now());
end $$;

create function public.add_incident_location(
  p_id uuid, p_lat float8, p_lng float8, p_acc real default null, p_fix_at timestamptz default now()
) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
  uid uuid := public._require_verified();
begin
  if not exists (select 1 from public.incidents i where i.id = p_id and i.user_id = uid) then
    raise exception 'incident not found' using errcode = 'P0002';
  end if;
  perform public._add_location(p_id, p_lat, p_lng, p_acc, p_fix_at, 'phone');
end $$;

create function public.end_incident(p_id uuid, p_status text) returns void
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
begin
  perform public._end_incident_for(public._require_verified(), p_id, p_status);
end $$;

create function public.register_watch() returns text
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  uid   uuid := public._require_verified();
  token text := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
begin
  delete from public.devices d where d.user_id = uid and d.kind = 'watch';
  insert into public.devices (user_id, kind, token_hash)
  values (uid, 'watch', encode(sha256(convert_to(token, 'UTF8')), 'hex'));
  return token;
end $$;

-- ---------------------------------------------------------------------------
-- Watch direct path (service role only; called by the watch-sos Edge Function)
-- ---------------------------------------------------------------------------
create function public.watch_sos(
  p_device_token text, p_action text, p_incident_id uuid,
  p_trigger text default 'knock',
  p_lat float8 default null, p_lng float8 default null, p_acc real default null,
  p_fix_at timestamptz default null, p_test boolean default true
) returns jsonb
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
  v_user    uuid;
  v_name    text;
  v_row     record;
  v_created boolean := false;
  v_token   text;
  v_status  text;
begin
  select d.user_id into v_user from public.devices d
   where d.kind = 'watch'
     and d.token_hash = encode(sha256(convert_to(coalesce(p_device_token, ''), 'UTF8')), 'hex');
  if v_user is null then
    raise exception 'invalid device token' using errcode = '28000';
  end if;
  if not public.is_verified_female(v_user) then
    raise exception 'not verified' using errcode = '42501';
  end if;
  update public.devices set last_seen = now() where user_id = v_user and kind = 'watch';

  if p_action = 'start' then
    select * into v_row from public._upsert_incident_for(
      v_user, p_incident_id, coalesce(p_trigger, 'knock'), 'watch_direct',
      p_lat, p_lng, p_acc, p_test, p_fix_at);
    v_created := v_row.created;
  elsif p_action = 'location' then
    if not exists (select 1 from public.incidents i where i.id = p_incident_id and i.user_id = v_user) then
      raise exception 'incident not found' using errcode = 'P0002';
    end if;
    perform public._add_location(p_incident_id, p_lat, p_lng, p_acc, p_fix_at, 'watch_direct');
  elsif p_action = 'cancel' then
    perform public._end_incident_for(v_user, p_incident_id, 'resolved');
  else
    raise exception 'invalid action %', p_action using errcode = '22023';
  end if;

  select i.share_token, i.status into v_token, v_status
    from public.incidents i where i.id = p_incident_id and i.user_id = v_user;
  select p.display_name into v_name from public.profiles p where p.id = v_user;

  return jsonb_build_object(
    'created', v_created,
    'status', v_status,
    'share_token', v_token,
    'display_name', v_name,
    'contacts', public._sos_contacts(v_user));
end $$;

-- ---------------------------------------------------------------------------
-- Live tracking (anon, by unguessable share token)
-- ---------------------------------------------------------------------------
create function public.track(p_token text) returns jsonb
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
declare
  v    record;
  locs jsonb;
begin
  select i.id, i.status, i.created_at, i.expires_at, p.display_name
    into v
    from public.incidents i join public.profiles p on p.id = i.user_id
   where i.share_token = p_token;
  if not found then
    return null;
  end if;

  if v.status <> 'active' or v.expires_at <= now() then
    return jsonb_build_object('name', split_part(v.display_name, ' ', 1), 'status', 'ended',
                              'startedAt', v.created_at, 'locations', '[]'::jsonb);
  end if;

  select coalesce(jsonb_agg(jsonb_build_object(
           'lat', st_y(l.geog::geometry), 'lng', st_x(l.geog::geometry),
           'acc', l.accuracy_m, 'at', l.fix_at) order by l.fix_at desc), '[]'::jsonb)
    into locs
    from (select * from public.incident_locations x
           where x.incident_id = v.id order by x.fix_at desc limit 50) l;

  return jsonb_build_object('name', split_part(v.display_name, ' ', 1), 'status', 'active',
                            'startedAt', v.created_at, 'locations', locs);
end $$;

-- ---------------------------------------------------------------------------
-- Map
-- ---------------------------------------------------------------------------
create function public.nearest_safe_places(p_lat float8, p_lng float8, p_n int default 5)
returns table (id bigint, name text, category text, lat float8, lng float8,
               distance_m float8, phone text, verified boolean)
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
#variable_conflict use_column
declare
  pt extensions.geography := public._point(p_lat, p_lng);
begin
  perform public._require_verified();
  return query
    select s.id, s.name, s.category, st_y(s.geog::geometry), st_x(s.geog::geometry),
           st_distance(s.geog, pt), s.phone, s.verified
      from public.safe_places s
     order by s.geog <-> pt
     limit greatest(1, least(coalesce(p_n, 5), 50));
end $$;

create function public.danger_cells_in_bbox(
  p_min_lat float8, p_min_lng float8, p_max_lat float8, p_max_lng float8
) returns table (lat float8, lng float8, score float8, reports int)
language plpgsql stable security definer set search_path = public, extensions, pg_temp as $$
#variable_conflict use_column
begin
  perform public._require_verified();
  return query
    select d.lat, d.lng, d.score, d.reports
      from public.danger_cells d
     where d.lat between p_min_lat and p_max_lat
       and d.lng between p_min_lng and p_max_lng
     order by d.score desc
     limit 2000;
end $$;

create function public.refresh_danger_cells() returns void
language sql security definer set search_path = public, extensions, pg_temp as $$
  refresh materialized view public.danger_cells;
$$;

create function public.report_unsafe(
  p_lat float8, p_lng float8, p_category text, p_note text default null
) returns bigint
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
  uid   uuid := public._require_verified();
  v_max int;
  v_id  bigint;
begin
  select c.value::int into v_max from public.app_config c where c.key = 'max_reports_per_day';
  if (select count(*) from public.unsafe_reports r
       where r.reporter_id = uid and r.created_at > now() - interval '1 day') >= coalesce(v_max, 10) then
    raise exception 'report limit reached, try again tomorrow' using errcode = '54000';
  end if;
  insert into public.unsafe_reports (reporter_id, geog, category, note)
  values (uid, public._point(p_lat, p_lng), p_category, nullif(trim(p_note), ''))
  returning id into v_id;
  -- Small demo dataset: refresh immediately so the map updates live.
  perform public.refresh_danger_cells();
  return v_id;
end $$;

-- ---------------------------------------------------------------------------
-- Housekeeping (pg_cron) and Telegram linking (service role)
-- ---------------------------------------------------------------------------
create function public.expire_incidents() returns int
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  n int;
begin
  with e as (
    update public.incidents set status = 'expired', ended_at = now()
     where status = 'active' and expires_at <= now()
    returning id
  ), ev as (
    insert into public.incident_events (incident_id, type) select id, 'expired' from e
  )
  select count(*) into n from e;
  return n;
end $$;

create function public.telegram_link(p_code text, p_chat_id bigint) returns jsonb
language plpgsql security definer set search_path = public, pg_temp as $$
declare
  v record;
begin
  update public.trusted_contacts c set telegram_chat_id = p_chat_id
   where c.telegram_link_code = lower(trim(p_code))
  returning c.name, (select p.display_name from public.profiles p where p.id = c.owner_id) as owner_name
  into v;
  if not found then return null; end if;
  return jsonb_build_object('contact_name', v.name, 'owner_name', v.owner_name);
end $$;

-- ---------------------------------------------------------------------------
-- Execute privileges. Supabase grants EXECUTE on new functions to anon/authenticated by default.
-- ---------------------------------------------------------------------------
revoke execute on all functions in schema public from public, anon, authenticated;

grant execute on function public.is_verified_female(uuid) to authenticated;
grant execute on function public.mock_verify_female() to authenticated;
grant execute on function public.upsert_incident(uuid, text, text, float8, float8, real, boolean) to authenticated;
grant execute on function public.add_incident_location(uuid, float8, float8, real, timestamptz) to authenticated;
grant execute on function public.end_incident(uuid, text) to authenticated;
grant execute on function public.register_watch() to authenticated;
grant execute on function public.nearest_safe_places(float8, float8, int) to authenticated;
grant execute on function public.danger_cells_in_bbox(float8, float8, float8, float8) to authenticated;
grant execute on function public.report_unsafe(float8, float8, text, text) to authenticated;
grant execute on function public.track(text) to anon, authenticated;

grant execute on function public.watch_sos(text, text, uuid, text, float8, float8, real, timestamptz, boolean) to service_role;
grant execute on function public.set_verified_from_digilocker(uuid, boolean, text) to service_role;
grant execute on function public.telegram_link(text, bigint) to service_role;
grant execute on function public.expire_incidents() to service_role;
grant execute on function public.refresh_danger_cells() to service_role;
