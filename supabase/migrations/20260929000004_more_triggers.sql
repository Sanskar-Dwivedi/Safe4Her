-- Accept every trigger the apps now send:
--   voice   = "HELP HELP HELP" heard by the watch (Voice SOS)
--   pocket  = three knocks on the phone (Pocket SOS)
--   journey = Safe Journey raised it after a missed check-in
alter table public.incidents drop constraint if exists incidents_trigger_check;
alter table public.incidents add constraint incidents_trigger_check
  check (trigger in ('knock', 'shake', 'button', 'voice', 'pocket', 'journey'));

create or replace function public._upsert_incident_for(
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
  if p_trigger not in ('knock', 'shake', 'button', 'voice', 'pocket', 'journey') then
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
