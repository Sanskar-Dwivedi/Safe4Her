-- Additive iPhone protocol. Terminal records intentionally survive retries and offline replays.
create table public.ios_terminal_sessions (
  kind text not null check (kind in ('incident', 'journey')),
  id uuid not null,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key(kind, id)
);
alter table public.ios_terminal_sessions enable row level security;
revoke all on public.ios_terminal_sessions from public, anon, authenticated;
alter table public.ios_journeys add column revision int not null default 0 check (revision >= 0);
alter table public.ios_journeys add column test_mode boolean not null default false;
alter table public.ios_alert_jobs alter column incident_id drop not null;
alter table public.ios_alert_jobs add column journey_id uuid references public.ios_journeys(id) on delete cascade;
alter table public.ios_alert_jobs add column event text not null default 'sos'
  check (event in ('sos', 'resolved', 'journey_started', 'journey_arrived', 'journey_ended'));
alter table public.ios_alert_jobs add column lease_token uuid;
alter table public.ios_alert_jobs drop constraint ios_alert_jobs_incident_id_contact_id_key;
create unique index ios_incident_event on public.ios_alert_jobs(incident_id, contact_id, event) where journey_id is null;
create unique index ios_journey_event on public.ios_alert_jobs(journey_id, contact_id, event) where journey_id is not null;
alter table public.ios_alert_jobs add constraint ios_job_session check (incident_id is not null or journey_id is not null);

create or replace function public._ios_enqueue(p_user uuid, p_incident uuid) returns void
language sql security definer set search_path = public, pg_temp as $$
  insert into public.ios_alert_jobs(user_id, incident_id, contact_id)
  select p_user, p_incident, c.id from public.trusted_contacts c
  join public.incidents i on i.id = p_incident and i.user_id = p_user
  where c.owner_id = p_user and c.receives_sos and c.telegram_chat_id is not null
    and not i.test_mode and i.status = 'active'
  on conflict do nothing
$$;

create function public._ios_lifecycle(p_user uuid, p_event text, p_incident uuid, p_journey uuid) returns void
language sql security definer set search_path = public, pg_temp as $$
  insert into public.ios_alert_jobs(user_id, incident_id, journey_id, contact_id, event)
  select p_user, p_incident, p_journey, c.id, p_event from public.trusted_contacts c
  where c.owner_id = p_user and c.receives_sos and c.telegram_chat_id is not null
    and ((p_journey is not null and exists(select 1 from public.ios_journeys j
      where j.id = p_journey and j.user_id = p_user and not j.test_mode)) or
      (p_journey is null and exists(select 1 from public.incidents i
      where i.id = p_incident and i.user_id = p_user and not i.test_mode)))
  on conflict do nothing
$$;

create or replace function public.ios_safety(p_action text, p_data jsonb) returns jsonb
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare
  uid uuid := public._require_verified();
  iid uuid; jid uuid; item record; j public.ios_journeys%rowtype;
  due timestamptz; ready boolean; recipients int; rev int := coalesce((p_data->>'revision')::int, 0);
begin
  if p_action not in ('capabilities','sos','end','journeyStart','journeyStatus','journeyExtend','journeyEnd') then
    raise exception 'unknown action';
  end if;
  -- Serialize all mutations for an owner, including deadline dispatch below.
  perform pg_advisory_xact_lock(hashtextextended(uid::text, 4104));
  ready := coalesce((select value::timestamptz > now() - interval '3 minutes'
    from public.app_config where key = 'ios_dispatch_heartbeat'), false);
  select count(*) into recipients from public.trusted_contacts where owner_id = uid and receives_sos and telegram_chat_id is not null;
  if p_action = 'capabilities' then
    return jsonb_build_object('schedulerReady', ready, 'eligibleRecipients', recipients);
  end if;
  if p_action in ('sos','end') then
    iid := (p_data->>'incidentId')::uuid;
    if iid is null then raise exception 'incidentId required'; end if;
    perform pg_advisory_xact_lock(hashtextextended(iid::text,4105));
    if exists(select 1 from public.incidents where id = iid and user_id <> uid) or
       exists(select 1 from public.ios_journeys where incident_id = iid and user_id <> uid) or
       exists(select 1 from public.ios_terminal_sessions where kind = 'incident' and id = iid and user_id <> uid) then
      raise exception 'incident belongs to another user';
    end if;
    if p_action = 'end' then
      insert into public.ios_terminal_sessions(kind,id,user_id) values('incident',iid,uid) on conflict do nothing;
      if exists(select 1 from public.incidents where id = iid and user_id = uid) then
        perform public._end_incident_for(uid, iid, 'resolved');
        perform public._ios_lifecycle(uid, 'resolved', iid, null);
      end if;
      update public.ios_journeys set state = 'ended' where incident_id = iid and user_id = uid;
      update public.ios_alert_jobs set state = 'cancelled', lease_token = null
        where incident_id = iid and user_id = uid and event = 'sos' and state = 'pending';
      return jsonb_build_object('state','resolved');
    end if;
    if exists(select 1 from public.ios_terminal_sessions where kind = 'incident' and id = iid) then
      return jsonb_build_object('state','resolved');
    end if;
    select * into item from public._upsert_incident_for(uid, iid, coalesce(p_data->>'trigger','button'), 'phone',
      (p_data->>'lat')::float8, (p_data->>'lng')::float8, (p_data->>'accuracyM')::real,
      coalesce((p_data->>'test')::boolean,true), coalesce((p_data->>'fixAt')::timestamptz,now()));
    perform public._ios_enqueue(uid, iid);
    return jsonb_build_object('state',item.status,'shareToken',case when item.status = 'active' then item.share_token end,
      'accepted',(select count(*) from public.ios_alert_jobs where incident_id = iid and event = 'sos' and state = 'sent'),
      'pending',(select count(*) from public.ios_alert_jobs where incident_id = iid and event = 'sos' and state = 'pending'));
  end if;
  jid := (p_data->>'journeyId')::uuid;
  if jid is null or rev < 0 then raise exception 'invalid journey request'; end if;
  perform pg_advisory_xact_lock(hashtextextended(jid::text,4106));
  if exists(select 1 from public.ios_journeys where id = jid and user_id <> uid) or
     exists(select 1 from public.ios_terminal_sessions where kind = 'journey' and id = jid and user_id <> uid) then
    raise exception 'journey belongs to another user';
  end if;
  if p_action = 'journeyEnd' then
    insert into public.ios_terminal_sessions(kind,id,user_id) values('journey',jid,uid) on conflict do nothing;
  elsif exists(select 1 from public.ios_terminal_sessions where kind = 'journey' and id = jid) then
    return jsonb_build_object('state','ended','revision',rev);
  end if;
  if p_action = 'journeyStart' and not exists(select 1 from public.ios_journeys where id = jid) then
    if not ready then raise exception 'journey scheduler is not running'; end if;
    if recipients = 0 then raise exception 'link at least one trusted contact to Telegram first'; end if;
    due := (p_data->>'deadline')::timestamptz;
    iid := (p_data->>'incidentId')::uuid;
    if due is null or due <= now() or due > now() + interval '24 hours' then raise exception 'invalid deadline'; end if;
    if exists(select 1 from public.incidents where id = iid) or
       exists(select 1 from public.ios_terminal_sessions where kind = 'incident' and id = iid) then
      raise exception 'incident ID already used';
    end if;
    insert into public.ios_journeys(id,user_id,incident_id,name,deadline,revision,test_mode)
      values(jid,uid,iid,p_data->>'name',due,rev,coalesce((p_data->>'test')::boolean,false));
    perform public._ios_lifecycle(uid,'journey_started',null,jid);
  end if;
  select * into j from public.ios_journeys where id = jid and user_id = uid for update;
  if not found and p_action = 'journeyEnd' then return jsonb_build_object('state','ended','revision',rev); end if;
  if not found then raise exception 'journey not found'; end if;
  -- Older iPhone builds did not send revisions. Preserve their extension API.
  if p_action = 'journeyExtend' and not (p_data ? 'revision') then rev := j.revision + 1; end if;
  if p_action = 'journeyExtend' and j.state = 'active' and rev > j.revision then
    due := (p_data->>'deadline')::timestamptz;
    if due is null or due <= now() or due > now() + interval '24 hours' then raise exception 'invalid deadline'; end if;
    update public.ios_journeys set deadline = due, revision = rev where id = jid;
  elsif p_action = 'journeyEnd' then
    if j.state = 'alerted' then
      perform public._end_incident_for(uid,j.incident_id,'resolved');
      insert into public.ios_terminal_sessions(kind,id,user_id) values('incident',j.incident_id,uid) on conflict do nothing;
      perform public._ios_lifecycle(uid,'resolved',j.incident_id,null);
      update public.ios_alert_jobs set state = 'cancelled', lease_token = null
        where incident_id = j.incident_id and event = 'sos' and state = 'pending';
    end if;
    if j.state <> 'ended' then
      perform public._ios_lifecycle(uid,case when p_data->>'reason' = 'arrived' then 'journey_arrived' else 'journey_ended' end,null,jid);
    end if;
    update public.ios_alert_jobs set state = 'cancelled', lease_token = null
      where journey_id = jid and event = 'journey_started' and state = 'pending';
    update public.ios_journeys set state = 'ended', revision = greatest(revision,rev) where id = jid;
  elsif p_action = 'journeyStatus' and p_data ? 'lat' and j.state = 'active' then
    perform public._point((p_data->>'lat')::float8,(p_data->>'lng')::float8);
    -- Reject future measurements and never replace a newer location with an offline replay.
    if (p_data->>'fixAt')::timestamptz <= now() + interval '5 seconds' and
       (j.fix_at is null or (p_data->>'fixAt')::timestamptz > j.fix_at) then
      update public.ios_journeys set lat = (p_data->>'lat')::float8,lng = (p_data->>'lng')::float8,
        accuracy = (p_data->>'accuracyM')::real,fix_at = (p_data->>'fixAt')::timestamptz where id = jid;
    end if;
  end if;
  return (select jsonb_build_object('state',x.state,'deadline',x.deadline,'revision',x.revision,
    'incidentState',i.status,'shareToken',case when i.status = 'active' then i.share_token end)
    from public.ios_journeys x left join public.incidents i on i.id = x.incident_id where x.id = jid);
end $$;

create or replace function public.ios_claim_alerts(p_user uuid default null) returns jsonb
language plpgsql security definer set search_path = public, extensions, pg_temp as $$
declare candidate record; j public.ios_journeys%rowtype; result jsonb;
begin
  if p_user is null then
    for candidate in select id,user_id from public.ios_journeys where state = 'active' and deadline <= now() loop
      if not pg_try_advisory_xact_lock(hashtextextended(candidate.user_id::text,4104)) then continue; end if;
      select * into j from public.ios_journeys where id = candidate.id for update;
      if j.state <> 'active' or j.deadline > now() then continue; end if;
      if public.is_verified_female(j.user_id) and not exists(select 1 from public.ios_terminal_sessions
          where kind = 'incident' and id = j.incident_id) then
        perform public._upsert_incident_for(j.user_id,j.incident_id,'journey','phone',j.lat,j.lng,j.accuracy,j.test_mode,j.fix_at);
        perform public._ios_enqueue(j.user_id,j.incident_id);
        update public.ios_journeys set state = 'alerted' where id = j.id;
      else update public.ios_journeys set state = 'ended' where id = j.id;
      end if;
    end loop;
  end if;
  update public.ios_alert_jobs a set state = 'cancelled',lease_token = null where a.state = 'pending' and (
    a.created_at < now() - interval '24 hours' or not public.is_verified_female(a.user_id) or
    not exists(select 1 from public.trusted_contacts c where c.id = a.contact_id and c.owner_id = a.user_id
      and c.receives_sos and c.telegram_chat_id is not null) or
    (a.event = 'sos' and not exists(select 1 from public.incidents i where i.id = a.incident_id
      and i.status = 'active' and not i.test_mode and i.expires_at > now())) or
    (a.event = 'journey_started' and not exists(select 1 from public.ios_journeys x where x.id = a.journey_id and x.state = 'active')));
  with picked as (
    select a.id from public.ios_alert_jobs a where a.state = 'pending' and a.retry_at <= now()
      and (a.lease_until is null or a.lease_until < now()) and (p_user is null or a.user_id = p_user)
    order by a.created_at,a.id limit 10 for update skip locked
  ), leased as (
    update public.ios_alert_jobs a set lease_until = now() + interval '2 minutes',lease_token = gen_random_uuid(),attempts = attempts + 1
      from picked where a.id = picked.id returning a.*
  ) select coalesce(jsonb_agg(jsonb_build_object('id',a.id,'lease',a.lease_token,'chatId',c.telegram_chat_id,
    'name',p.display_name,'token',case when a.event = 'sos' then i.share_token end,'trigger',i.trigger,
    'event',a.event,'destination',x.name,'deadline',x.deadline)), '[]'::jsonb) into result
    from leased a join public.trusted_contacts c on c.id = a.contact_id join public.profiles p on p.id = a.user_id
    left join public.incidents i on i.id = a.incident_id left join public.ios_journeys x on x.id = a.journey_id;
  return result;
end $$;

-- Old workers cannot finish a new lease. Deploy the function after this migration.
create or replace function public.ios_finish_alert(p_id uuid,p_sent boolean,p_retry_seconds int default 60) returns void
language sql security definer set search_path = public, pg_temp as $$
  update public.ios_alert_jobs set state = case when p_sent then 'sent' else 'pending' end,lease_until = null,
    retry_at = now() + make_interval(secs => greatest(1,least(p_retry_seconds,3600)))
  where id = p_id and state = 'pending' and lease_token is null
$$;
create function public.ios_finish_delivery(p_id uuid,p_lease uuid,p_sent boolean,p_retry_seconds int default 60) returns void
language sql security definer set search_path = public, pg_temp as $$
  update public.ios_alert_jobs set state = case when p_sent then 'sent' else 'pending' end,
    lease_until = null,lease_token = null,retry_at = now() + make_interval(secs => greatest(1,least(p_retry_seconds,3600)))
  where id = p_id and state = 'pending' and lease_token = p_lease and lease_until > now()
$$;
create or replace function public.ios_worker_heartbeat() returns void
language sql security definer set search_path = public, pg_temp as $$
  insert into public.app_config(key,value) values('ios_dispatch_heartbeat',now()::text)
  on conflict(key) do update set value = excluded.value
$$;
revoke all on function public._ios_lifecycle(uuid,text,uuid,uuid), public.ios_finish_delivery(uuid,uuid,boolean,int) from public,anon,authenticated;
grant execute on function public.ios_finish_delivery(uuid,uuid,boolean,int) to service_role;
