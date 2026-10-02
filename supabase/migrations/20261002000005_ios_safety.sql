-- iPhone cannot send silent SMS or rely on an app timer after suspension.
-- All new tables are private; authenticated clients use the owner-checked RPC only.
create table public.ios_journeys (
  id uuid primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  incident_id uuid not null unique,
  name text not null check (length(name) between 1 and 200),
  deadline timestamptz not null,
  state text not null default 'active' check (state in ('active', 'ended', 'alerted')),
  lat float8, lng float8, accuracy real, fix_at timestamptz,
  created_at timestamptz not null default now()
);
create unique index ios_one_active_journey on public.ios_journeys(user_id) where state = 'active';
create table public.ios_alert_jobs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  incident_id uuid not null references public.incidents(id) on delete cascade,
  contact_id uuid not null references public.trusted_contacts(id) on delete cascade,
  state text not null default 'pending' check (state in ('pending', 'sent', 'cancelled')),
  attempts int not null default 0,
  retry_at timestamptz not null default now(),
  lease_until timestamptz,
  created_at timestamptz not null default now(),
  unique (incident_id, contact_id)
);
create index ios_alert_ready on public.ios_alert_jobs(retry_at) where state = 'pending';
alter table public.ios_journeys enable row level security;
alter table public.ios_alert_jobs enable row level security;
revoke all on public.ios_journeys, public.ios_alert_jobs from anon, authenticated;

create function public._ios_enqueue(p_user uuid, p_incident uuid) returns void
language sql security definer set search_path = public, pg_temp as $$
  insert into public.ios_alert_jobs(user_id, incident_id, contact_id)
  select p_user, p_incident, c.id from public.trusted_contacts c
  join public.incidents i on i.id = p_incident and i.user_id = p_user
  where c.owner_id = p_user and c.receives_sos and c.telegram_chat_id is not null
    and not i.test_mode and i.status = 'active'
  on conflict (incident_id, contact_id) do nothing
$$;

create function public.ios_safety(p_action text, p_data jsonb) returns jsonb
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
  uid uuid := public._require_verified();
  iid uuid; jid uuid; item record; j public.ios_journeys%rowtype;
  due timestamptz; ready boolean;
begin
  ready := coalesce((select value::timestamptz > now() - interval '3 minutes'
                     from public.app_config where key = 'ios_dispatch_heartbeat'), false);
  if p_action = 'capabilities' then return jsonb_build_object('schedulerReady', ready); end if;
  if p_action in ('sos', 'end') then
    iid := (p_data->>'incidentId')::uuid;
    if p_action = 'end' then
      if exists(select 1 from public.incidents where id = iid and user_id = uid) then
        perform public._end_incident_for(uid, iid, 'resolved');
      elsif exists(select 1 from public.incidents where id = iid) then
        raise exception 'incident belongs to another user';
      end if;
      update public.ios_alert_jobs set state = 'cancelled' where incident_id = iid and user_id = uid and state = 'pending';
      return jsonb_build_object('state', 'resolved');
    end if;
    select * into item from public._upsert_incident_for(uid, iid, coalesce(p_data->>'trigger', 'button'), 'phone',
      (p_data->>'lat')::float8, (p_data->>'lng')::float8, (p_data->>'accuracyM')::real,
      coalesce((p_data->>'test')::boolean, true), coalesce((p_data->>'fixAt')::timestamptz, now()));
    perform public._ios_enqueue(uid, iid);
    return jsonb_build_object('state', item.status, 'shareToken', item.share_token,
      'accepted', (select count(*) from public.ios_alert_jobs where incident_id = iid and state = 'sent'),
      'pending', (select count(*) from public.ios_alert_jobs where incident_id = iid and state = 'pending'));
  end if;
  jid := (p_data->>'journeyId')::uuid;
  if p_action = 'journeyStart' then
    if not ready then raise exception 'journey scheduler is not running'; end if;
    if not exists(select 1 from public.trusted_contacts where owner_id = uid and receives_sos and telegram_chat_id is not null) then
      raise exception 'link at least one trusted contact to Telegram first';
    end if;
    due := (p_data->>'deadline')::timestamptz;
    if due is null or due <= now() or due > now() + interval '24 hours' then raise exception 'invalid deadline'; end if;
    insert into public.ios_journeys(id, user_id, incident_id, name, deadline)
      values(jid, uid, (p_data->>'incidentId')::uuid, p_data->>'name', due)
      on conflict (id) do nothing;
  end if;
  select * into j from public.ios_journeys where id = jid and user_id = uid for update;
  if not found then raise exception 'journey not found'; end if;
  if p_action = 'journeyExtend' then
    if j.state <> 'active' then raise exception 'journey is no longer active'; end if;
    due := (p_data->>'deadline')::timestamptz;
    if due is null or due <= now() or due > now() + interval '24 hours' then raise exception 'invalid deadline'; end if;
    update public.ios_journeys set deadline = due where id = jid;
  elsif p_action = 'journeyEnd' then
    if j.state = 'alerted' then
      perform public._end_incident_for(uid, j.incident_id, 'resolved');
      update public.ios_alert_jobs set state = 'cancelled' where incident_id = j.incident_id and state = 'pending';
    end if;
    update public.ios_journeys set state = 'ended' where id = jid;
  elsif p_action = 'journeyStatus' and p_data ? 'lat' then
    perform public._point((p_data->>'lat')::float8, (p_data->>'lng')::float8);
    update public.ios_journeys set lat = (p_data->>'lat')::float8, lng = (p_data->>'lng')::float8,
      accuracy = (p_data->>'accuracyM')::real, fix_at = (p_data->>'fixAt')::timestamptz where id = jid;
  elsif p_action not in ('journeyStart', 'journeyStatus') then raise exception 'unknown action';
  end if;
  return (select jsonb_build_object('state', x.state, 'shareToken', i.share_token)
          from public.ios_journeys x left join public.incidents i on i.id = x.incident_id where x.id = jid);
end $$;

-- Service-role only: transactional deadline processing, followed by leased delivery batches.
create function public.ios_claim_alerts(p_user uuid default null) returns jsonb
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare j record; result jsonb;
begin
  if p_user is null then
    for j in select * from public.ios_journeys where state = 'active' and deadline <= now() for update skip locked loop
      if public.is_verified_female(j.user_id) then
        perform public._upsert_incident_for(j.user_id, j.incident_id, 'journey', 'phone', j.lat, j.lng, j.accuracy, false, j.fix_at);
        perform public._ios_enqueue(j.user_id, j.incident_id);
        update public.ios_journeys set state = 'alerted' where id = j.id;
      else update public.ios_journeys set state = 'ended' where id = j.id;
      end if;
    end loop;
  end if;
  update public.ios_alert_jobs a set state = 'cancelled'
    where a.state = 'pending' and (a.created_at < now() - interval '24 hours' or
      not exists(select 1 from public.incidents i where i.id = a.incident_id and i.status = 'active' and i.expires_at > now()) or
      not exists(select 1 from public.trusted_contacts c where c.id = a.contact_id and c.receives_sos and c.telegram_chat_id is not null));
  with picked as (
    select a.id from public.ios_alert_jobs a
    where a.state = 'pending' and a.retry_at <= now() and (a.lease_until is null or a.lease_until < now())
      and (p_user is null or a.user_id = p_user)
    order by a.created_at limit 20 for update skip locked
  ), leased as (
    update public.ios_alert_jobs a set lease_until = now() + interval '2 minutes', attempts = attempts + 1
      from picked where a.id = picked.id returning a.*
  ) select coalesce(jsonb_agg(jsonb_build_object('id', a.id, 'chatId', c.telegram_chat_id, 'name', p.display_name,
      'token', i.share_token, 'trigger', i.trigger)), '[]'::jsonb) into result
    from leased a join public.trusted_contacts c on c.id = a.contact_id
    join public.profiles p on p.id = a.user_id join public.incidents i on i.id = a.incident_id;
  return result;
end $$;

create function public.ios_finish_alert(p_id uuid, p_sent boolean, p_retry_seconds int default 60) returns void
language sql security definer set search_path = public, pg_temp as $$
  update public.ios_alert_jobs set state = case when p_sent then 'sent' else 'pending' end,
    lease_until = null, retry_at = now() + make_interval(secs => greatest(1, least(p_retry_seconds, 3600)))
  where id = p_id and state = 'pending';
$$;
create function public.ios_worker_heartbeat() returns void
language sql security definer set search_path = public, pg_temp as $$
  insert into public.app_config(key, value) values ('ios_dispatch_heartbeat', now()::text)
    on conflict(key) do update set value = excluded.value;
  delete from public.ios_alert_jobs where state <> 'pending' and created_at < now() - interval '7 days';
  delete from public.ios_journeys where state <> 'active' and created_at < now() - interval '7 days';
$$;
revoke all on function public._ios_enqueue(uuid, uuid), public.ios_safety(text, jsonb),
 public.ios_claim_alerts(uuid), public.ios_finish_alert(uuid, boolean, int), public.ios_worker_heartbeat() from public, anon, authenticated;
grant execute on function public.ios_safety(text, jsonb) to authenticated;
grant execute on function public.ios_claim_alerts(uuid), public.ios_finish_alert(uuid, boolean, int), public.ios_worker_heartbeat() to service_role;
